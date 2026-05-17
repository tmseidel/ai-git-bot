package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

    private ToolExecutionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        AgentConfigProperties config = new AgentConfigProperties();
        service = new ToolExecutionService(config,
                new org.remus.giteabot.agent.tools.ToolCatalog(config));
    }

    @Test
    void executeContextTool_branchSwitcher_invalidBranchName_returnsFailure() {
        ToolResult result = service.executeContextTool(tempDir, "branch-switcher", List.of("../main"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid branch name");
    }

    @Test
    void executeContextTool_branchSwitcher_missingArgs_returnsFailure() {
        ToolResult result = service.executeContextTool(tempDir, "branch-switcher", List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("requires a branch name argument");
    }

    @Test
    void executeContextTool_branchSwitcher_blankBranch_returnsFailure() {
        ToolResult result = service.executeContextTool(tempDir, "branch-switcher", List.of("   "));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("requires a non-empty branch name");
    }

    @Test
    void executeContextTool_cat_readsRequestedRangeWithLineNumbers() throws IOException {
        Path file = tempDir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                line 1
                line 2
                line 3
                """);

        ToolResult result = service.executeContextTool(tempDir, "cat", List.of("src/Main.java", "2", "3"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("2 | line 2");
        assertThat(result.output()).contains("3 | line 3");
        assertThat(result.output()).doesNotContain("1 | line 1");
    }

    @Test
    void executeContextTool_rg_searchesWorkspace() throws IOException {
        Path file = tempDir.resolve("src/Config.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                class Config {
                    private ConfigService service;
                }
                """);

        ToolResult result = service.executeContextTool(tempDir, "rg", List.of("ConfigService", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/Config.java:2:     private ConfigService service;");
    }

    @Test
    void executeContextTool_rg_supportsEscapedAlternationFromAiGeneratedPatterns() throws IOException {
        Path file = tempDir.resolve("src/GiteaWebhookHandler.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                class GiteaWebhookHandler {
                    void handleBotCommand() {}
                    void handleInlineComment() {}
                }
                """);

        ToolResult result = service.executeContextTool(tempDir, "rg",
                List.of("handleBotCommand\\|handleInlineComment", "src", "-n"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/GiteaWebhookHandler.java:2:     void handleBotCommand() {}");
        assertThat(result.output()).contains("src/GiteaWebhookHandler.java:3:     void handleInlineComment() {}");
    }

    @Test
    void executeContextTool_rg_supportsCombinedCaseInsensitiveFlags() throws IOException {
        Path file = tempDir.resolve("src/ReviewNotes.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                class ReviewNotes {
                    String value = "Clarifying question";
                }
                """);

        ToolResult result = service.executeContextTool(tempDir, "rg",
                List.of("clarifying", "src", "-in"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/ReviewNotes.java:2:     String value = \"Clarifying question\";");
    }

    @Test
    void executeContextTool_rg_supportsIncludeGlobAndListFilesMode() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/BotWebhookService.java"), "class BotWebhookService { String author = \"tom\"; }");
        Files.writeString(tempDir.resolve("src/notes.txt"), "author mention in text file");

        ToolResult result = service.executeContextTool(tempDir, "rg",
                List.of("author", "src", "--include=*.java", "-l"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("src/BotWebhookService.java");
    }

    @Test
    void executeContextTool_find_matchesGlobPattern() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/app.yml"), "name: app");
        Files.writeString(tempDir.resolve("src/Config.java"), "class Config {}");

        ToolResult result = service.executeContextTool(tempDir, "find", List.of("*.yml", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/app.yml");
    }

    @Test
    void executeContextTool_find_supportsShellStyleNameSyntax() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/org/example"));
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.java"), "class BotWebhookService {}");
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.txt"), "text");

        ToolResult result = service.executeContextTool(tempDir, "find",
                List.of("src/main/java", "-name", "BotWebhookService.java"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("src/main/java/org/example/BotWebhookService.java");
    }

    @Test
    void executeContextTool_find_supportsIncludeGlobAlongsideNameSyntax() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/org/example"));
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.java"), "class BotWebhookService {}");
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.kt"), "class BotWebhookService");

        ToolResult result = service.executeContextTool(tempDir, "find",
                List.of("src/main/java/org/example", "--include=*.java", "-name", "BotWebhookService.*"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("src/main/java/org/example/BotWebhookService.java");
    }

    // ---- File tool tests ----

    @Test
    void executeFileTool_writeFile_createsFileWithContent() throws IOException {
        ToolResult result = service.executeFileTool(tempDir, "write-file",
                List.of("src/main/Foo.java", "public class Foo {}"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/main/Foo.java");
        assertThat(Files.readString(tempDir.resolve("src/main/Foo.java"))).isEqualTo("public class Foo {}");
    }

    @Test
    void executeFileTool_writeFile_overwritesExistingFile() throws IOException {
        Path target = tempDir.resolve("Existing.java");
        Files.writeString(target, "old content");

        ToolResult result = service.executeFileTool(tempDir, "write-file",
                List.of("Existing.java", "new content"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("new content");
    }

    @Test
    void executeFileTool_writeFile_sameContent_returnsFailure() throws IOException {
        Path target = tempDir.resolve("Existing.java");
        Files.writeString(target, "same content");

        ToolResult result = service.executeFileTool(tempDir, "write-file",
                List.of("Existing.java", "same content"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("would not change file");
    }

    @Test
    void executeFileTool_patchFile_replacesExactText() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service {\n    private int x = 1;\n}\n");

        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java", "private int x = 1;", "private int x = 42;"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).contains("private int x = 42;");
    }

    @Test
    void executeFileTool_patchFile_searchTextNotFound_returnsFailure() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service {}");

        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java", "does not exist", "replacement"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("search text not found");
    }

    @Test
    void executeFileTool_patchFile_identicalReplacement_returnsFailure() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service { int x = 1; }");

        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java", "int x = 1;", "int x = 1;"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("produced no changes");
    }

    @Test
    void executeFileTool_patchFile_crlfFile_lfSearch_appliesWithLineEndingTier() throws IOException {
        Path target = tempDir.resolve("Service.java");
        // File on disk uses CRLF (e.g. checked out on Windows or a .gitattributes rule).
        Files.writeString(target, "class Service {\r\n    private int x = 1;\r\n}\r\n");

        // LLM submits the search/replacement with LF (typical model output).
        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java", "private int x = 1;\n", "private int x = 42;\n"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("normalized line endings");
        // CRLF is preserved on write.
        assertThat(Files.readString(target)).isEqualTo(
                "class Service {\r\n    private int x = 42;\r\n}\r\n");
    }

    @Test
    void executeFileTool_patchFile_trailingWhitespaceMismatch_appliesWithToleranceTier() throws IOException {
        Path target = tempDir.resolve("Service.java");
        // File has trailing whitespace on the indented line that the LLM omitted.
        Files.writeString(target, "class Service {\n    private int x = 1;   \n}\n");

        // Search spans two lines so trailing whitespace on the first line breaks exact match.
        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java",
                        "    private int x = 1;\n}",
                        "    private int x = 42;\n}"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("whitespace-tolerant");
        assertThat(Files.readString(target)).isEqualTo("class Service {\n    private int x = 42;\n}\n");
    }

    @Test
    void executeFileTool_patchFile_collapsedInteriorWhitespace_appliesWithToleranceTier() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class   Service { int    x = 1; }\n");

        // LLM normalized interior whitespace to a single space.
        ToolResult result = service.executeFileTool(tempDir, "patch-file",
                List.of("Service.java", "class Service { int x = 1; }", "class Service { int x = 42; }"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("whitespace-tolerant");
        // Replacement line is written verbatim (LLM-provided form).
        assertThat(Files.readString(target)).isEqualTo("class Service { int x = 42; }\n");
    }

    @Test
    void executeFileTool_mkdir_createsDirectory() {
        ToolResult result = service.executeFileTool(tempDir, "mkdir",
                List.of("new/nested/dir"));

        assertThat(result.success()).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("new/nested/dir"))).isTrue();
    }

    @Test
    void executeFileTool_mkdir_existingDirectory_returnsFailure() throws IOException {
        Files.createDirectories(tempDir.resolve("existing"));

        ToolResult result = service.executeFileTool(tempDir, "mkdir", List.of("existing"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("already exists");
    }

    @Test
    void executeFileTool_deleteFile_removesFile() throws IOException {
        Path target = tempDir.resolve("ToDelete.java");
        Files.writeString(target, "delete me");

        ToolResult result = service.executeFileTool(tempDir, "delete-file",
                List.of("ToDelete.java"));

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void executeFileTool_deleteFile_nonexistent_returnsFailure() {
        ToolResult result = service.executeFileTool(tempDir, "delete-file",
                List.of("DoesNotExist.java"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("did not exist");
    }

    @Test
    void executeFileTool_pathTraversal_returnsError() {
        ToolResult result = service.executeFileTool(tempDir, "write-file",
                List.of("../../etc/passwd", "evil content"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("escapes");
    }
}
