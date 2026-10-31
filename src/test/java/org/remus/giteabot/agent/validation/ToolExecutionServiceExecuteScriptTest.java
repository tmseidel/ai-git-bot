package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code execute} validation tool: a script committed inside the
 * repository is the validation step, addressed by its repository-relative path,
 * with the exit code as the success contract and the workspace path guard plus
 * the committed-version check as its boundaries.
 * <p>
 * POSIX-only: the cases rely on shebangs, {@code /bin/sh} and the executable bit.
 */
@DisabledOnOs(OS.WINDOWS)
class ToolExecutionServiceExecuteScriptTest {

    @TempDir
    Path workspace;

    @TempDir
    Path plainDirectory;

    private ToolExecutionService service;

    @BeforeEach
    void setUp() throws IOException {
        service = newService(new AgentConfigProperties());
        git("init", "-q");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
    }

    private static ToolExecutionService newService(AgentConfigProperties config) {
        return new ToolExecutionService(config, new ToolCatalog(config));
    }

    private void git(String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder pb = new ProcessBuilder(command).directory(workspace.toFile());
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor())
                    .as("git %s failed: %s", String.join(" ", arguments), output)
                    .isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /** Writes a script and commits it, so it is the version the repository validates with. */
    private void writeCommittedScript(String relativePath, String content, boolean executable) throws IOException {
        writeScript(relativePath, content, executable);
        git("add", "--", relativePath);
        git("commit", "-q", "-m", "add " + relativePath);
    }

    /** Writes a script the way the agent would: file on disk, nothing committed. */
    private void writeScript(String relativePath, String content, boolean executable) throws IOException {
        Path script = workspace.resolve(relativePath);
        Files.createDirectories(script.getParent());
        Files.writeString(script, content);
        assertThat(script.toFile().setExecutable(executable))
                .as("could not set the executable bit on the test script")
                .isTrue();
    }

    @Test
    void execute_scriptExitsZero_reportsSuccessWithCapturedOutput() throws IOException {
        // The marker file only resolves when the script's working directory is the workspace.
        Files.writeString(workspace.resolve("marker.txt"), "present");
        writeCommittedScript("scripts/validate.sh",
                "#!/bin/sh\ntest -f marker.txt && echo 'cwd-ok; validated docs'\nexit 0\n", true);

        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("cwd-ok; validated docs");
    }

    @Test
    void execute_scriptExitsNonZero_reportsFailureWithCapturedStderr() throws IOException {
        writeCommittedScript("scripts/validate.sh",
                "#!/bin/sh\necho 'lint error: README.md:12' 1>&2\nexit 3\n", true);

        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).contains("lint error: README.md:12");
    }

    @Test
    void execute_extraArgumentsAreForwardedToTheScript() throws IOException {
        writeCommittedScript("scripts/validate.sh", "#!/bin/sh\necho \"args:$@\"\n", true);

        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute",
                List.of("scripts/validate.sh", "--strict", "docs/"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("args:--strict docs/");
    }

    @Test
    void execute_blankArgumentsAreForwardedVerbatim() throws IOException {
        writeCommittedScript("scripts/validate.sh", "#!/bin/sh\necho \"argc=$#\"\n", true);

        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute",
                List.of("scripts/validate.sh", "", "--strict"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("argc=2");
    }

    @Test
    void execute_missingScript_reportsClearError() {
        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("scripts/does-not-exist.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("not found inside the repository")
                .contains("scripts/does-not-exist.sh");
    }

    @Test
    void execute_scriptWithoutExecutableBit_reportsClearError() throws IOException {
        writeCommittedScript("scripts/validate.sh", "#!/bin/sh\nexit 0\n", false);

        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not executable").contains("chmod +x");
    }

    @Test
    void execute_scriptModifiedAfterCommit_isRefused() throws IOException {
        writeCommittedScript("scripts/validate.sh", "#!/bin/sh\necho 'lint failed' 1>&2\nexit 1\n", true);

        // The agent rewrites the script it is being validated by — the bypass this guards against.
        writeScript("scripts/validate.sh", "#!/bin/sh\nexit 0\n", true);

        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("must be the committed version")
                .contains("scripts/validate.sh");
    }

    @Test
    void execute_scriptNeverCommitted_isRefused() throws IOException {
        writeScript("scripts/validate.sh", "#!/bin/sh\nexit 0\n", true);

        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("must be the committed version");
    }

    @Test
    void execute_workspaceWithoutGitCheckout_isRefused() throws IOException {
        Path script = plainDirectory.resolve("scripts/validate.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();

        ToolResult result = service.executeTool(Workspace.unmanaged(plainDirectory), "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("could not be verified against the repository");
    }

    @Test
    void execute_pathTraversalOutOfTheRepository_isRejected() {
        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("../../etc/passwd"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("escapes");
    }

    @Test
    void execute_absolutePath_isRejected() {
        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("/etc/passwd"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("must be relative");
    }

    @Test
    void execute_gitInternalsPath_isRejected() {
        ToolResult result = service.executeTool(Workspace.unmanaged(workspace), "execute", List.of(".git/hooks/pre-commit"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains(".git internals");
    }

    @Test
    void execute_missingPathArgument_reportsClearError() {
        assertThat(service.executeTool(Workspace.unmanaged(workspace), "execute", List.of()).error())
                .contains("requires the repository-relative path");
        assertThat(service.executeTool(Workspace.unmanaged(workspace), "execute", List.of("   ")).error())
                .contains("requires the repository-relative path");
    }

    @Test
    void execute_isRejectedWhenTheToolIsNotConfigured() {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getValidation().setAvailableTools(List.of("mvn"));

        ToolResult result = newService(config)
                .executeTool(Workspace.unmanaged(workspace), "execute", List.of("scripts/validate.sh"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("is not available");
    }
}
