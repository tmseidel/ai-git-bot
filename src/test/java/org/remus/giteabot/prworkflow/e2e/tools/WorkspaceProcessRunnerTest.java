package org.remus.giteabot.prworkflow.e2e.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.validation.SandboxedCommandExecutor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceProcessRunnerTest {

    @Mock
    private SandboxedCommandExecutor sandboxedExecutor;

    @InjectMocks
    private WorkspaceProcessRunner runner;

    @Test
    void run_routesToSandboxWhenAvailable(@TempDir Path workspace) throws Exception {
        when(sandboxedExecutor.run(workspace, List.of("npm", "test"), 30_000, TimeUnit.MILLISECONDS, 1024,
                Map.of("BASE_URL", "https://preview.example.test",
                        SandboxedCommandExecutor.EXPORT_ARTIFACTS_ENV, "true")))
                .thenReturn(new SandboxedCommandExecutor.Result(false, 1, "failed", false));

        WorkspaceProcessRunner.ProcessResult result = runner.run(workspace, List.of("npm", "test"),
                Map.of("BASE_URL", "https://preview.example.test"), 30_000, 1024);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.combinedOutput()).isEqualTo("failed");
        assertThat(result.timedOut()).isFalse();
        verify(sandboxedExecutor).run(workspace, List.of("npm", "test"), 30_000, TimeUnit.MILLISECONDS, 1024,
                Map.of("BASE_URL", "https://preview.example.test",
                        SandboxedCommandExecutor.EXPORT_ARTIFACTS_ENV, "true"));
    }

    @Test
    void run_preservesMillisecondTimeout(@TempDir Path workspace) throws Exception {
        when(sandboxedExecutor.run(workspace, List.of("npm", "test"), 1_500, TimeUnit.MILLISECONDS, 1024,
                Map.of(SandboxedCommandExecutor.EXPORT_ARTIFACTS_ENV, "true")))
                .thenReturn(new SandboxedCommandExecutor.Result(true, 0, "", false));

        runner.run(workspace, List.of("npm", "test"), 1_500, 1024);

        verify(sandboxedExecutor).run(workspace, List.of("npm", "test"), 1_500, TimeUnit.MILLISECONDS, 1024,
                Map.of(SandboxedCommandExecutor.EXPORT_ARTIFACTS_ENV, "true"));
    }

    @Test
    void run_restoresBoundedSandboxArtifacts(@TempDir Path workspace) throws Exception {
        String artifact = Base64.getEncoder().encodeToString("screenshot".getBytes(StandardCharsets.UTF_8));
        String output = "test output\n" + SandboxedCommandExecutor.ARTIFACTS_MARKER + "\n"
                + "test-results/screenshot.txt\t" + artifact + "\n";
        when(sandboxedExecutor.run(workspace, List.of("npm", "test"), 30_000, TimeUnit.MILLISECONDS, 1024,
                Map.of(SandboxedCommandExecutor.EXPORT_ARTIFACTS_ENV, "true")))
                .thenReturn(new SandboxedCommandExecutor.Result(true, 0, output, false));

        WorkspaceProcessRunner.ProcessResult result = runner.run(workspace, List.of("npm", "test"),
                30_000, 1024);

        assertThat(result.combinedOutput()).isEqualTo("test output");
        assertThat(Files.readString(workspace.resolve("test-results/screenshot.txt"))).isEqualTo("screenshot");
    }

    @Test
    void run_doesNotRestoreArtifactsThroughWorkspaceSymlinks(@TempDir Path workspace) throws Exception {
        Path outside = workspace.getParent().resolve("outside-artifacts");
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("test-results"), outside);
        String artifact = Base64.getEncoder().encodeToString("screenshot".getBytes(StandardCharsets.UTF_8));
        String output = SandboxedCommandExecutor.ARTIFACTS_MARKER + "\n"
                + "test-results/screenshot.txt\t" + artifact + "\n";
        when(sandboxedExecutor.run(workspace, List.of("npm", "test"), 30_000, TimeUnit.MILLISECONDS, 1024,
                Map.of(SandboxedCommandExecutor.EXPORT_ARTIFACTS_ENV, "true")))
                .thenReturn(new SandboxedCommandExecutor.Result(true, 0, output, false));

        try {
            runner.run(workspace, List.of("npm", "test"), 30_000, 1024);

            assertThat(outside.resolve("screenshot.txt")).doesNotExist();
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
