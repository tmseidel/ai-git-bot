package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxedCommandExecutorTest {

    @Test
    void buildDockerCommand_appliesIsolationAndMasksGitConfig(@TempDir Path tempDir) throws IOException {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getSandbox().setImage("registry.example/sandbox:test");
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);
        Path workspace = tempDir.resolve("workspace");
        Path configOverlay = tempDir.resolve("empty-git-config");
        Files.createDirectories(workspace);
        Files.createFile(configOverlay);

        List<String> command = executor.buildDockerCommand(workspace, List.of("mvn", "test"),
                Map.of("BASE_URL", "https://preview.example.test"), "sandbox-test", configOverlay,
                30, TimeUnit.SECONDS);

        assertThat(command).containsSubsequence(
                "docker", "run", "--rm", "--name", "sandbox-test", "--label", "ai-git-bot.sandbox=true",
                "--entrypoint=/bin/sh",
                "--network", "none", "--memory", "2048m", "--cpus", "2.0",
                "--pids-limit", "256", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--log-driver", "none", "--read-only", "--tmpfs", "/tmp:rw,size=512m",
                "--tmpfs", "/ws:rw,size=1024m,mode=1777", "--user", "1000:1000",
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
                "-e", "NUGET_PACKAGES=/tmp/nuget");
        assertThat(command).contains("AI_GIT_BOT_TIMEOUT_SECONDS=30");
        assertThat(command).contains("type=bind,src=" + workspace.toAbsolutePath() + ",dst=/source,readonly");
        assertThat(command).contains("type=bind,src=" + configOverlay.toAbsolutePath()
                + ",dst=/source/.git/config,readonly");
        assertThat(command).contains("registry.example/sandbox:test", "-c", "sandbox-command");
        assertThat(command).anyMatch(argument -> argument.contains("cp -R /source/. /ws/"));
        assertThat(command).anyMatch(argument -> argument.contains("timeout --signal=KILL"));
        assertThat(command).endsWith("sandbox-command", "mvn", "test");
    }

    @Test
    void createGitConfigOverlay_masksRegularGitConfig(@TempDir Path tempDir) throws IOException {
        AgentConfigProperties config = new AgentConfigProperties();
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);
        Path workspace = tempDir.resolve("workspace");
        Path gitDirectory = workspace.resolve(".git");
        Files.createDirectories(gitDirectory);
        Files.writeString(gitDirectory.resolve("config"), "[remote \"origin\"]\nurl = https://token@example.test\n");

        Path overlay = executor.createGitConfigOverlay(workspace);

        assertThat(overlay).isRegularFile();
        assertThat(overlay.getParent()).isEqualTo(tempDir);
        assertThat(Files.readString(overlay)).isEmpty();

        Files.deleteIfExists(overlay);
    }

    @Test
    void createGitConfigOverlay_skipsNonGitWorkspaces(@TempDir Path tempDir) throws IOException {
        AgentConfigProperties config = new AgentConfigProperties();
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        assertThat(executor.createGitConfigOverlay(workspace)).isNull();
    }

    @Test
    void run_executesDirectlyWhenSandboxingIsDisabled(@TempDir Path tempDir) throws Exception {
        AgentConfigProperties config = new AgentConfigProperties();
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);

        SandboxedCommandExecutor.Result result = executor.run(tempDir,
                List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                        EchoProcess.class.getName()), 10);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("sandbox-executor-ok");
    }

    @Test
    void run_failsClosedWhenEnabledSandboxCannotReachDocker(@TempDir Path tempDir) {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getSandbox().setEnabled(true);
        config.getSandbox().setDockerHost("unix:///definitely-missing-docker.sock");
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);

        assertThatThrownBy(() -> executor.run(tempDir, List.of("echo", "should-not-run"), 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Docker sandbox is enabled but unavailable");
    }

    @Test
    void run_usesDirectFallbackOnlyWhenExplicitlyEnabled(@TempDir Path tempDir) throws Exception {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getSandbox().setEnabled(true);
        config.getSandbox().setFallbackToDirect(true);
        config.getSandbox().setDockerHost("unix:///definitely-missing-docker.sock");
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);

        SandboxedCommandExecutor.Result result = executor.run(tempDir,
                List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                        EchoProcess.class.getName()), 10);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("sandbox-executor-ok");
    }

    @Test
    void isNetworkIsolated_allowsDirectFallbackWhenDockerIsUnavailable() {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getSandbox().setEnabled(true);
        config.getSandbox().setFallbackToDirect(true);
        config.getSandbox().setDockerHost("unix:///definitely-missing-docker.sock");
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);

        assertThat(executor.isNetworkIsolated()).isFalse();
    }

    @Test
    void createGitConfigOverlay_rejectsUnexpectedGitLayout(@TempDir Path tempDir) throws IOException {
        AgentConfigProperties config = new AgentConfigProperties();
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.createSymbolicLink(workspace.resolve(".git"), tempDir.resolve("outside"));

        assertThatThrownBy(() -> executor.createGitConfigOverlay(workspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unexpected .git layout");
    }

    @Test
    void createGitConfigOverlay_rejectsMissingGitConfig(@TempDir Path tempDir) throws IOException {
        AgentConfigProperties config = new AgentConfigProperties();
        SandboxedCommandExecutor executor = new SandboxedCommandExecutor(config);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".git"));

        assertThatThrownBy(() -> executor.createGitConfigOverlay(workspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unexpected .git layout");
    }

    public static final class EchoProcess {
        public static void main(String[] args) {
            System.out.print("sandbox-executor-ok");
        }
    }
}
