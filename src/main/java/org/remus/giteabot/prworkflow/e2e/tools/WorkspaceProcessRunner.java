package org.remus.giteabot.prworkflow.e2e.tools;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.agent.validation.SandboxedCommandExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around {@link ProcessBuilder} for the {@code pr-test-run}
 * tool. Split into its own Spring bean so unit tests can substitute a stub
 * runner that returns a canned Playwright-style JSON report instead of
 * spawning an external process.
 *
 * <p>The runner intentionally combines stdout and stderr into a single
 * stream because most test frameworks (Playwright, pytest, k6) interleave
 * them, and the agent only needs a single textual blob to reason about the
 * outcome.</p>
 */
@Component
@RequiredArgsConstructor
public class WorkspaceProcessRunner {

    private static final int MAX_EXPORTED_ARTIFACT_BYTES = 4 * 1024 * 1024;
    private static final List<String> EXPORTED_ARTIFACT_DIRECTORIES = List.of(
            "playwright-report", "test-results", "cypress/screenshots", "cypress/videos");

    private final SandboxedCommandExecutor sandboxedCommandExecutor;

    /** Result of one process invocation. */
    public record ProcessResult(int exitCode, String combinedOutput, long durationMs, boolean timedOut) { }

    /** Backwards-compatible overload — no extra environment overrides. */
    public ProcessResult run(Path workspace, List<String> command,
                             long timeoutMs, int maxOutputBytes) throws IOException, InterruptedException {
        return run(workspace, command, Map.of(), timeoutMs, maxOutputBytes);
    }

    /**
     * Runs the given command in {@code workspace}, capturing combined
     * stdout/stderr (UTF-8) up to {@code maxOutputBytes} bytes and waiting
     * at most {@code timeout} ms before terminating the process.
     *
     * @param extraEnv additional environment variables supplied to the command
     *                 after the executor has scrubbed its environment. Use this to inject
     *                 {@code BASE_URL} for browser tests.
     */
    public ProcessResult run(Path workspace, List<String> command,
                              Map<String, String> extraEnv,
                              long timeoutMs, int maxOutputBytes) throws IOException, InterruptedException {
        long start = System.nanoTime();
        Map<String, String> sandboxEnvironment = new LinkedHashMap<>();
        if (extraEnv != null) {
            sandboxEnvironment.putAll(extraEnv);
        }
        sandboxEnvironment.put(SandboxedCommandExecutor.EXPORT_ARTIFACTS_ENV, "true");
        SandboxedCommandExecutor.Result result =
                sandboxedCommandExecutor.run(workspace, command, timeoutMs, TimeUnit.MILLISECONDS,
                        maxOutputBytes, sandboxEnvironment);
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        String output = restoreArtifacts(workspace, result.output());
        return new ProcessResult(result.exitCode(), output,
                durationMs, result.timedOut());
    }

    private String restoreArtifacts(Path workspace, String output) {
        if (output == null) {
            return "";
        }
        int marker = output.lastIndexOf(SandboxedCommandExecutor.ARTIFACTS_MARKER);
        if (marker < 0) {
            return output;
        }
        int totalBytes = 0;
        String artifactData = output.substring(marker + SandboxedCommandExecutor.ARTIFACTS_MARKER.length());
        for (String line : artifactData.split("\\R")) {
            int separator = line.indexOf('\t');
            if (separator <= 0) {
                continue;
            }
            String relativePath = line.substring(0, separator);
            if (!isExportedArtifact(relativePath)) {
                continue;
            }
            byte[] content;
            try {
                content = Base64.getDecoder().decode(line.substring(separator + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (content.length > MAX_EXPORTED_ARTIFACT_BYTES - totalBytes) {
                continue;
            }
            Path target = workspace.resolve(relativePath).normalize();
            if (!target.startsWith(workspace.normalize()) || Files.isSymbolicLink(target)
                    || !isSafeArtifactTarget(workspace, target)) {
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                totalBytes += content.length;
            } catch (IOException ignored) {
                // The command result remains available even when an optional artifact cannot be restored.
            }
        }
        return output.substring(0, marker).stripTrailing();
    }

    private boolean isExportedArtifact(String relativePath) {
        return EXPORTED_ARTIFACT_DIRECTORIES.stream()
                .anyMatch(directory -> relativePath.startsWith(directory + "/"));
    }

    private boolean isSafeArtifactTarget(Path workspace, Path target) {
        try {
            Path realWorkspace = workspace.toRealPath();
            Path existingParent = target.getParent();
            while (existingParent != null && !Files.exists(existingParent)) {
                existingParent = existingParent.getParent();
            }
            return existingParent != null && existingParent.toRealPath().startsWith(realWorkspace);
        } catch (IOException e) {
            return false;
        }
    }
}
