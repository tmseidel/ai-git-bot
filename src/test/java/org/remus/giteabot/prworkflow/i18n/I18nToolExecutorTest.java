package org.remus.giteabot.prworkflow.i18n;

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

class I18nToolExecutorTest {

    private final I18nToolExecutor executor = new I18nToolExecutor();

    private I18nCoverageToolContext ctx(Path workspace) {
        return new I18nCoverageToolContext(workspace, List.of("i18n/*.properties", "i18n/*.json"));
    }

    @Test
    void writeCreatesInScopeProperties(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("i18n"));
        I18nCoverageToolContext ctx = ctx(ws);
        String result = executor.execute("i18n-write",
                Map.of("path", "i18n/messages_de.properties", "content", "greeting=Hallo\n"), ctx);

        assertTrue(result.startsWith("OK"), result);
        assertTrue(Files.exists(ws.resolve("i18n/messages_de.properties")));
        assertTrue(ctx.created().contains("i18n/messages_de.properties"));
    }

    @Test
    void writeOnExistingRecordsUpdated(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("i18n"));
        Files.writeString(ws.resolve("i18n/fr.json"), "{}");
        I18nCoverageToolContext ctx = ctx(ws);

        String result = executor.execute("i18n-write",
                Map.of("path", "i18n/fr.json", "content", "{\"a\":\"A\"}"), ctx);

        assertTrue(result.startsWith("OK"), result);
        assertEquals("{\"a\":\"A\"}", Files.readString(ws.resolve("i18n/fr.json")));
        assertTrue(ctx.updated().contains("i18n/fr.json"));
        assertTrue(ctx.created().isEmpty());
    }

    @Test
    void writeRejectsUnsupportedExtension(@TempDir Path ws) {
        I18nCoverageToolContext ctx = ctx(ws);
        String result = executor.execute("i18n-write",
                Map.of("path", "i18n/notes.txt", "content", "x"), ctx);
        assertTrue(result.startsWith("ERROR"), result);
        assertFalse(ctx.touchedAnything());
    }

    @Test
    void writeRejectsOutOfScopePath(@TempDir Path ws) {
        I18nCoverageToolContext ctx = ctx(ws);
        String result = executor.execute("i18n-write",
                Map.of("path", "src/main/java/App.java", "content", "x"), ctx);
        assertTrue(result.startsWith("ERROR"), result);
        assertFalse(ctx.touchedAnything());
    }

    @Test
    void writeRejectsPathTraversal(@TempDir Path ws) {
        I18nCoverageToolContext ctx = ctx(ws);
        String result = executor.execute("i18n-write",
                Map.of("path", "../i18n/en.json", "content", "x"), ctx);
        assertTrue(result.startsWith("ERROR"), result);
    }

    @Test
    void deleteRemovesInScopeFile(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("i18n"));
        Files.writeString(ws.resolve("i18n/obsolete.json"), "{}");
        I18nCoverageToolContext ctx = ctx(ws);

        String result = executor.execute("i18n-delete", Map.of("path", "i18n/obsolete.json"), ctx);

        assertTrue(result.startsWith("OK"), result);
        assertFalse(Files.exists(ws.resolve("i18n/obsolete.json")));
        assertTrue(ctx.deleted().contains("i18n/obsolete.json"));
    }

    @Test
    void deleteRejectsMissingFile(@TempDir Path ws) {
        I18nCoverageToolContext ctx = ctx(ws);
        String result = executor.execute("i18n-delete", Map.of("path", "i18n/en.json"), ctx);
        assertTrue(result.startsWith("ERROR"), result);
    }

    @Test
    void unknownToolReturnsError(@TempDir Path ws) {
        String result = executor.execute("i18n-frobnicate", Map.of("path", "i18n/en.json"), ctx(ws));
        assertTrue(result.startsWith("ERROR"), result);
    }
}
