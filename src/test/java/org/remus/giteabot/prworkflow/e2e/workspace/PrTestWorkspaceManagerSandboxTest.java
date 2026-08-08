package org.remus.giteabot.prworkflow.e2e.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.validation.SandboxedCommandExecutor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrTestWorkspaceManagerSandboxTest {

    @Mock
    private SandboxedCommandExecutor sandboxedExecutor;

    @Test
    void allocate_runsNpmInstallThroughSandboxExecutor(@TempDir Path tmp) throws Exception {
        PrTestWorkspaceManager manager = new PrTestWorkspaceManager(tmp.toString(), true, sandboxedExecutor);
        when(sandboxedExecutor.run(any(Path.class), any(), eq(120)))
                .thenReturn(new SandboxedCommandExecutor.Result(true, 0, "", false));

        Path workspace = manager.allocate(1L, E2eTestFramework.PLAYWRIGHT);

        verify(sandboxedExecutor).run(workspace, List.of("npm", "install", "-D",
                "--no-audit", "--no-fund", "--loglevel=error", "--prefer-offline",
                "@playwright/test@1.60.0"), 120);
    }

    @Test
    void allocate_rejectsOfflineSandboxForE2eWorkspaces(@TempDir Path tmp) throws Exception {
        PrTestWorkspaceManager manager = new PrTestWorkspaceManager(tmp.toString(), true, sandboxedExecutor);
        when(sandboxedExecutor.isNetworkIsolated()).thenReturn(true);

        assertThatThrownBy(() -> manager.allocate(1L, E2eTestFramework.PLAYWRIGHT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sandbox network");

        verify(sandboxedExecutor, never()).run(any(Path.class), any(), eq(120));
    }

    @Test
    void allocate_allowsE2eWhenDockerFallsBackToDirectExecution(@TempDir Path tmp) throws IOException {
        AgentConfigProperties config = new AgentConfigProperties();
        config.getSandbox().setEnabled(true);
        config.getSandbox().setFallbackToDirect(true);
        config.getSandbox().setDockerHost("unix:///definitely-missing-docker.sock");
        PrTestWorkspaceManager manager = new PrTestWorkspaceManager(tmp.toString(), false,
                new SandboxedCommandExecutor(config));

        assertThat(manager.allocate(1L, E2eTestFramework.PLAYWRIGHT)).isDirectory();
    }

    @Test
    void allocate_defersDependencyInstallWhenDockerSandboxIsActive(@TempDir Path tmp) throws Exception {
        PrTestWorkspaceManager manager = new PrTestWorkspaceManager(tmp.toString(), true, sandboxedExecutor);
        when(sandboxedExecutor.isSandboxed()).thenReturn(true);

        assertThat(manager.allocate(1L, E2eTestFramework.PLAYWRIGHT)).isDirectory();

        verify(sandboxedExecutor, never()).run(any(Path.class), any(), eq(120));
    }
}
