package org.remus.giteabot.prworkflow.readmesync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadmeSyncToolExecutorTest {

    private final ReadmeSyncToolExecutor executor = new ReadmeSyncToolExecutor();

    private ReadmeSyncToolContext ctx(Path workspace) {
        return new ReadmeSyncToolContext(workspace, List.of("README.md", "README.*.md", "doc/**/*.md"));
    }

    @Test
    void docWriteCreatesInScopeMarkdown(@TempDir Path ws) {
        ReadmeSyncToolContext ctx = ctx(ws);
        String result = executor.execute("doc-write",
                Map.of("path", "README.md", "content", "# Title\n"), ctx);

        assertTrue(result.startsWith("OK"), result);
        assertTrue(Files.exists(ws.resolve("README.md")));
        assertTrue(ctx.created().contains("README.md"));
        assertTrue(ctx.updated().isEmpty());
    }

    @Test
    void docWriteOnExistingRecordsUpdated(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("README.md"), "old");
        ReadmeSyncToolContext ctx = ctx(ws);

        String result = executor.execute("doc-write",
                Map.of("path", "README.md", "content", "new"), ctx);

        assertTrue(result.startsWith("OK"), result);
        assertEquals("new", Files.readString(ws.resolve("README.md")));
        assertTrue(ctx.updated().contains("README.md"));
        assertTrue(ctx.created().isEmpty());
    }

    @Test
    void docWriteRejectsNonMarkdown(@TempDir Path ws) {
        ReadmeSyncToolContext ctx = ctx(ws);
        String result = executor.execute("doc-write",
                Map.of("path", "doc/setup.txt", "content", "x"), ctx);

        assertTrue(result.startsWith("ERROR"), result);
        assertFalse(Files.exists(ws.resolve("doc/setup.txt")));
        assertFalse(ctx.touchedAnything());
    }

    @Test
    void docWriteRejectsOutOfScopePath(@TempDir Path ws) {
        ReadmeSyncToolContext ctx = ctx(ws);
        String result = executor.execute("doc-write",
                Map.of("path", "src/main/java/App.java", "content", "x"), ctx);

        assertTrue(result.startsWith("ERROR"), result);
        assertFalse(ctx.touchedAnything());
    }

    @Test
    void docWriteRejectsPathTraversal(@TempDir Path ws) {
        ReadmeSyncToolContext ctx = ctx(ws);
        String result = executor.execute("doc-write",
                Map.of("path", "../README.md", "content", "x"), ctx);

        assertTrue(result.startsWith("ERROR"), result);
    }

    @Test
    void docDeleteRemovesInScopeMarkdown(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("README.md"), "content");
        ReadmeSyncToolContext ctx = ctx(ws);

        String result = executor.execute("doc-delete", Map.of("path", "README.md"), ctx);

        assertTrue(result.startsWith("OK"), result);
        assertFalse(Files.exists(ws.resolve("README.md")));
        assertTrue(ctx.deleted().contains("README.md"));
    }

    @Test
    void docDeleteRejectsMissingFile(@TempDir Path ws) {
        ReadmeSyncToolContext ctx = ctx(ws);
        String result = executor.execute("doc-delete", Map.of("path", "README.md"), ctx);
        assertTrue(result.startsWith("ERROR"), result);
    }

    @Test
    void docDeleteRejectsOutOfScope(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("CONTRIBUTING.md"), "content");
        ReadmeSyncToolContext ctx = ctx(ws);
        String result = executor.execute("doc-delete", Map.of("path", "CONTRIBUTING.md"), ctx);
        assertTrue(result.startsWith("ERROR"), result);
        assertTrue(Files.exists(ws.resolve("CONTRIBUTING.md")));
    }

    @Test
    void unknownToolReturnsError(@TempDir Path ws) {
        String result = executor.execute("doc-frobnicate", Map.of("path", "README.md"), ctx(ws));
        assertTrue(result.startsWith("ERROR"), result);
    }
}
