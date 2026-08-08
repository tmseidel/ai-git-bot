package org.remus.giteabot.prworkflow.e2e.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrTestWorkspaceManagerWorkspacePathsTest {

    @Test
    void resolveInsideWorkspaceRejectsGitMetadataAndIntermediateSymlinks(@TempDir Path tmp) throws IOException {
        PrTestWorkspaceManager manager = PrTestWorkspaceManager.rootedAt(tmp);
        Path workspace = manager.allocate(1L, E2eTestFramework.PLAYWRIGHT);
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("linked"), outside);

        assertThatThrownBy(() -> manager.resolveInsideWorkspace(workspace, ".git/config"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".git internals");
        assertThatThrownBy(() -> manager.resolveInsideWorkspace(workspace, "linked/secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symlinked directory");
    }
}
