package org.remus.giteabot.prworkflow.e2e.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrTestWorkspaceManagerTest {

    @Test
    void allocatesPlaywrightScaffolding(@TempDir Path tmp) throws IOException {
        PrTestWorkspaceManager manager = PrTestWorkspaceManager.rootedAt(tmp);

        Path ws = manager.allocate(42L, E2eTestFramework.PLAYWRIGHT);

        assertThat(ws).exists().isDirectory();
        assertThat(ws.resolve("package.json")).exists();
        assertThat(ws.resolve("playwright.config.ts")).exists();
        assertThat(ws.resolve("tests")).isDirectory();
        assertThat(ws.resolve("README.md")).exists();
        assertThat(ws.startsWith(tmp)).isTrue();
    }

    @Test
    void isIdempotentWhenAllocatedTwice(@TempDir Path tmp) throws IOException {
        PrTestWorkspaceManager manager = PrTestWorkspaceManager.rootedAt(tmp);
        Path first = manager.allocate(7L, E2eTestFramework.PLAYWRIGHT);

        // Pretend the agent added a file.
        Path agentFile = first.resolve("tests/agent.spec.ts");
        Files.writeString(agentFile, "// generated\n");

        Path second = manager.allocate(7L, E2eTestFramework.PLAYWRIGHT);
        assertThat(second).isEqualTo(first);
        // Existing user content is preserved.
        assertThat(agentFile).exists();
    }

    @Test
    void resolveInsideWorkspaceRejectsTraversal(@TempDir Path tmp) throws IOException {
        PrTestWorkspaceManager manager = PrTestWorkspaceManager.rootedAt(tmp);
        Path ws = manager.allocate(1L, E2eTestFramework.PLAYWRIGHT);

        assertThatThrownBy(() -> manager.resolveInsideWorkspace(ws, "../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the workspace");

        assertThatThrownBy(() -> manager.resolveInsideWorkspace(ws, "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> manager.resolveInsideWorkspace(ws, ""))
                .isInstanceOf(IllegalArgumentException.class);

        Path good = manager.resolveInsideWorkspace(ws, "tests/login.spec.ts");
        assertThat(good.startsWith(ws)).isTrue();
    }

    @Test
    void cleanupRemovesEverythingUnderRunDir(@TempDir Path tmp) throws IOException {
        PrTestWorkspaceManager manager = PrTestWorkspaceManager.rootedAt(tmp);
        Path ws = manager.allocate(99L, E2eTestFramework.PLAYWRIGHT);
        Files.writeString(ws.resolve("tests/x.spec.ts"), "x");

        manager.cleanup(99L);

        assertThat(ws).doesNotExist();
        // Root itself is not deleted.
        assertThat(tmp).exists();
    }

    @Test
    void cleanupOfMissingRunIsSilent(@TempDir Path tmp) {
        PrTestWorkspaceManager manager = PrTestWorkspaceManager.rootedAt(tmp);
        manager.cleanup(123_456L); // does not exist — must not throw
    }

    @Test
    void scaffoldsAllSupportedFrameworks(@TempDir Path tmp) throws IOException {
        PrTestWorkspaceManager manager = PrTestWorkspaceManager.rootedAt(tmp);

        Path cypress = manager.allocate(2L, E2eTestFramework.CYPRESS);
        assertThat(cypress.resolve("package.json")).exists();
        assertThat(cypress.resolve("cypress/e2e")).isDirectory();

        Path pytest = manager.allocate(3L, E2eTestFramework.PYTEST);
        assertThat(pytest.resolve("pyproject.toml")).exists();
        assertThat(pytest.resolve("tests")).isDirectory();

        Path k6 = manager.allocate(4L, E2eTestFramework.K6);
        assertThat(k6.resolve("scenarios")).isDirectory();
    }
}


