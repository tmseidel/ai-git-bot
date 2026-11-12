package org.remus.giteabot.agent.packagemanager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Decides which package manager a checkout uses, so the tool layer can refuse npm
 * for a Yarn-managed repository instead of relying on the model to notice.
 *
 * <p>Two markers decide it, the authoritative one first:</p>
 * <ol>
 *   <li>the {@code packageManager} field in {@code package.json} (the Corepack contract);</li>
 *   <li>{@code yarn.lock} — its {@code # yarn lockfile v1} header also tells Yarn Classic
 *       from a modern release;</li>
 *   <li>{@code package-lock.json} / {@code npm-shrinkwrap.json};</li>
 *   <li>nothing — {@link PackageManager#UNKNOWN}, which keeps the previous behaviour.</li>
 * </ol>
 *
 * <p>A repository carrying both a Yarn and an npm lockfile is treated as Yarn: a Yarn
 * project that accumulated a stray {@code package-lock.json} must not have npm run in it,
 * because that is exactly the file the pull request would then carry.</p>
 */
@Slf4j
@Component
public class PackageManagerDetector {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LOCKFILE_HEADER_LINES = 5;
    private static final String YARN_CLASSIC_LOCKFILE = "yarn lockfile v1";

    public PackageManagerDetection detect(Path workspace) {
        Optional<String> declared = packageManagerField(workspace.resolve("package.json"));
        if (declared.isPresent()) {
            PackageManagerDetection detection = fromPackageManagerField(declared.get());
            if (detection != null) {
                return detection;
            }
        }

        Path yarnLock = workspace.resolve("yarn.lock");
        if (Files.isRegularFile(yarnLock)) {
            return PackageManagerDetection.yarn(yarnLockVersionSpec(yarnLock), "yarn.lock");
        }
        if (Files.isRegularFile(workspace.resolve("package-lock.json"))) {
            return PackageManagerDetection.npm("package-lock.json");
        }
        if (Files.isRegularFile(workspace.resolve("npm-shrinkwrap.json"))) {
            return PackageManagerDetection.npm("npm-shrinkwrap.json");
        }

        return PackageManagerDetection.unknown("no package manager marker");
    }

    /** {@code packageManager: "yarn@4.1.1+sha256.…"}; {@code null} for a manager we do not drive. */
    private PackageManagerDetection fromPackageManagerField(String value) {
        int at = value.indexOf('@');
        String name = (at < 0 ? value : value.substring(0, at)).strip().toLowerCase();
        String version = at < 0 ? "" : versionWithoutHash(value.substring(at + 1));
        if ("yarn".equals(name)) {
            return PackageManagerDetection.yarn(version.isBlank() ? null : version,
                    "package.json packageManager field (" + value + ")");
        }
        if ("npm".equals(name)) {
            return PackageManagerDetection.npm("package.json packageManager field (" + value + ")");
        }
        return null;
    }

    /** Corepack appends the integrity hash to the field: {@code 4.1.1+sha256.abc}. */
    private static String versionWithoutHash(String version) {
        String stripped = version.strip();
        int plus = stripped.indexOf('+');
        return plus < 0 ? stripped : stripped.substring(0, plus).strip();
    }

    private static Optional<String> packageManagerField(Path packageJson) {
        if (!Files.isRegularFile(packageJson)) {
            return Optional.empty();
        }
        try {
            JsonNode field = JSON.readTree(Files.readString(packageJson, StandardCharsets.UTF_8))
                    .get("packageManager");
            if (field == null || !field.isString() || field.asString().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(field.asString().strip());
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read {}: {}", packageJson, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Yarn Classic writes {@code # yarn lockfile v1} as the first line; a modern lockfile
     * carries a generated-by header and no version marker, so its version stays unknown.
     */
    private static String yarnLockVersionSpec(Path yarnLock) {
        try (Stream<String> lines = Files.lines(yarnLock, StandardCharsets.UTF_8)) {
            return lines.limit(LOCKFILE_HEADER_LINES)
                    .anyMatch(line -> line.contains(YARN_CLASSIC_LOCKFILE)) ? "1" : null;
        } catch (IOException e) {
            log.debug("Could not read {}: {}", yarnLock, e.getMessage());
            return null;
        }
    }
}
