package org.remus.giteabot.agent.validation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

    private ToolExecutionService service;

    @TempDir
    Path tempDir;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logEvents;

    @BeforeEach
    void setUp() {
        AgentConfigProperties config = new AgentConfigProperties();
        service = new ToolExecutionService(config,
                new org.remus.giteabot.agent.tools.ToolCatalog(config),
                new org.remus.giteabot.agent.packagemanager.PackageManagerDetector(),
                new org.remus.giteabot.agent.packagemanager.YarnCommandBuilder(config));
        serviceLogger = (Logger) LoggerFactory.getLogger(ToolExecutionService.class);
        logEvents = new ListAppender<>();
        logEvents.start();
        serviceLogger.addAppender(logEvents);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logEvents);
    }

    /** INFO-and-above messages the service logged about {@code patch-file}. */
    private List<String> patchFileLogLines() {
        return logEvents.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("patch-file:"))
                .toList();
    }

    /** INFO-and-above messages the service logged about the {@code rg} tool. */
    private List<String> searchLogLines() {
        return logEvents.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("rg: "))
                .toList();
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
    void executeContextTool_rg_invalidRegexIsSearchedLiterallyAndSaysSo() throws IOException {
        Path file = tempDir.resolve("src/Config.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "void reviewPullRequest(A a) {}\n");

        ToolResult hit = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("reviewPullRequest(", "src"));
        ToolResult miss = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("nope(", "src"));

        assertThat(hit.success()).isTrue();
        assertThat(hit.output()).contains("not a valid regex").contains("literal")
                .contains("src/Config.java:1: void reviewPullRequest(A a) {}");
        assertThat(miss.output()).contains("not a valid regex").contains("No matches found");
    }

    @Test
    void executeContextTool_rg_validRegexHasNoLiteralNote() throws IOException {
        Path file = tempDir.resolve("src/Config.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Config {}\n");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("Config", "src"));

        assertThat(result.output()).doesNotContain("not a valid regex");
    }

    @Test
    void executeContextTool_rg_capsMatchesPerFileUnlessOneFileIsSearched() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Snapshot.pcf"), "prune: true\n".repeat(12));
        Files.writeString(tempDir.resolve("src/App.pkl"), "prune = true\n");

        ToolResult wide = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("prune", "src"));
        ToolResult oneFile = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("prune", "src/Snapshot.pcf"));
        ToolResult filesOnly = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-l", "prune", "src"));

        int perFile = ToolExecutionService.MAX_SEARCH_MATCHES_PER_FILE;
        assertThat(wide.output().lines().filter(l -> l.startsWith("src/Snapshot.pcf:"))).hasSize(perFile + 1);
        assertThat(wide.output()).contains("src/Snapshot.pcf:" + perFile + ": prune: true")
                .doesNotContain("src/Snapshot.pcf:" + (perFile + 1) + ":")
                .contains("src/Snapshot.pcf: … " + (12 - perFile) + " more matches in this file; "
                        + "search that file alone to see all.")
                .contains("src/App.pkl:1: prune = true");
        assertThat(oneFile.output().lines()).hasSize(12);
        assertThat(filesOnly.output().lines()).containsExactly("src/App.pkl", "src/Snapshot.pcf");
    }

    @Test
    void executeContextTool_rg_saysSoWhenItStopsAtTheResultCap() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Big.txt"), "needle\n".repeat(250));
        Files.writeString(tempDir.resolve("src/Small.txt"), "needle\n".repeat(3));

        ToolResult capped = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("needle", "src/Big.txt"));
        ToolResult complete = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("needle", "src/Small.txt"));

        assertThat(capped.output().lines().findFirst()).contains(ToolExecutionService.SEARCH_CAPPED_NOTE);
        assertThat(capped.output().lines().filter(l -> l.startsWith("src/Big.txt:"))).hasSize(200);
        assertThat(complete.output()).doesNotContain(ToolExecutionService.SEARCH_CAPPED_NOTE);
    }

    @Test
    void executeContextTool_rg_doesNotSayCappedForExactlyAsManyMatchesAsTheCap() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Exact.txt"), "needle\n".repeat(200));
        Files.writeString(tempDir.resolve("src/One.txt"), "needle\n".repeat(201));

        ToolResult exact = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("needle", "src/Exact.txt"));
        ToolResult over = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("needle", "src/One.txt"));

        assertThat(exact.output().lines().filter(l -> l.contains("Exact.txt:"))).hasSize(200);
        assertThat(exact.output()).doesNotContain(ToolExecutionService.SEARCH_CAPPED_NOTE);
        assertThat(over.output()).startsWith(ToolExecutionService.SEARCH_CAPPED_NOTE);
    }

    @Test
    void executeContextTool_rg_usesSingularForOneRemainingMatch() throws IOException {
        int perFile = ToolExecutionService.MAX_SEARCH_MATCHES_PER_FILE;
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Snapshot.pcf"), "prune: true\n".repeat(perFile + 1));
        Files.writeString(tempDir.resolve("src/App.pkl"), "prune = true\n");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("prune", "src"));

        assertThat(result.output()).contains("src/Snapshot.pcf: … 1 more match in this file; "
                + "search that file alone to see all.");
    }

    @Test
    void executeContextTool_rg_searchesEveryGivenPathLikeRipgrep() throws IOException {
        Files.createDirectories(tempDir.resolve("doc"));
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("doc/AGENT.md"), "use rg here\n");
        Files.writeString(tempDir.resolve("doc/TOOLS.md"), "rg and find\n");
        Files.writeString(tempDir.resolve("prompts/review.md"), "nothing\n");

        // with -e every positional is a path
        ToolResult all = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-e", "rg ", "prompts", "doc/AGENT.md", "doc/TOOLS.md"));
        // a second pattern passed as a path: reported as not found, the other path is still searched
        ToolResult secondPattern = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("rg", "doc", "find|grep"));
        ToolResult noneExists = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("rg", "gone", "missing"));
        ToolResult overlapping = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("find", "doc", "doc/TOOLS.md"));
        ToolResult listing = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--files", "prompts", "doc/AGENT.md"));

        assertThat(all.output()).contains("doc/AGENT.md:1: use rg here", "doc/TOOLS.md:1: rg and find")
                .doesNotContain("Not searched");
        assertThat(secondPattern.success()).isTrue();
        assertThat(secondPattern.output()).startsWith("Not searched, path not found: find|grep")
                .contains("doc/AGENT.md:1:");
        assertThat(noneExists.success()).isFalse();
        assertThat(noneExists.error()).isEqualTo("Path not found: gone, missing");
        assertThat(overlapping.output()).isEqualTo("doc/TOOLS.md:1: rg and find");
        assertThat(listing.output()).isEqualTo("doc/AGENT.md\nprompts/review.md");
    }

    @Test
    void executeContextTool_rg_maxDepthLimitsTheSearchAndTheFileList() throws IOException {
        Files.createDirectories(tempDir.resolve("sub/deep"));
        Files.writeString(tempDir.resolve("Top.txt"), "needle\n");
        Files.writeString(tempDir.resolve("sub/deep/Low.txt"), "needle\n");

        ToolResult shallow = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-d", "1", "needle"));
        ToolResult deep = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--max-depth=3", "needle"));
        ToolResult files = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--files", "--maxdepth", "1"));
        ToolResult bad = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-d", "x", "needle"));

        assertThat(shallow.output()).contains("Top.txt:1:").doesNotContain("Low.txt");
        assertThat(deep.output()).contains("Top.txt:1:").contains("sub/deep/Low.txt:1:");
        assertThat(files.output()).isEqualTo("Top.txt");
        assertThat(bad.success()).isFalse();
        assertThat(bad.error()).startsWith("-d needs a non-negative number, got 'x'.");
    }

    @Test
    void executeContextTool_rg_printsContextLinesLikeRipgrep() throws IOException {
        Files.writeString(tempDir.resolve("A.txt"), "l1\nl2\nhit3\nl4\nl5\nl6\nl7\nhit8\nl9\n");
        Files.writeString(tempDir.resolve("B.txt"), "hit1\nb2\n");

        ToolResult after = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-A", "1", "hit", "A.txt"));
        ToolResult around = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-C2", "hit", "A.txt"));
        ToolResult longForm = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--before-context=1", "hit3", "A.txt"));
        ToolResult twoFiles = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-B", "1", "hit[13]", "-g", "*.txt"));

        assertThat(after.output().lines()).containsExactly(
                "A.txt:3: hit3", "A.txt-4- l4", "--", "A.txt:8: hit8", "A.txt-9- l9");
        // overlapping windows merge into one group without a separator
        assertThat(around.output().lines()).containsExactly(
                "A.txt-1- l1", "A.txt-2- l2", "A.txt:3: hit3", "A.txt-4- l4", "A.txt-5- l5",
                "A.txt-6- l6", "A.txt-7- l7", "A.txt:8: hit8", "A.txt-9- l9");
        assertThat(longForm.output().lines()).containsExactly("A.txt-2- l2", "A.txt:3: hit3");
        assertThat(twoFiles.output().lines()).containsExactly(
                "A.txt-2- l2", "A.txt:3: hit3", "--", "B.txt:1: hit1");
    }

    @Test
    void executeContextTool_rg_specificContextWinsAndLargeValuesAreClamped() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            content.append(i == 15 ? "hit" : "l" + i).append('\n');
        }
        Files.writeString(tempDir.resolve("A.txt"), content);

        ToolResult mixed = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-A", "0", "-C", "1", "hit", "A.txt"));
        ToolResult huge = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-C", "500", "hit", "A.txt"));

        assertThat(mixed.output().lines()).containsExactly("A.txt-14- l14", "A.txt:15: hit");
        int max = ToolExecutionService.MAX_SEARCH_CONTEXT_LINES;
        assertThat(huge.output().lines()).hasSize(2 * max + 1)
                .startsWith("A.txt-" + (15 - max) + "- l" + (15 - max));
    }

    @Test
    void executeContextTool_rg_rejectsUnsupportedFlagsWithAHint() throws IOException {
        Files.writeString(tempDir.resolve("A.txt"), "foo\n");

        ToolResult invert = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-v", "foo"));
        ToolResult clustered = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-iw", "foo"));
        ToolResult longOption = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--word-regexp", "foo"));
        ToolResult badCount = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-A", "x", "foo"));
        ToolResult missingValue = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("foo", "-C"));
        ToolResult glued = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-A2 \"name: Postgres__Host\"", "A.txt"));

        assertThat(invert.success()).isFalse();
        assertThat(invert.error()).startsWith("Unsupported flag -v.").contains(ToolExecutionService.SEARCH_SUPPORTED_FLAGS);
        assertThat(clustered.error()).startsWith("Unsupported flag -w in -iw.");
        assertThat(longOption.error()).startsWith("Unsupported option --word-regexp.");
        assertThat(badCount.error()).startsWith("-A needs a number of lines, got 'x'.");
        assertThat(missingValue.error()).startsWith("Flag -C needs a value.");
        assertThat(glued.success()).isFalse();
        assertThat(glued.error()).contains("holds a flag and its value in one element")
                .contains("If it is a pattern that starts with -, pass it as [\"-e\", ")
                .contains("or put \"--\" before it");
    }

    @Test
    void executeContextTool_rg_onlyMatchingPrintsEachMatchLikeRipgrep() throws IOException {
        Files.writeString(tempDir.resolve("list.html"), """
                <td>${entry.model}</td><td>${entry.tokens}</td>
                <p>static text</p>
                <td>${entry.cost}</td>
                """);

        ToolResult only = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-o", "\\$\\{entry\\.[a-zA-Z]+", "list.html"));
        ToolResult longForm = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("--only-matching", "entry\\.cost", "list.html"));
        ToolResult withContext = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("-o", "-B", "1", "entry\\.cost", "list.html"));
        ToolResult emptyMatches = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-o", "q*", "list.html"));

        assertThat(only.output()).isEqualTo("""
                list.html:1: ${entry.model
                list.html:1: ${entry.tokens
                list.html:3: ${entry.cost""");
        assertThat(longForm.output()).isEqualTo("list.html:3: entry.cost");
        // as in ripgrep, context lines stay whole
        assertThat(withContext.output()).isEqualTo("list.html-2- <p>static text</p>\nlist.html:3: entry.cost");
        // a pattern that matches only the empty string prints nothing for that line
        assertThat(emptyMatches.output()).doesNotContain("list.html:");
    }

    @Test
    void executeContextTool_rg_rejectsBlankValuesAndReadsANonOptionAsThePattern() throws IOException {
        Files.writeString(tempDir.resolve("Git.txt"), "git clone --depth 1\nplain line\n");

        ToolResult emptyPattern = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-e", "", "Git.txt"));
        ToolResult emptyGlob = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("clone", "-g", " "));
        ToolResult emptyLong = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--regexp=", "Git.txt"));
        // no option is named like that, so it is the pattern
        ToolResult alternatives = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("--depth|depth 1|shallow", "Git.txt"));
        ToolResult unknownOption = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--depth", "Git.txt"));

        assertThat(emptyPattern.error()).startsWith("Flag -e needs a non-empty value.");
        assertThat(emptyGlob.error()).startsWith("Flag -g needs a non-empty value.");
        assertThat(emptyLong.error()).startsWith("Flag --regexp needs a non-empty value.");
        assertThat(alternatives.success()).isTrue();
        assertThat(alternatives.output()).isEqualTo("Git.txt:1: git clone --depth 1");
        assertThat(unknownOption.error()).startsWith("Unsupported option --depth. To search for it, pass [\"-e\", \"--depth\"].");
    }

    @Test
    void executeContextTool_rg_rejectsAGluedFlagValueInsteadOfSearchingForIt() throws IOException {
        Files.writeString(tempDir.resolve("A.java"), "foo\n");

        ToolResult equalsForm = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-g=*.java", "foo"));
        ToolResult glob = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("-g*.java", "foo"));
        // the same text after -- or -e is a pattern
        ToolResult asPattern = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--", "-g=*.java"));

        assertThat(equalsForm.success()).isFalse();
        assertThat(equalsForm.error()).startsWith("Cannot read '-g=*.java' as flags")
                .contains("[\"-g\", \"*.java\"]");
        assertThat(glob.success()).isFalse();
        assertThat(asPattern.success()).isTrue();
        assertThat(asPattern.output()).startsWith("No matches found");
    }

    @Test
    void executeContextTool_rg_acceptsNoOpFlags() throws IOException {
        Files.writeString(tempDir.resolve("A.txt"), "foo\n");

        ToolResult grepStyle = service.executeContextTool(Workspace.unmanaged(tempDir), "grep", List.of("-rnHE", "foo", "."));
        ToolResult longNoOps = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("--no-heading", "--color=never", "--line-number", "foo"));
        ToolResult afterDashDash = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--", "-v", "A.txt"));

        assertThat(grepStyle.output()).isEqualTo("A.txt:1: foo");
        assertThat(longNoOps.output()).isEqualTo("A.txt:1: foo");
        assertThat(afterDashDash.success()).isTrue();
        assertThat(afterDashDash.output()).startsWith("No matches found for pattern: -v");
    }

    @Test
    void executeContextTool_rg_globsExcludeWithBangAndAlternateWithBraces() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.createDirectories(tempDir.resolve("src/test"));
        Files.writeString(tempDir.resolve("src/main/Router.java"), "case \"pr-diff\" ->\n");
        Files.writeString(tempDir.resolve("src/main/Catalog.kt"), "\"pr-diff\"\n");
        Files.writeString(tempDir.resolve("src/test/RouterTest.java"), "\"pr-diff\"\n");
        Files.writeString(tempDir.resolve("NOTES.md"), "pr-diff\n");

        ToolResult noTests = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("pr-diff|pr_diff", "-n", "--glob", "!src/test/**"));
        ToolResult codeOnly = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("pr-diff", "-g", "*.{java,kt}"));
        ToolResult javaNoTests = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("pr-diff", "-g", "*.java", "-g", "!**/test/**"));
        ToolResult listing = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("--files", "-g", "!*.md"));

        assertThat(noTests.output()).contains("src/main/Router.java:1:", "src/main/Catalog.kt:1:", "NOTES.md:1:")
                .doesNotContain("RouterTest");
        assertThat(codeOnly.output()).contains("Router.java", "Catalog.kt", "RouterTest.java")
                .doesNotContain("NOTES.md");
        assertThat(javaNoTests.output()).contains("src/main/Router.java:1:")
                .doesNotContain("Catalog.kt").doesNotContain("RouterTest");
        assertThat(listing.output()).contains("src/main/Router.java").doesNotContain("NOTES.md");
    }

    @Test
    void globToRegex_supportsDoubleStarSlashBracesAndLiteralStrays() {
        String anyDepth = ToolExecutionService.globToRegex("**/*.java");
        assertThat("A.java").matches(anyDepth);
        assertThat("src/main/A.java").matches(anyDepth);
        assertThat("A.kt").doesNotMatch(anyDepth);

        assertThat("a.kt").matches(ToolExecutionService.globToRegex("*.{java,kt}"));
        assertThat("x.txt").matches(ToolExecutionService.globToRegex("{x}.txt"));
        assertThat("a,b}").matches(ToolExecutionService.globToRegex("a,b}"));
        // an unclosed brace still compiles and matches its alternatives
        assertThat("ab").matches(ToolExecutionService.globToRegex("a{b,c"));
    }

    @Test
    void executeContextTool_rg_notesSurviveTheCutOfALongOutput() throws IOException {
        // 60 files x 3 matching lines of about 80 chars: over the 10,000-character output limit, but under the
        // 200-result search cap
        Files.createDirectories(tempDir.resolve("big"));
        for (int i = 0; i < 60; i++) {
            Files.writeString(tempDir.resolve("big/F" + i + ".txt"), ("needle " + "x".repeat(60) + "\n").repeat(3));
        }

        ToolResult search = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of("needle", "big", "gone"));

        assertThat(search.outputTruncated()).isTrue();
        assertThat(search.output()).startsWith("Not searched, path not found: gone\n");
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

    @Test
    void executeContextTool_search_skipsBinaryFilesInTreeInsteadOfFailing() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("wwwroot"));
        Files.writeString(src.resolve("Index.cshtml"), "<input id=\"hide-passed-rows-checkbox\" />\n");
        // PNG header: NUL byte marks it binary, and the bytes are not valid UTF-8 either.
        Files.write(src.resolve("wwwroot/logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x00, 0x01, (byte) 0xFF});

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("hide-passed-rows-checkbox", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/Index.cshtml:1: ");
        assertThat(result.output()).doesNotContain("logo.png");
    }

    @Test
    void executeContextTool_search_doesNotFailOnNonUtf8TextFile() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Index.cshtml"), "<input id=\"hide-passed-rows-checkbox\" />\n");
        // 0xE9 is latin-1 'é' and invalid UTF-8.
        Files.writeString(src.resolve("legacy.txt"),
                "café: hide-passed-rows-checkbox", StandardCharsets.ISO_8859_1);

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("hide-passed-rows-checkbox", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/Index.cshtml:1: ");
        assertThat(result.output()).contains("src/legacy.txt:1: ");
    }

    @Test
    void executeContextTool_search_zeroHits_succeedsWithNoMatchesMessage() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Index.cshtml"), "nothing relevant here\n");

        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("hide-passed-rows-checkbox", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).startsWith("No matches found for pattern: hide-passed-rows-checkbox");
    }

    @Test
    void executeContextTool_search_missingPattern_logsOneLineWithReason() {
        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg", List.of());

        assertThat(result.success()).isFalse();
        assertThat(searchLogLines()).singleElement().satisfies(line ->
                assertThat(line).contains("no pattern argument"));
    }

    @Test
    void executeContextTool_search_missingPath_logsOneLineWithPathAndReason() {
        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("needle", "src/missing"));

        assertThat(result.success()).isFalse();
        assertThat(searchLogLines()).singleElement().satisfies(line ->
                assertThat(line).contains("src/missing").contains("not found"));
    }

    @Test
    void executeContextTool_search_rejectedPath_logsOneLineWithPathAndReason() {
        ToolResult result = service.executeContextTool(Workspace.unmanaged(tempDir), "rg",
                List.of("needle", "../outside"));

        assertThat(result.success()).isFalse();
        assertThat(searchLogLines()).singleElement().satisfies(line ->
                assertThat(line).contains("../outside").contains("traversal"));
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
    void executeFileTool_patchFile_successfulPatch_logsExactlyOneLineNamingPathAndTier() throws IOException {
        Files.writeString(tempDir.resolve("Service.java"), "class Service { int x = 1; }");

        service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
                List.of("Service.java", "int x = 1;", "int x = 2;"));

        assertThat(patchFileLogLines()).singleElement().satisfies(line ->
                assertThat(line).contains("Service.java").contains("EXACT"));
    }

    @Test
    void executeFileTool_patchFile_searchTextNotFound_logsPathAndStatus() throws IOException {
        Files.writeString(tempDir.resolve("Service.java"), "class Service {}");

        service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
                List.of("Service.java", "secret-looking search text", "replacement"));

        assertThat(patchFileLogLines()).singleElement().satisfies(line ->
                assertThat(line).contains("Service.java").contains("NOT_FOUND")
                        .doesNotContain("secret-looking search text"));
    }

    @Test
    void executeFileTool_patchFile_ambiguousSearchText_logsPathStatusAndMatchCount() throws IOException {
        Files.writeString(tempDir.resolve("Service.java"), "int x = 1;\nint y = 1;\nint z = 1;\n");

        service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
                List.of("Service.java", "= 1;", "= 2;"));

        assertThat(patchFileLogLines()).singleElement().satisfies(line ->
                assertThat(line).contains("Service.java").contains("AMBIGUOUS").contains("3"));
    }

    @Test
    void executeFileTool_patchFile_identicalReplacement_logsPathAndStatus() throws IOException {
        Files.writeString(tempDir.resolve("Service.java"), "class Service { int x = 1; }");

        service.executeFileTool(Workspace.unmanaged(tempDir), "patch-file",
                List.of("Service.java", "int x = 1;", "int x = 1;"));

        assertThat(patchFileLogLines()).singleElement().satisfies(line ->
                assertThat(line).contains("Service.java").contains("NO_CHANGE"));
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
