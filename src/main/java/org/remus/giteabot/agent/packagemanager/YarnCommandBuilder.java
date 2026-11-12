package org.remus.giteabot.agent.packagemanager;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@code yarn …} tool call into the process the workspace actually gets.
 *
 * <p>The model never picks the Yarn version. When {@code packageManager} pins it, the
 * command is a bare {@code corepack yarn …}: Corepack resolves the version from
 * {@code package.json} and verifies the {@code +sha512…} integrity hash the repository
 * opted into, which an explicit {@code yarn@<version>} would bypass. An explicit version
 * is passed only for the one thing Corepack cannot read off the manifest — the Classic
 * major inferred from a v1 lockfile. A checkout that vendors its release through
 * {@code yarnPath} runs that committed file with Node, which is what Yarn's own shim does.
 * Without Corepack the {@code yarn} on {@code PATH} is used. Yarn Classic may be installed
 * through npm as a fallback, but only when the operator set
 * {@code agent.validation.allow-yarn-install=true}.</p>
 *
 * <p>A Yarn-managed checkout with neither a pinned version nor a {@code yarnPath} is
 * refused rather than run with Corepack's default, which is Yarn Classic: Classic reads a
 * Berry lockfile as if it were its own and rewrites it in v1 format. Lockfile changes are
 * meant to come from the requested dependency operation, not from a guessed Yarn version.</p>
 *
 * <p>Corepack's auto-pin is disabled, so a Yarn run never adds a {@code packageManager}
 * field to {@code package.json} behind the user's back; the same flag is set in the
 * image's {@code ENV}.</p>
 */
@Slf4j
@Component
public class YarnCommandBuilder {

    /** The property that lets this tool install Yarn Classic into the image. */
    static final String YARN_INSTALL_PROPERTY = "agent.validation.allow-yarn-install";

    /**
     * Corepack asks before downloading a package manager, and some Corepack versions
     * auto-pin by writing a {@code packageManager} field into {@code package.json} —
     * an agent run has nobody to answer the first and must not produce the second.
     */
    private static final Map<String, String> COREPACK_ENVIRONMENT = Map.of(
            "COREPACK_ENABLE_DOWNLOAD_PROMPT", "0",
            "COREPACK_ENABLE_AUTO_PIN", "0");

    /** Is a binary runnable? Injectable so tests can pin the toolchain an image provides. */
    @FunctionalInterface
    public interface CommandLocator {
        boolean available(String command);
    }

    /**
     * A resolved invocation: preparation commands in order, then the command to run in the
     * workspace; or a {@code failure} explaining why no command could be built.
     */
    public record Invocation(List<List<String>> preparation, List<String> command,
                             Map<String, String> environment, String failure) {

        public boolean failed() {
            return failure != null;
        }

        static Invocation failed(String failure) {
            return new Invocation(List.of(), List.of(), Map.of(), failure);
        }
    }

    private final AgentConfigProperties config;
    private final CommandLocator locator;

    @Autowired
    public YarnCommandBuilder(AgentConfigProperties config) {
        this(config, YarnCommandBuilder::onPath);
    }

    /** Test seam: pin which binaries the image provides. */
    YarnCommandBuilder(AgentConfigProperties config, CommandLocator locator) {
        this.config = config;
        this.locator = locator;
    }

    public Invocation build(PackageManagerDetection detection, List<String> arguments) {
        List<String> args = arguments == null ? List.of() : arguments;
        String versionSpec = detection.yarnVersionSpec();
        List<List<String>> preparation = new ArrayList<>();
        List<String> command;

        // A committed Yarn 2+ release: run it with Node, exactly as Yarn's own shim would.
        // That covers repositories that pin through yarnPath and predate Corepack.
        if (detection.yarnPath() != null) {
            if (!locator.available("node")) {
                return Invocation.failed("This repository runs Yarn from its committed release ("
                        + detection.yarnPath() + "), but node is not on PATH. Install Node.js in "
                        + "the image.");
            }
            command = new ArrayList<>(List.of("node", detection.yarnPath()));
            command.addAll(args);
            return new Invocation(List.of(), List.copyOf(command), Map.of(), null);
        }

        // A Yarn checkout with no pinned version: running Corepack here would use its
        // default (Yarn Classic 1.x), and Classic rewrites a Berry yarn.lock in v1 format.
        if (detection.isYarn() && versionSpec == null) {
            return Invocation.failed("This repository is managed by Yarn (" + detection.evidence()
                    + "), but no Yarn version is pinned in package.json. Running Corepack without a "
                    + "pin falls back to its default — Yarn Classic 1.x — which rewrites a modern "
                    + "yarn.lock in the v1 format. Pin the version first, for example "
                    + "\"packageManager\": \"yarn@4.1.1\", or commit a release and point yarnPath at it.");
        }

        if (locator.available("corepack")) {
            command = new ArrayList<>();
            command.add("corepack");
            // A manifest pin is left to Corepack so it also verifies the integrity hash; only
            // the Classic major inferred from a v1 lockfile is passed explicitly.
            command.add(detection.yarnVersionFromManifest() || versionSpec == null
                    ? "yarn" : "yarn@" + versionSpec);
            command.addAll(args);
        } else if (locator.available("yarn")) {
            command = new ArrayList<>(List.of("yarn"));
            command.addAll(args);
            if (versionSpec != null) {
                log.info("Yarn {} is pinned ({}) but Corepack is not installed; using the yarn on PATH",
                        versionSpec, detection.evidence());
            }
        } else if (!config.getValidation().isAllowYarnInstall()) {
            return Invocation.failed("This repository is managed by Yarn (" + detection.evidence()
                    + "), but this image has neither corepack nor yarn on PATH. Install Yarn and "
                    + "Corepack in the image, or set " + YARN_INSTALL_PROPERTY + "=true so the yarn "
                    + "tool may install Yarn Classic through npm.");
        } else if (!isClassic(versionSpec)) {
            return Invocation.failed("This repository is managed by Yarn (" + detection.evidence()
                    + "), but this image has neither corepack nor yarn on PATH, and only Yarn Classic "
                    + "can be installed through npm. Use an image with Node.js 16+ (it bundles "
                    + "Corepack) and run `corepack enable`.");
        } else if (!locator.available("npm")) {
            return Invocation.failed("This repository is managed by Yarn (" + detection.evidence()
                    + "), but this image has neither corepack, yarn nor npm on PATH, so the Yarn "
                    + "toolchain cannot be prepared. Install Yarn in the image.");
        } else {
            // Yarn Classic only: Yarn 2+ is not published as the `yarn` npm package.
            preparation.add(List.of("npm", "install", "-g", "yarn@" + versionSpec));
            command = new ArrayList<>(List.of("yarn"));
            command.addAll(args);
            log.info("Yarn {} is not installed; installing Yarn Classic through npm instead ({})",
                    versionSpec, YARN_INSTALL_PROPERTY + "=true");
        }

        return new Invocation(List.copyOf(preparation), List.copyOf(command),
                COREPACK_ENVIRONMENT, null);
    }

    /** {@code 1} or {@code 1.x.y} — the only versions npm may install. */
    private static boolean isClassic(String versionSpec) {
        return versionSpec != null && (versionSpec.equals("1") || versionSpec.startsWith("1."));
    }

    /** Whether {@code command} is an executable on the child environment's {@code PATH}. */
    static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (!directory.isBlank() && Files.isExecutable(Path.of(directory, command))) {
                return true;
            }
        }
        return false;
    }
}
