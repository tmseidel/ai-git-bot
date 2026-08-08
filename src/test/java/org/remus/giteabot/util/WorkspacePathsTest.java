package org.remus.giteabot.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspacePathsTest {

    @Test
    void resolveInsideWorkspace_allowsNewFilesWithinWorkspace(@TempDir Path root) throws IOException {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace);

        Path result = WorkspacePaths.resolveInsideWorkspace(workspace, "src/Main.java");

        assertThat(result).isEqualTo(workspace.resolve("src/Main.java"));
    }

    @Test
    void resolveInsideWorkspace_rejectsTraversalAndGitMetadata(@TempDir Path root) throws IOException {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve(".git"));

        assertThatThrownBy(() -> WorkspacePaths.resolveInsideWorkspace(workspace, "../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the workspace");
        assertThatThrownBy(() -> WorkspacePaths.resolveInsideWorkspace(workspace, ".git/config"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".git internals");
        assertThatThrownBy(() -> WorkspacePaths.resolveInsideWorkspace(workspace, ".GIT/config"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".git internals");
    }

    @Test
    void resolveInsideWorkspace_rejectsAbsoluteAndNormalizedTraversalPaths(@TempDir Path root) throws IOException {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace);

        assertThatThrownBy(() -> WorkspacePaths.resolveInsideWorkspace(
                workspace, workspace.resolve("inside.txt").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
        assertThatThrownBy(() -> WorkspacePaths.resolveInsideWorkspace(workspace, "src/../inside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void resolveInsideWorkspace_rejectsIntermediateSymlink(@TempDir Path root) throws IOException {
        Path workspace = root.resolve("workspace");
        Path outside = root.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("linked"), outside);

        assertThatThrownBy(() -> WorkspacePaths.resolveInsideWorkspace(workspace, "linked/secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symlinked directory");
    }
}
