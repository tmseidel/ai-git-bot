package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.util.ProcessSupport;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Executes untrusted build/test/tool commands (agent validation tools,
 * generated test suites, npm installs of PR-controlled package.json files).
 *
 * <p>When {@code agent.sandbox.enabled=true} and a Docker host is reachable,
 * commands run in an <b>ephemeral container</b> with:</p>
 * <ul>
 *   <li>no network by default (configurable),</li>
 *   <li>hard memory/CPU/PID limits, dropped capabilities, no-new-privileges,</li>
 *   <li>read-only root filesystem (workspace mounted read-write at /ws),</li>
 *   <li>a minimal, secret-free environment,</li>
 *   <li>the application user (uid 1000), never root.</li>
 * </ul>
 *
 * <p>Without Docker the executor falls back to direct execution with a
 * scrubbed environment (and logs a warning once). The fallback keeps the
 * previous behavior but does not isolate filesystem/network.</p>
 */
@Slf4j
@Component
public class SandboxedCommandExecutor {

    /** Result of one sandboxed invocation. */
    public record Result(boolean success, int exitCode, String output, boolean timedOut) {
    }

    private final AgentConfigProperties.SandboxConfig config;
    private volatile Boolean dockerAvailable;

    public SandboxedCommandExecutor(AgentConfigProperties agentConfig) {
        this.config = agentConfig.getSandbox();
    }

    /** Whether commands will actually run inside a Docker sandbox. */
    public boolean isSandboxed() {
        return config.isEnabled() && dockerAvailable();
    }

    public Result run(Path workspaceDir, List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        return run(workspaceDir, command, timeoutSeconds, Map.of());
    }

    public Result run(Path workspaceDir, List<String> command, int timeoutSeconds,
                      Map<String, String> extraEnv) throws IOException, InterruptedException {
        if (isSandboxed()) {
            return runInDocker(workspaceDir, command, timeoutSeconds, extraEnv);
        }
        if (config.isEnabled()) {
            log.warn("agent.sandbox.enabled=true but no Docker host is reachable - "
                    + "running command directly with scrubbed environment (NOT isolated)");
        }
        return runDirect(workspaceDir, command, timeoutSeconds, extraEnv);
    }

    // ---- direct execution (fallback) ------------------------------------

    private Result runDirect(Path workspaceDir, List<String> command, int timeoutSeconds,
                             Map<String, String> extraEnv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceDir.toFile());
        pb.redirectErrorStream(true);
        ProcessSupport.scrubEnvironment(pb);
        pb.environment().putIfAbsent("CI", "1");
        if (extraEnv != null) {
            extraEnv.forEach((k, v) -> {
                if (k != null && v != null) {
                    pb.environment().put(k, v);
                }
            });
        }

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new Result(false, -1, output.toString(), true);
        }
        return new Result(process.exitValue() == 0, process.exitValue(), output.toString(), false);
    }

    // ---- docker sandbox ---------------------------------------------------

    private Result runInDocker(Path workspaceDir, List<String> command, int timeoutSeconds,
                               Map<String, String> extraEnv) throws IOException, InterruptedException {
        String name = "ai-bot-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", name,
                // The toolchain image carries an ENTRYPOINT (tini + java);
                // clear it so the sandbox runs exactly the given command.
                // Note: must be the "--entrypoint=" (equals) form — docker
                // mis-parses a separate empty-string argument.
                "--entrypoint=",
                "--network", config.getNetwork(),
                "--memory", config.getMemoryMb() + "m",
                "--cpus", String.valueOf(config.getCpus()),
                "--pids-limit", String.valueOf(config.getPidsLimit()),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--read-only",
                "--tmpfs", "/tmp:rw,size=512m",
                "--user", "1000:1000",
                "-e", "CI=1"));
        if (extraEnv != null) {
            extraEnv.forEach((k, v) -> {
                if (k != null && v != null && k.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    cmd.add("-e");
                    cmd.add(k + "=" + v);
                }
            });
        }
        cmd.add("-v");
        cmd.add(workspaceDir.toAbsolutePath() + ":/ws");
        cmd.add("-w");
        cmd.add("/ws");
        cmd.add(resolveImage());
        cmd.addAll(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        ProcessSupport.scrubEnvironment(pb);
        String dockerHost = resolveDockerHost();
        if (dockerHost != null) {
            pb.environment().put("DOCKER_HOST", dockerHost);
        }

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            killContainer(name);
            return new Result(false, -1, output.toString(), true);
        }
        return new Result(process.exitValue() == 0, process.exitValue(), output.toString(), false);
    }

    private void killContainer(String name) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "rm", "-f", name);
            pb.redirectErrorStream(true);
            ProcessSupport.scrubEnvironment(pb);
            String dockerHost = resolveDockerHost();
            if (dockerHost != null) {
                pb.environment().put("DOCKER_HOST", dockerHost);
            }
            pb.start().waitFor(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to remove sandbox container {}: {}", name, e.getMessage());
        }
    }

    private String resolveImage() {
        if (config.getImage() != null && !config.getImage().isBlank()) {
            return config.getImage();
        }
        // The upstream image ships the full build/test toolchain.
        return "tmseidel/ai-git-bot:latest";
    }

    private String resolveDockerHost() {
        if (config.getDockerHost() != null && !config.getDockerHost().isBlank()) {
            return config.getDockerHost();
        }
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && !dockerHost.isBlank()) {
            return dockerHost;
        }
        return System.getenv("CLOUDRON_DOCKER_HOST");
    }

    private boolean dockerAvailable() {
        Boolean cached = dockerAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (dockerAvailable == null) {
                dockerAvailable = probeDocker();
            }
        }
        return dockerAvailable;
    }

    private boolean probeDocker() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}");
            pb.redirectErrorStream(true);
            ProcessSupport.scrubEnvironment(pb);
            String dockerHost = resolveDockerHost();
            if (dockerHost != null) {
                pb.environment().put("DOCKER_HOST", dockerHost);
            }
            Process process = pb.start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            boolean ok = process.exitValue() == 0;
            log.info("Docker sandbox availability probe: {}", ok ? "available" : "unavailable");
            return ok;
        } catch (Exception e) {
            log.info("Docker sandbox availability probe failed: {}", e.getMessage());
            return false;
        }
    }
}
