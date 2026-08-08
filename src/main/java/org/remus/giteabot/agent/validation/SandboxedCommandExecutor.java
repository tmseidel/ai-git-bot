package org.remus.giteabot.agent.validation;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.util.ProcessSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs untrusted build and test commands in an ephemeral Docker container when
 * sandboxing is enabled and Docker is reachable. Direct fallback execution
 * keeps the previous behavior but always uses a scrubbed environment.
 */
@Slf4j
@Component
public class SandboxedCommandExecutor {

    private static final int MAX_CAPTURED_OUTPUT_BYTES = 1_000_000;
    private static final String SANDBOX_LABEL = "ai-git-bot.sandbox=true";
    public static final String INSTALL_PACKAGE_ENV = "AI_GIT_BOT_INSTALL_PACKAGE";
    public static final String EXPORT_ARTIFACTS_ENV = "AI_GIT_BOT_EXPORT_ARTIFACTS";
    public static final String ARTIFACTS_MARKER = "__AI_GIT_BOT_ARTIFACTS__";
    private static final String WORKSPACE_SETUP_COMMAND = """
            cp -R /source/. /ws/ && if [ -n "${AI_GIT_BOT_INSTALL_PACKAGE:-}" ]; then
              timeout --signal=KILL "${AI_GIT_BOT_TIMEOUT_SECONDS}s" sh -c 'npm install -D --no-audit --no-fund --loglevel=error --prefer-offline "$1" && shift && exec "$@"' sandbox-install "$AI_GIT_BOT_INSTALL_PACKAGE" "$@"
            else
              timeout --signal=KILL "${AI_GIT_BOT_TIMEOUT_SECONDS}s" "$@"
            fi > /tmp/command-output 2>&1; status=$?
            if [ "${AI_GIT_BOT_EXPORT_ARTIFACTS:-}" = "true" ]; then
              head -c 49152 /tmp/command-output
              printf '\\n__AI_GIT_BOT_ARTIFACTS__\\n'
              : > /tmp/artifact-files
              for root in playwright-report test-results cypress/screenshots cypress/videos; do
                if [ -d "$root" ]; then find "$root" -type f -size -4194305c -print >> /tmp/artifact-files; fi
              done
              total=0
              while IFS= read -r file; do
                size=$(wc -c < "$file")
                if [ "$((total + size))" -le 4194304 ]; then
                  printf '%s\\t' "$file"
                  base64 "$file" | tr -d '\\n'
                  printf '\\n'
                  total=$((total + size))
                fi
              done < /tmp/artifact-files
            else
              cat /tmp/command-output
            fi
            exit "$status"
            """;

    /** Result of one command invocation. */
    public record Result(boolean success, int exitCode, String output, boolean timedOut) {
    }

    private final AgentConfigProperties.SandboxConfig config;

    public SandboxedCommandExecutor(AgentConfigProperties agentConfig) {
        this.config = agentConfig.getSandbox();
    }

    @PostConstruct
    void reapOrphanedSandboxes() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "docker", "ps", "-aq", "--filter", "label=" + SANDBOX_LABEL);
            processBuilder.redirectErrorStream(true);
            ProcessSupport.scrubEnvironment(processBuilder);
            String dockerHost = resolveDockerHost();
            if (dockerHost != null) {
                processBuilder.environment().put("DOCKER_HOST", dockerHost);
            }
            ProcessSupport.CommandResult result = ProcessSupport.waitFor(processBuilder.start(), 15,
                    TimeUnit.SECONDS, 64 * 1024);
            if (result.finished() && result.exitCode() == 0) {
                result.output().lines().map(String::strip).filter(id -> !id.isEmpty())
                        .forEach(this::killContainer);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Could not reap orphaned sandbox containers: {}", e.getMessage());
        }
    }

    /** Whether commands can run inside the configured Docker sandbox. */
    public boolean isSandboxed() {
        return config.isEnabled() && probeDocker();
    }

    /** Whether the configured sandbox network prevents dependency downloads and preview access. */
    public boolean isNetworkIsolated() {
        return "none".equalsIgnoreCase(config.getNetwork()) && isSandboxed();
    }

    /** Runs a command with no additional environment variables. */
    public Result run(Path workspaceDir, List<String> command, int timeoutSeconds)
            throws IOException, InterruptedException {
        return run(workspaceDir, command, timeoutSeconds, Map.of());
    }

    /** Runs a command in the Docker sandbox when enabled, otherwise directly with a scrubbed environment. */
    public Result run(Path workspaceDir, List<String> command, int timeoutSeconds,
                      Map<String, String> extraEnvironment) throws IOException, InterruptedException {
        return run(workspaceDir, command, timeoutSeconds, TimeUnit.SECONDS, MAX_CAPTURED_OUTPUT_BYTES,
                extraEnvironment);
    }

    /** Runs a command with an exact timeout and bounded combined output. */
    public Result run(Path workspaceDir, List<String> command, long timeout, TimeUnit timeoutUnit,
                      int maxOutputBytes, Map<String, String> extraEnvironment)
            throws IOException, InterruptedException {
        if (!config.isEnabled()) {
            return runDirect(workspaceDir, command, timeout, timeoutUnit, maxOutputBytes, extraEnvironment);
        }
        long deadlineNanos = System.nanoTime() + timeoutUnit.toNanos(timeout);
        long remainingTimeoutMs = remainingTimeoutMillis(deadlineNanos);
        if (!probeDocker(remainingTimeoutMs, TimeUnit.MILLISECONDS)) {
            return handleDockerUnavailable(workspaceDir, command, remainingTimeoutMs, TimeUnit.MILLISECONDS,
                    maxOutputBytes, extraEnvironment,
                    "no Docker host is reachable");
        }
        try {
            return runInDocker(workspaceDir, command, remainingTimeoutMillis(deadlineNanos), TimeUnit.MILLISECONDS,
                    maxOutputBytes, extraEnvironment);
        } catch (DockerUnavailableException e) {
            return handleDockerUnavailable(workspaceDir, command, remainingTimeoutMillis(deadlineNanos),
                    TimeUnit.MILLISECONDS, maxOutputBytes, extraEnvironment,
                    e.getMessage());
        }
    }

    private Result handleDockerUnavailable(Path workspaceDir, List<String> command, long timeout,
                                           TimeUnit timeoutUnit, int maxOutputBytes,
                                           Map<String, String> extraEnvironment, String reason)
            throws IOException, InterruptedException {
        if (!config.isFallbackToDirect()) {
            throw new IOException("Docker sandbox is enabled but unavailable: " + reason);
        }
        log.warn("Docker sandbox is unavailable; running directly with a scrubbed environment: {}", reason);
        return runDirect(workspaceDir, command, timeout, timeoutUnit, maxOutputBytes, extraEnvironment);
    }

    private Result runDirect(Path workspaceDir, List<String> command, long timeout, TimeUnit timeoutUnit,
                             int maxOutputBytes, Map<String, String> extraEnvironment)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workspaceDir.toFile());
        processBuilder.redirectErrorStream(true);
        ProcessSupport.scrubEnvironment(processBuilder);
        processBuilder.environment().put("CI", "1");
        addExtraEnvironment(processBuilder.environment(), extraEnvironment);
        if (config.isEnabled()) {
            return toResult(ProcessSupport.runInNewProcessGroup(processBuilder, timeout, timeoutUnit,
                    maxOutputBytes));
        }
        return toResult(ProcessSupport.waitFor(processBuilder.start(), timeout, timeoutUnit, maxOutputBytes));
    }

    private Result runInDocker(Path workspaceDir, List<String> command, long timeout, TimeUnit timeoutUnit,
                               int maxOutputBytes, Map<String, String> extraEnvironment)
            throws IOException, InterruptedException {
        Path configOverlay = createGitConfigOverlay(workspaceDir);
        String containerName = "ai-bot-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
        boolean containerStarted = false;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(buildDockerCommand(
                    workspaceDir, command, extraEnvironment, containerName, configOverlay, timeout, timeoutUnit));
            processBuilder.redirectErrorStream(true);
            ProcessSupport.scrubEnvironment(processBuilder);
            String dockerHost = resolveDockerHost();
            if (dockerHost != null) {
                processBuilder.environment().put("DOCKER_HOST", dockerHost);
            }
            Process process;
            try {
                process = processBuilder.start();
                containerStarted = true;
            } catch (IOException e) {
                throw new DockerUnavailableException(e);
            }
            ProcessSupport.CommandResult result;
            try {
                result = ProcessSupport.waitFor(process, timeout, timeoutUnit, maxOutputBytes);
            } catch (InterruptedException e) {
                throw e;
            }
            if (!result.finished()) {
                return toResult(result);
            }
            return toResult(result);
        } finally {
            if (containerStarted) {
                killContainer(containerName);
            }
            deleteGitConfigOverlay(configOverlay);
        }
    }

    /** Builds the Docker invocation separately so its isolation settings are regression-testable. */
    List<String> buildDockerCommand(Path workspaceDir, List<String> command,
                                     Map<String, String> extraEnvironment, String containerName,
                                     Path configOverlay, long timeout, TimeUnit timeoutUnit) {
        long timeoutMs = timeoutUnit.toMillis(timeout);
        long timeoutSeconds = Math.max(1L, (timeoutMs + 999L) / 1_000L);
        List<String> dockerCommand = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "--label", SANDBOX_LABEL,
                "--entrypoint=/bin/sh",
                "--network", config.getNetwork(),
                "--memory", config.getMemoryMb() + "m",
                "--cpus", String.valueOf(config.getCpus()),
                "--pids-limit", String.valueOf(config.getPidsLimit()),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--log-driver", "none",
                "--read-only",
                "--tmpfs", "/tmp:rw,size=512m",
                "--tmpfs", "/ws:rw,size=" + config.getWorkspaceMb() + "m,mode=1777",
                "--user", "1000:1000",
                "-e", "CI=1",
                "-e", "NPM_CONFIG_CACHE=/tmp/npm-cache",
                "-e", "MAVEN_OPTS=-Dmaven.repo.local=/tmp/m2",
                "-e", "GRADLE_USER_HOME=/tmp/gradle",
                "-e", "GOPATH=/tmp/go",
                "-e", "GOCACHE=/tmp/go-cache",
                "-e", "GOMODCACHE=/tmp/go-mod-cache",
                "-e", "GOBIN=/tmp/go-bin",
                "-e", "CARGO_HOME=/tmp/cargo",
                "-e", "CARGO_TARGET_DIR=/tmp/cargo-target",
                "-e", "RUSTUP_HOME=/home/appuser/.rustup",
                "-e", "DOTNET_CLI_HOME=/tmp/dotnet",
                "-e", "NUGET_PACKAGES=/tmp/nuget"));
        if (extraEnvironment != null) {
            extraEnvironment.forEach((key, value) -> {
                if (isEnvironmentName(key) && value != null) {
                    dockerCommand.add("-e");
                    dockerCommand.add(key + "=" + value);
                }
            });
        }
        dockerCommand.add("-e");
        dockerCommand.add("AI_GIT_BOT_TIMEOUT_SECONDS=" + timeoutSeconds);

        dockerCommand.add("--mount");
        dockerCommand.add("type=bind,src=" + workspaceDir.toAbsolutePath().normalize()
                + ",dst=/source,readonly");
        if (configOverlay != null) {
            dockerCommand.add("--mount");
            dockerCommand.add("type=bind,src=" + configOverlay.toAbsolutePath()
                    + ",dst=/source/.git/config,readonly");
        }
        dockerCommand.add("-w");
        dockerCommand.add("/ws");
        dockerCommand.add(resolveImage());
        dockerCommand.add("-c");
        dockerCommand.add(WORKSPACE_SETUP_COMMAND);
        dockerCommand.add("sandbox-command");
        dockerCommand.addAll(command);
        return dockerCommand;
    }

    /**
     * Creates an empty file that masks credential-bearing Git configuration in
     * the container while retaining read-only Git objects and refs for build tooling.
     */
    Path createGitConfigOverlay(Path workspaceDir) throws IOException {
        Path gitDirectory = workspaceDir.resolve(".git");
        BasicFileAttributes gitAttributes;
        try {
            gitAttributes = Files.readAttributes(gitDirectory, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new IOException("Cannot inspect .git layout in workspace: " + workspaceDir, e);
        }
        Path gitConfig = gitDirectory.resolve("config");
        if (gitAttributes.isSymbolicLink() || !gitAttributes.isDirectory()) {
            throw new IOException("Unexpected .git layout in workspace: " + workspaceDir);
        }
        BasicFileAttributes configAttributes;
        try {
            configAttributes = Files.readAttributes(gitConfig, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw new IOException("Unexpected .git layout in workspace: " + workspaceDir, e);
        } catch (IOException e) {
            throw new IOException("Cannot inspect .git layout in workspace: " + workspaceDir, e);
        }
        if (configAttributes.isSymbolicLink() || !configAttributes.isRegularFile()) {
            throw new IOException("Unexpected .git layout in workspace: " + workspaceDir);
        }
        try (var ignored = Files.newByteChannel(gitConfig, StandardOpenOption.READ)) {
            // Confirm the sandbox can safely mask a readable regular config file.
        } catch (IOException e) {
            throw new IOException("Cannot read .git layout in workspace: " + workspaceDir, e);
        }
        Path parent = workspaceDir.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Workspace has no parent directory: " + workspaceDir);
        }
        return Files.createTempFile(parent, ".ai-bot-git-config-", ".tmp");
    }

    private Result toResult(ProcessSupport.CommandResult result) {
        return new Result(result.finished() && result.exitCode() == 0, result.exitCode(),
                result.output(), !result.finished());
    }

    private void addExtraEnvironment(Map<String, String> environment, Map<String, String> extraEnvironment) {
        if (extraEnvironment == null) {
            return;
        }
        extraEnvironment.forEach((key, value) -> {
            if (isEnvironmentName(key) && value != null) {
                environment.put(key, value);
            }
        });
    }

    private boolean isEnvironmentName(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private void deleteGitConfigOverlay(Path configOverlay) {
        if (configOverlay == null) {
            return;
        }
        try {
            Files.deleteIfExists(configOverlay);
        } catch (IOException e) {
            log.warn("Failed to remove temporary Git config overlay {}: {}", configOverlay, e.getMessage());
        }
    }

    private void killContainer(String containerName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "rm", "-f", containerName);
            processBuilder.redirectErrorStream(true);
            ProcessSupport.scrubEnvironment(processBuilder);
            String dockerHost = resolveDockerHost();
            if (dockerHost != null) {
                processBuilder.environment().put("DOCKER_HOST", dockerHost);
            }
            ProcessSupport.CommandResult result = ProcessSupport.waitFor(processBuilder.start(), 15,
                    TimeUnit.SECONDS, 64 * 1024);
            if (!result.finished()) {
                log.warn("Timed out while removing sandbox container {}", containerName);
            } else if (result.exitCode() != 0) {
                log.debug("Sandbox container {} was already removed: {}", containerName, result.output());
            }
        } catch (Exception e) {
            log.warn("Failed to remove sandbox container {}: {}", containerName, e.getMessage());
        }
    }

    private String resolveImage() {
        if (config.getImage() != null && !config.getImage().isBlank()) {
            return config.getImage();
        }
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

    private boolean probeDocker() {
        return probeDocker(15, TimeUnit.SECONDS);
    }

    private boolean probeDocker(long timeout, TimeUnit timeoutUnit) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "docker", "version", "--format", "{{.Server.Version}}");
            processBuilder.redirectErrorStream(true);
            ProcessSupport.scrubEnvironment(processBuilder);
            String dockerHost = resolveDockerHost();
            if (dockerHost != null) {
                processBuilder.environment().put("DOCKER_HOST", dockerHost);
            }
            ProcessSupport.CommandResult result = ProcessSupport.waitFor(processBuilder.start(), timeout,
                    timeoutUnit, 64 * 1024);
            boolean available = result.finished() && result.exitCode() == 0;
            log.info("Docker sandbox availability probe: {}", available ? "available" : "unavailable");
            return available;
        } catch (Exception e) {
            log.info("Docker sandbox availability probe failed: {}", e.getMessage());
            return false;
        }
    }

    private long remainingTimeoutMillis(long deadlineNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    private static final class DockerUnavailableException extends IOException {
        private DockerUnavailableException(Throwable cause) {
            super(cause.getMessage(), cause);
        }

        private DockerUnavailableException(String message) {
            super(message);
        }
    }
}
