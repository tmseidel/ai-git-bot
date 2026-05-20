package org.remus.giteabot.prworkflow.e2e.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
public class WorkspaceProcessRunner {

    /** Result of one process invocation. */
    public record ProcessResult(int exitCode, String combinedOutput, long durationMs, boolean timedOut) { }

    /**
     * Runs the given command in {@code workspace}, capturing combined
     * stdout/stderr (UTF-8) up to {@code maxOutputBytes} bytes and waiting
     * at most {@code timeout} ms before terminating the process.
     */
    public ProcessResult run(Path workspace, List<String> command,
                             long timeoutMs, int maxOutputBytes) throws IOException, InterruptedException {
        long start = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        // Make sure CI-style envs do not break the runner with interactive prompts.
        pb.environment().putIfAbsent("CI", "1");
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0) {
                    if (out.length() >= maxOutputBytes) {
                        // Drain so the child does not block on a full pipe.
                        continue;
                    }
                    int spare = Math.max(0, maxOutputBytes - out.length());
                    int take = Math.min(read, spare);
                    if (take > 0) {
                        out.append(new String(buf, 0, take, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // process exited
            }
        }, "pr-test-run-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(2_000);
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            return new ProcessResult(-1, out.toString(), durationMs, true);
        }
        reader.join(2_000);
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        return new ProcessResult(process.exitValue(), out.toString(), durationMs, false);
    }
}
