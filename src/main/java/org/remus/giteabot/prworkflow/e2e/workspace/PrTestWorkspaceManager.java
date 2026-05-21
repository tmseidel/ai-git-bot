package org.remus.giteabot.prworkflow.e2e.workspace;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Allocates and cleans up sandboxed per-run workspaces for the
 * {@code E2ETestWorkflow}.
 *
 * <p>Every run gets its own directory under
 * {@code ${ai-git-bot.e2e.workspace-root:${java.io.tmpdir}/ai-bot-pr-tests}/run-&lt;id&gt;/}.
 * The directory is created with the framework-specific minimal scaffolding
 * ({@code package.json} + Playwright config for {@link E2eTestFramework#PLAYWRIGHT})
 * and is the only path the {@code TestAuthorAgent}'s {@code pr-test-write}
 * tool is allowed to write into — path-traversal guards mirror
 * {@code WorkspaceFileTools}.</p>
 *
 * <p>The workspace is intentionally <strong>not</strong> a checkout of the
 * source repository — generated tests must never have access to repository
 * source code or secrets. The agents communicate with the source repo
 * exclusively through the existing repository-aware tools
 * ({@code cat}, {@code rg}, {@code tree}, {@code get-issue}).</p>
 */
@Slf4j
@Component
public class PrTestWorkspaceManager {

    private final Path root;
    private final boolean npmInstallEnabled;

    public PrTestWorkspaceManager(
            @Value("${ai-git-bot.e2e.workspace-root:#{null}}") String configuredRoot,
            @Value("${ai-git-bot.e2e.npm-install-enabled:true}") boolean npmInstallEnabled) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            this.root = Path.of(System.getProperty("java.io.tmpdir"), "ai-bot-pr-tests")
                    .toAbsolutePath().normalize();
        } else {
            this.root = Path.of(configuredRoot).toAbsolutePath().normalize();
        }
        this.npmInstallEnabled = npmInstallEnabled;
        log.debug("PrTestWorkspaceManager root={} npmInstallEnabled={}", root, npmInstallEnabled);
    }

    /** Test seam: build a manager rooted at the given path with npm install disabled. */
    public static PrTestWorkspaceManager rootedAt(Path root) {
        return new PrTestWorkspaceManager(root.toAbsolutePath().normalize().toString(), false);
    }

    /**
     * Creates (or returns the existing) sandboxed workspace for the given
     * run id and scaffolds the framework-specific bootstrap files.
     */
    public Path allocate(long runId, E2eTestFramework framework) throws IOException {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        Path workspace = root.resolve("run-" + runId).toAbsolutePath().normalize();
        ensureUnderRoot(workspace);
        Files.createDirectories(workspace);
        scaffold(workspace, framework);
        return workspace;
    }

    /**
     * Resolves a caller-supplied relative path inside the given workspace,
     * rejecting any value that would escape the workspace root (absolute
     * paths, {@code ..} traversal, symlink trickery).
     */
    public Path resolveInsideWorkspace(Path workspace, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        Path candidate = workspace.resolve(relativePath).toAbsolutePath().normalize();
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        if (!candidate.startsWith(normalizedWorkspace)) {
            throw new IllegalArgumentException(
                    "Path '" + relativePath + "' escapes the workspace");
        }
        // No symlink-following escape either.
        if (Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException(
                    "Path '" + relativePath + "' resolves to a symlink");
        }
        return candidate;
    }

    /**
     * Removes the workspace for the given run id. Safe to call multiple
     * times; no-op if the directory does not exist or is not under the
     * configured root.
     */
    public void cleanup(long runId) {
        Path workspace = root.resolve("run-" + runId).toAbsolutePath().normalize();
        if (!workspace.startsWith(root) || !Files.exists(workspace)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(workspace)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("Failed to delete {}: {}", p, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.warn("Failed to walk workspace {}: {}", workspace, e.getMessage());
        }
    }


    private void ensureUnderRoot(Path candidate) {
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Computed workspace path " + candidate + " is not under root " + root);
        }
    }

    private void scaffold(Path workspace, E2eTestFramework framework) throws IOException {
        switch (framework) {
            case PLAYWRIGHT -> scaffoldPlaywright(workspace);
            case CYPRESS -> scaffoldCypress(workspace);
            case PYTEST -> scaffoldPytest(workspace);
            case K6 -> scaffoldK6(workspace);
        }
        Files.writeString(workspace.resolve("README.md"),
                "Generated by ai-git-bot E2ETestWorkflow. Do not commit.\n",
                StandardCharsets.UTF_8);
    }

    private void scaffoldPlaywright(Path workspace) throws IOException {
        // Always overwrite the package.json + config so a re-used workspace
        // (same run-id reused after a previous run) cannot drag in a stale
        // or AI-corrupted configuration.
        writeAlways(workspace.resolve("package.json"), """
                {
                  "name": "ai-bot-pr-tests",
                  "private": true,
                  "type": "module",
                  "scripts": { "test": "playwright test" },
                  "devDependencies": { "@playwright/test": "^1.60" }
                }
                """);
        writeAlways(workspace.resolve("playwright.config.ts"), """
                import { defineConfig } from '@playwright/test';
                // baseURL is populated at runtime from the deployment preview URL.
                // We fail loudly if BASE_URL is missing — silently falling back
                // to localhost:3000 hides a misconfigured preview deployment
                // and produces a flood of unrelated test failures.
                const baseURL = process.env.BASE_URL;
                if (!baseURL) {
                  throw new Error(
                      'BASE_URL environment variable is required — the PR-workflow '
                      + 'executor must set it from the deployment preview URL.');
                }
                export default defineConfig({
                  testDir: './tests',
                  reporter: [['json', { outputFile: 'playwright-report/report.json' }],
                             ['list']],
                  use: { baseURL }
                });
                """);
        Files.createDirectories(workspace.resolve("tests"));
        // Materialise a local node_modules so `import { defineConfig } from
        // '@playwright/test'` resolves from the workspace itself. The
        // Dockerfile pre-seeds npm's cache with playwright@1.60, so this is
        // an offline-first install. We skip it if node_modules/@playwright/test
        // already exists from a previous run on the same workspace.
        if (!Files.isDirectory(workspace.resolve("node_modules/@playwright/test"))) {
            runNpmInstall(workspace, "@playwright/test@1.60.0");
        } else {
            log.debug("@playwright/test already installed in {} — skipping npm install",
                    workspace);
        }
    }

    private void scaffoldCypress(Path workspace) throws IOException {
        writeAlways(workspace.resolve("package.json"), """
                { "name": "ai-bot-pr-tests", "private": true,
                  "scripts": { "test": "cypress run" },
                  "devDependencies": { "cypress": "^15" } }
                """);
        writeAlways(workspace.resolve("cypress.config.ts"), """
                import { defineConfig } from 'cypress';
                const baseUrl = process.env.BASE_URL;
                if (!baseUrl) {
                  throw new Error(
                      'BASE_URL environment variable is required — the PR-workflow '
                      + 'executor must set it from the deployment preview URL.');
                }
                export default defineConfig({
                  e2e: {
                    baseUrl,
                    specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}',
                    supportFile: false
                  }
                });
                """);
        Files.createDirectories(workspace.resolve("cypress/e2e"));
        if (!Files.isDirectory(workspace.resolve("node_modules/cypress"))) {
            runNpmInstall(workspace, "cypress@15");
        } else {
            log.debug("cypress already installed in {} — skipping npm install", workspace);
        }
    }

    private void scaffoldPytest(Path workspace) throws IOException {
        writeIfMissing(workspace.resolve("pyproject.toml"), """
                [project]
                name = "ai-bot-pr-tests"
                version = "0.0.0"
                requires-python = ">=3.10"
                dependencies = ["pytest>=8", "requests>=2"]
                """);
        Files.createDirectories(workspace.resolve("tests"));
    }

    private void scaffoldK6(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("scenarios"));
        writeIfMissing(workspace.resolve("README-k6.md"),
                "k6 scenarios live under ./scenarios — invoke with `k6 run`.\n");
    }

    private void writeIfMissing(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }

    /**
     * Always (re-)writes the given file. Used for scaffold files that must
     * never go stale or be tampered with by AI-generated tools across
     * re-used run-id workspaces.
     */
    private void writeAlways(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Best-effort {@code npm install -D <pkg>} inside {@code workspace}.
     * Failures (no {@code npm} on PATH, no network, registry errors) are
     * logged but do not abort scaffolding — the agent will surface a more
     * actionable error when {@code pr-test-run} subsequently fails.
     */
    private void runNpmInstall(Path workspace, String packageSpec) {
        if (!npmInstallEnabled) {
            log.debug("npm install skipped (disabled) for {} in {}", packageSpec, workspace);
            return;
        }
        List<String> cmd = List.of("npm", "install", "-D",
                "--no-audit", "--no-fund", "--loglevel=error",
                "--prefer-offline", packageSpec);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true);
            pb.environment().putIfAbsent("CI", "1");
            Process p = pb.start();
            try (var in = p.getInputStream()) {
                // Drain so the child does not block on a full pipe — but we
                // do not need to keep the output beyond the log line below.
                in.readAllBytes();
            }
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("npm install of {} in {} timed out after 120s", packageSpec, workspace);
                return;
            }
            if (p.exitValue() != 0) {
                log.warn("npm install of {} in {} exited with code {}",
                        packageSpec, workspace, p.exitValue());
            } else {
                log.debug("npm install of {} in {} succeeded", packageSpec, workspace);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("npm install of {} in {} failed: {} — continuing scaffold",
                    packageSpec, workspace, e.getMessage());
        }
    }
}
