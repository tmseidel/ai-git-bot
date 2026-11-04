package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "branch-switcher", List.of("../main"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid branch name");
    }

    @Test
    void executeContextTool_branchSwitcher_missingArgs_returnsFailure() {
        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "branch-switcher", List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("requires a branch name argument");
    }

    @Test
    void executeContextTool_branchSwitcher_blankBranch_returnsFailure() {
        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "branch-switcher", List.of("   "));

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

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "cat", List.of("src/Main.java", "2", "3"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("2 | line 2");
        assertThat(result.output()).contains("3 | line 3");
        assertThat(result.output()).doesNotContain("1 | line 1");
    }

    @Test
    void executeContextTool_cat_treatsABlankSlotAsAnUnsuppliedLineArgument() throws IOException {
        Path file = tempDir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                line 1
                line 2
                line 3
                """);

        // {"path": …, "endLine": 3} arrives with the startLine slot blank rather than shifted into it.
        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "cat", List.of("src/Main.java", "", "3"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("1 | line 1").contains("3 | line 3");
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

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("ConfigService", "src"));

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

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
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

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("clarifying", "src", "-in"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/ReviewNotes.java:2:     String value = \"Clarifying question\";");
    }

    @Test
    void executeContextTool_rg_supportsIncludeGlobAndListFilesMode() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/BotWebhookService.java"), "class BotWebhookService { String author = \"tom\"; }");
        Files.writeString(tempDir.resolve("src/notes.txt"), "author mention in text file");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("author", "src", "--include=*.java", "-l"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("src/BotWebhookService.java");
    }

    @Test
    void executeContextTool_rg_acceptsFlagsBeforeThePattern() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/OrderExporter.java"), "class OrderExporter {}\n");
        Files.writeString(tempDir.resolve("src/Discount.java"), "class Discount {}\n");

        ToolResult flagFirst = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-i", "orderexporter"));
        ToolResult alternation = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-i", "exporter|discount", "src"));
        ToolResult withContext = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-C", "2", "-n", "Discount", "src"));

        assertThat(flagFirst.success()).isTrue();
        assertThat(flagFirst.output()).contains("src/OrderExporter.java:1:");
        assertThat(alternation.output()).contains("src/OrderExporter.java:1:").contains("src/Discount.java:1:");
        assertThat(withContext.output()).contains("src/Discount.java:1:").doesNotContain("OrderExporter");
    }

    @Test
    void executeContextTool_rg_filesListsFilesWithPathAndGlob() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve("src/A.java"), "class A {}\n");
        Files.writeString(tempDir.resolve("src/notes.md"), "notes\n");
        Files.writeString(tempDir.resolve(".reviewignore"), "*.lock\n");
        Files.writeString(tempDir.resolve(".git/HEAD"), "ref: refs/heads/main\n");

        ToolResult all = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--files"));
        ToolResult inSrc = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--files", "src"));
        ToolResult javaOnly = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--files", "-g", "*.java"));
        ToolResult none = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--files", "-g", "*.kt"));

        assertThat(all.success()).isTrue();
        assertThat(all.output()).contains(".reviewignore").contains("src/A.java").contains("src/notes.md")
                .doesNotContain(".git/HEAD");
        assertThat(inSrc.output()).contains("src/A.java").contains("src/notes.md").doesNotContain(".reviewignore");
        assertThat(javaOnly.output()).isEqualTo("src/A.java");
        assertThat(none.output()).isEqualTo("No files found.");
    }

    @Test
    void executeContextTool_rg_explicitPatternAndDashPatterns() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Notes.md"), "a -> b\n-flag text\n");

        ToolResult arrow = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("->", "src"));
        ToolResult explicit = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("src", "-e", "-flag"));
        ToolResult flagsOnly = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-i", "-n"));

        assertThat(arrow.output()).contains("src/Notes.md:1:");
        assertThat(explicit.output()).contains("src/Notes.md:2:");
        assertThat(flagsOnly.success()).isFalse();
        assertThat(flagsOnly.error()).contains("requires at least a pattern");
    }

    @Test
    void executeContextTool_gitLog_ignoresFlagsAndReadsCounts() throws Exception {
        initRepoWithCommits("first", "second", "third");

        ToolResult flags = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("--oneline", "-2"));
        ToolResult maxCount = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("-n", "1"));
        ToolResult pathAndLimit = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("file.txt", "5"));

        assertThat(flags.success()).isTrue();
        assertThat(flags.output()).contains("third").contains("second").doesNotContain("first");
        assertThat(maxCount.output()).contains("third").doesNotContain("second");
        assertThat(pathAndLimit.output()).contains("first").contains("third");
    }

    @Test
    void executeContextTool_gitLog_reportsNoCommitsInsteadOfEmptyOutput() throws Exception {
        initRepoWithCommits("only");
        Files.writeString(tempDir.resolve("untracked.txt"), "x");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("untracked.txt"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("No commits found for path: untracked.txt");
    }

    @Test
    void executeContextTool_rg_repeatedRegexpPatternsAreOred() throws IOException {
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("A.txt"), "foo here\nbar there\nbaz\n");
        Files.writeString(tempDir.resolve("sub/B.txt"), "foo\n");
        Files.writeString(tempDir.resolve("Lit.txt"), "a.b\naxb\nc+d\ncd\n");

        ToolResult both = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-e", "foo", "-e", "bar", "A.txt"));
        ToolResult pathFirst = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("sub", "-e", "foo"));
        ToolResult longForm = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("sub", "--regexp=foo"));
        ToolResult fixed = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-F", "-e", "a.b", "-e", "c+d", "Lit.txt"));

        assertThat(both.output()).contains("A.txt:1: foo here").contains("A.txt:2: bar there").doesNotContain("baz");
        assertThat(pathFirst.output()).contains("B.txt:1: foo").doesNotContain("A.txt");
        assertThat(longForm.output()).contains("B.txt:1: foo").doesNotContain("A.txt");
        assertThat(fixed.output()).contains("Lit.txt:1: a.b").contains("Lit.txt:3: c+d")
                .doesNotContain("Lit.txt:2:").doesNotContain("Lit.txt:4:");
    }

    @Test
    void executeContextTool_rg_valueFlagsDoNotBecomePatternOrPath() throws IOException {
        Files.writeString(tempDir.resolve("Hello.java"), "Hello\n");
        Files.writeString(tempDir.resolve("Hello.txt"), "hello\n");

        ToolResult combinedGlob = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-ig", "*.java", "hello"));
        ToolResult grepInclude = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("--include", "*.txt", "hello"));
        ToolResult inlineContext = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-C3", "Hello"));
        ToolResult maxDepth = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("--max-depth", "2", "Hello"));
        ToolResult shortDepth = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-d", "1", "Hello"));

        assertThat(combinedGlob.output()).contains("Hello.java:1: Hello").doesNotContain("Hello.txt");
        assertThat(grepInclude.output()).contains("Hello.txt:1: hello").doesNotContain("Hello.java");
        assertThat(inlineContext.output()).contains("Hello.java:1: Hello");
        assertThat(maxDepth.output()).contains("Hello.java:1: Hello");
        assertThat(shortDepth.output()).contains("Hello.java:1: Hello");
    }

    @Test
    void executeContextTool_gitLog_passesAllowedFiltersAsOptionValues() throws Exception {
        git("init", "-q");
        commitAs("alice", "add feature", "alpha");
        commitAs("bob", "fix bug", "beta");

        ToolResult author = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("--author", "alice"));
        ToolResult grep = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("--grep=fix"));
        ToolResult pickaxe = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("-S", "beta"));
        ToolResult noMatch = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("--author", "nobody"));
        // a value is always passed as --name=value, so it cannot turn into an option of its own
        ToolResult injected = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log",
                List.of("--author", "--output=evil.txt"));
        ToolResult unsupported = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log",
                List.of("--stat", "--format", "%H"));

        assertThat(author.output()).contains("add feature").doesNotContain("fix bug");
        assertThat(grep.output()).contains("fix bug").doesNotContain("add feature");
        assertThat(pickaxe.output()).contains("fix bug").doesNotContain("add feature");
        assertThat(noMatch.output()).isEqualTo("No commits found (filters: --author=nobody)");
        assertThat(injected.output()).startsWith("No commits found (filters: --author=--output=evil.txt)");
        assertThat(tempDir.resolve("evil.txt")).doesNotExist();
        assertThat(unsupported.output()).contains("fix bug").contains("add feature")
                .endsWith("Ignored unsupported options: --stat, --format %H");
    }

    @Test
    void executeContextTool_gitLog_clampsCountsAndNeedsAPathForFollow() throws Exception {
        git("init", "-q");
        commitAs("alice", "add feature", "alpha");
        commitAs("bob", "fix bug", "beta");

        ToolResult huge = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("-99999999999"));
        ToolResult zero = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("0"));
        ToolResult follow = service.executeContextTool(Workspace.unmanaged(tempDir), "git-log", List.of("--follow"));

        assertThat(huge.success()).isTrue();
        assertThat(huge.output()).contains("add feature").contains("fix bug");
        assertThat(zero.output()).contains("fix bug").doesNotContain("add feature");
        assertThat(follow.output()).endsWith("Ignored unsupported options: --follow (needs a path)");
    }

    private void commitAs(String author, String message, String content) throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), content);
        git("add", "file.txt");
        git("-c", "user.name=" + author, "-c", "user.email=" + author + "@example.com", "commit", "-q", "-m", message);
    }

    private void initRepoWithCommits(String... messages) throws Exception {
        git("init", "-q");
        for (String message : messages) {
            Files.writeString(tempDir.resolve("file.txt"), message);
            git("add", "file.txt");
            git("-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "-m", message);
        }
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(tempDir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }

    @Test
    void executeContextTool_find_matchesGlobPattern() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/app.yml"), "name: app");
        Files.writeString(tempDir.resolve("src/Config.java"), "class Config {}");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "find", List.of("*.yml", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/app.yml");
    }

    @Test
    void executeContextTool_find_supportsShellStyleNameSyntax() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/org/example"));
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.java"), "class BotWebhookService {}");
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.txt"), "text");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "find",
                List.of("src/main/java", "-name", "BotWebhookService.java"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("src/main/java/org/example/BotWebhookService.java");
    }

    @Test
    void executeContextTool_find_supportsIncludeGlobAlongsideNameSyntax() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/java/org/example"));
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.java"), "class BotWebhookService {}");
        Files.writeString(tempDir.resolve("src/main/java/org/example/BotWebhookService.kt"), "class BotWebhookService");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "find",
                List.of("src/main/java/org/example", "--include=*.java", "-name", "BotWebhookService.*"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("src/main/java/org/example/BotWebhookService.java");
    }

    @Test
    void executeContextTool_rejectsGitInternalsAndSkipsThemDuringSearch() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".git/config"), "token=should-not-be-visible");

        ToolResult catResult = service.executeContextTool(Workspace.unmanaged(tempDir), "cat", List.of(".git/config"));
        ToolResult caseVariantResult = service.executeContextTool(Workspace.unmanaged(tempDir), "cat", List.of(".GIT/config"));
        ToolResult searchResult = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("should-not-be-visible", "."));

        assertThat(catResult.success()).isFalse();
        assertThat(catResult.error()).contains("Access to .git internals is not allowed");
        assertThat(caseVariantResult.success()).isFalse();
        assertThat(caseVariantResult.error()).contains("Access to .git internals is not allowed");
        assertThat(searchResult.success()).isTrue();
        assertThat(searchResult.output()).doesNotContain(".git/config");
    }

    @Test
    void executeContextTool_rejectsPathThroughSymlinkedDirectory() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "should-not-be-visible");
        Files.createSymbolicLink(workspace.resolve("linked"), outside);

        ToolResult result = service.executeContextTool(Workspace.unmanaged(workspace), "cat", List.of("linked/secret.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("symlinked directory");
    }

    @Test
    void executeContextTool_skipsLeafSymlinksDuringRecursiveOperations() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "host-only-secret");
        Files.createSymbolicLink(workspace.resolve("linked-secret.txt"), outside.resolve("secret.txt"));

        ToolResult searchResult = service.executeContextTool(Workspace.unmanaged(workspace), "rg", List.of("host-only-secret", "."));
        ToolResult findResult = service.executeContextTool(Workspace.unmanaged(workspace), "find", List.of("*", "."));
        ToolResult treeResult = service.executeContextTool(Workspace.unmanaged(workspace), "tree", List.of(".", "1"));

        assertThat(searchResult.success()).isTrue();
        assertThat(searchResult.output()).startsWith("No matches found");
        assertThat(findResult.output()).doesNotContain("linked-secret.txt");
        assertThat(treeResult.output()).doesNotContain("linked-secret.txt");
    }

    // ---- File tool tests ----

    @Test
    void executeFileTool_writeFile_createsFileWithContent() throws IOException {
        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "write-file",
                List.of("src/main/Foo.java", "public class Foo {}"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/main/Foo.java");
        assertThat(Files.readString(tempDir.resolve("src/main/Foo.java"))).isEqualTo("public class Foo {}");
    }

    @Test
    void executeFileTool_writeFile_overwritesExistingFile() throws IOException {
        Path target = tempDir.resolve("Existing.java");
        Files.writeString(target, "old content");

        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "write-file",
                List.of("Existing.java", "new content"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("new content");
    }

    @Test
    void executeFileTool_writeFile_sameContent_returnsFailure() throws IOException {
        Path target = tempDir.resolve("Existing.java");
        Files.writeString(target, "same content");

        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "write-file",
                List.of("Existing.java", "same content"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("would not change file");
    }

    @Test
    void executeFileTool_patchFile_replacesExactText() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service {\n    private int x = 1;\n}\n");

        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
                List.of("Service.java", "private int x = 1;", "private int x = 42;"));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target)).contains("private int x = 42;");
    }

    @Test
    void executeFileTool_patchFile_searchTextNotFound_returnsFailure() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service {}");

        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
                List.of("Service.java", "does not exist", "replacement"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("search text not found");
    }

    @Test
    void executeFileTool_patchFile_identicalReplacement_returnsFailure() throws IOException {
        Path target = tempDir.resolve("Service.java");
        Files.writeString(target, "class Service { int x = 1; }");

        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
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
        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
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
        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
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
        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
                List.of("Service.java", "class Service { int x = 1; }", "class Service { int x = 42; }"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("whitespace-tolerant");
        // Replacement line is written verbatim (LLM-provided form).
        assertThat(Files.readString(target)).isEqualTo("class Service { int x = 42; }\n");
    }

    @Test
    void executeFileTool_mkdir_createsDirectory() {
        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "mkdir",
                List.of("new/nested/dir"));

        assertThat(result.success()).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("new/nested/dir"))).isTrue();
    }

    @Test
    void executeFileTool_mkdir_existingDirectory_returnsFailure() throws IOException {
        Files.createDirectories(tempDir.resolve("existing"));

        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "mkdir", List.of("existing"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("already exists");
    }

    @Test
    void executeFileTool_deleteFile_removesFile() throws IOException {
        Path target = tempDir.resolve("ToDelete.java");
        Files.writeString(target, "delete me");

        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "delete-file",
                List.of("ToDelete.java"));

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void executeFileTool_deleteFile_nonexistent_returnsFailure() {
        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "delete-file",
                List.of("DoesNotExist.java"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("did not exist");
    }

    @Test
    void executeFileTool_pathTraversal_returnsError() {
        ToolResult result = service.executeFileTool(Workspace.unmanaged(tempDir), "write-file",
                List.of("../../etc/passwd", "evil content"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("escapes");
    }
}
