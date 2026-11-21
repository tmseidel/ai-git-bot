package org.remus.giteabot.agent.shared;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.SystemPromptAssembler.PromptKind;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class SystemPromptAssemblerTest {
    private static final String CLEAN_BASE = "You are an autonomous software implementation agent.";
    private static final String BASE_WITH_MARKERS = """
            You are an autonomous software implementation agent.
            <!-- BEGIN_LEGACY_TOOL_PROTOCOL -->
            ## Output Format
            Respond with a JSON object using runTools / requestTools / requestFiles.
            <!-- END_LEGACY_TOOL_PROTOCOL -->
            """;
    private final McpToolCatalog mcpCatalog = new McpToolCatalog(List.of(
            new McpToolDefinition("github", "search_repositories",
                    "Search repositories", "Search GitHub repositories",
                    Map.of("type", "object"), "mcp:github:search_repositories")));
    private final ToolCatalog toolCatalog = new ToolCatalog(new AgentConfigProperties());
    @Test
    void legacyMode_appendsDynamicTemplateAndMcpCatalog() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                mcpCatalog, ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertTrue(out.startsWith(CLEAN_BASE));
        assertTrue(out.contains("## Output Format"));
        assertTrue(out.contains("runTools"));
        assertTrue(out.contains("## Security"));
        assertTrue(out.contains("## File Tools"));
        assertTrue(out.contains("write-file"));
        assertTrue(out.contains("Available MCP tools"));
        assertTrue(out.contains("mcp:github:search_repositories"));
    }
    @Test
    void legacyMode_whitelistRestrictsRenderedTools() {
        Set<String> allowed = Set.of("cat", "rg", "mvn");
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, allowed,
                McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertTrue(out.contains("`cat`"));
        assertTrue(out.contains("`rg`"));
        assertTrue(out.contains("`mvn`"));
        assertFalse(out.contains("write-file"));
        assertFalse(out.contains("patch-file"));
        assertFalse(out.contains("`gradle`"));
        assertFalse(out.contains("## File Tools"));
    }
    @Test
    void nativeMode_appendsNativeHintAndOmitsMcpCatalog() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                mcpCatalog, ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);
        assertTrue(out.startsWith(CLEAN_BASE));
        assertFalse(out.contains("## Output Format"));
        assertFalse(out.contains("runTools"));
        assertFalse(out.contains("Available MCP tools"));
        assertFalse(out.contains("mcp:github:search_repositories"));
        assertTrue(out.contains("native function-calling API"));
        assertTrue(out.contains("## Security"));
    }
    @Test
    void legacyMode_unmigratedPromptWithMarkers_stripsMarkerBlockAndRendersDynamic() {
        String out = new SystemPromptAssembler().assemble(BASE_WITH_MARKERS, toolCatalog, null,
                mcpCatalog, ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertFalse(out.contains(SystemPromptAssembler.BEGIN_MARKER));
        assertFalse(out.contains(SystemPromptAssembler.END_MARKER));
        assertFalse(out.contains("Respond with a JSON object using runTools / requestTools / requestFiles."));
        assertTrue(out.contains("## Output Format"));
    }
    @Test
    void nativeMode_unmigratedPromptWithMarkers_stripsBlockAndAppendsHint() {
        String out = new SystemPromptAssembler().assemble(BASE_WITH_MARKERS, toolCatalog, null,
                mcpCatalog, ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);
        assertFalse(out.contains(SystemPromptAssembler.BEGIN_MARKER));
        assertFalse(out.contains("## Output Format"));
        assertFalse(out.contains("runTools"));
        assertTrue(out.contains("native function-calling API"));
    }
    @Test
    void writerKind_legacyRenderedWithWriterTools() {
        String legacyOut = new SystemPromptAssembler().assemble("You are a writer.", toolCatalog, null,
                mcpCatalog, ToolingMode.LEGACY, PromptKind.WRITER_AGENT);
        assertTrue(legacyOut.contains("Reasoning tools:"));
        assertTrue(legacyOut.contains("get-issue"));
        assertTrue(legacyOut.contains("Available MCP tools"));
        String nativeOut = new SystemPromptAssembler().assemble("You are a writer.", toolCatalog, null,
                mcpCatalog, ToolingMode.NATIVE, PromptKind.WRITER_AGENT);
        assertTrue(nativeOut.contains("native function-calling API"));
        assertFalse(nativeOut.contains("Available MCP tools"));
    }
    @Test
    void nullPromptReturnsEmptyString() {
        assertEquals("", new SystemPromptAssembler().assemble(null, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.ISSUE_AGENT));
    }
    @Test
    void emptyMcpCatalogProducesNoMcpFragmentInLegacyMode() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertFalse(out.contains("Available MCP tools"));
    }

    @Test
    void nativeMode_appendsTheCodeExecutionGuidanceWhenTheToolIsSelected() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "execute-code"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.ISSUE_AGENT);

        assertTrue(out.contains("## Running code"));
        assertTrue(out.contains("the tools you can see"));
    }

    @Test
    void nativeMode_leavesTheGuidanceOutWhenTheToolIsNotSelected() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "rg"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.ISSUE_AGENT);

        assertFalse(out.contains("## Running code"));
        assertFalse(out.contains("execute-code"));
    }

    @Test
    void anUnconfiguredWhitelistIsTreatedAsEveryTool() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);

        assertTrue(out.contains("## Running code"));
    }

    @Test
    void theStrategyBulletsFollowTheWhitelist() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "execute-code"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.ISSUE_AGENT);

        // The one tool this bot has is described; the ones it does not have are not mentioned as
        // something to reach for.
        assertTrue(out.contains("**Read specific lines once you know the structure**"));
        assertFalse(out.contains("**First look at an unfamiliar file**"));
        assertFalse(out.contains("**Locate files by path**"));
    }

    @Test
    void theExecuteCodeBulletSitsInTheStrategyAndFollowsTheSelection() {
        String with = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "execute-code"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.ISSUE_AGENT);
        String without = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat"), McpToolCatalog.empty(), ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);

        assertTrue(with.contains("**An answer that takes several reads, or a step between them**"));
        assertFalse(without.contains("**An answer that takes several reads, or a step between them**"));
    }

    @Test
    void everyPromptKindGetsTheStrategyNotJustTheIssueAgent() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "execute-code"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.WRITER_AGENT);

        assertTrue(out.contains("**An answer that takes several reads, or a step between them**"));
        assertTrue(out.contains("**Read specific lines once you know the structure**"));
    }

    @Test
    void aToolWithNoHintContributesNoBullet() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat"), McpToolCatalog.empty(), ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);

        assertTrue(out.contains("`cat` with `startLine`"));
        assertFalse(out.contains("finds symbol usages"));
        assertFalse(out.contains("**First look at an unfamiliar file**"));
    }

    @Test
    void anUnconfiguredWhitelistRendersEveryStrategyBullet() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);

        assertTrue(out.contains("**First look at an unfamiliar file**"));
        assertTrue(out.contains("**An answer that takes several reads, or a step between them**"));
    }

    @Test
    void everyStageLoadsItsOwnProtocolFile() {
        Map<PromptKind, String> marker = Map.ofEntries(
                Map.entry(PromptKind.ISSUE_AGENT, "When no code change is needed"),
                Map.entry(PromptKind.WRITER_AGENT, "Do not request repository write tools"),
                Map.entry(PromptKind.TRIAGE_AGENT, "### Read-only"),
                Map.entry(PromptKind.E2E_TEST_AUTHOR, "### Writing the tests"),
                Map.entry(PromptKind.E2E_TEST_RUNNER, "### Running the suite"),
                Map.entry(PromptKind.README_SYNC_AGENT, "### Updating the documentation"),
                Map.entry(PromptKind.I18N_COVERAGE_AGENT, "### Updating the locale files"),
                Map.entry(PromptKind.UNIT_TEST_AUTHOR_AGENT, "production code is off-limits"),
                Map.entry(PromptKind.AGENT_REVIEW_AGENT, "## Recording the review"),
                Map.entry(PromptKind.AGENT_REVIEW_QA, "### Answering a follow-up question"));

        for (Map.Entry<PromptKind, String> stage : marker.entrySet()) {
            String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                    McpToolCatalog.empty(), ToolingMode.NATIVE, stage.getKey());
            assertTrue(out.contains(stage.getValue()),
                    () -> stage.getKey() + " must carry its own protocol, not another workflow's");
        }
    }

    @Test
    void theTwoE2eStagesDoNotShareAProtocol() {
        String author = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.NATIVE, PromptKind.E2E_TEST_AUTHOR);
        String runner = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog, null,
                McpToolCatalog.empty(), ToolingMode.NATIVE, PromptKind.E2E_TEST_RUNNER);

        assertFalse(author.contains("### Running the suite"));
        assertFalse(runner.contains("### Writing the tests"));
    }

    @Test
    void aPrWorkflowStageDescribesOnlyTheToolsItsAgentCanCall() {
        String author = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("pr-test-write"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.E2E_TEST_AUTHOR);
        String runner = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("preview-url", "preview-status", "pr-test-run", "attach-artifact"),
                McpToolCatalog.empty(), ToolingMode.NATIVE, PromptKind.E2E_TEST_RUNNER);

        assertTrue(author.contains("**Write each test into the workspace**"));
        assertFalse(author.contains("**Run the suite and get the results back**"));

        assertTrue(runner.contains("**Run the suite and get the results back**"));
        assertTrue(runner.contains("**Check the preview before spending a run on it**"));
        assertFalse(runner.contains("**Write each test into the workspace**"));
    }

    @Test
    void theStrategyFollowsTheStageRoleNotJustTheWhitelist() {
        // `pr-test-write` is whitelisted, but the issue agent's role has no PR-workflow tools, so
        // the stage is told nothing about a tool it could never call.
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "pr-test-write"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.ISSUE_AGENT);

        assertTrue(out.contains("**Read specific lines once you know the structure**"));
        assertFalse(out.contains("**Write each test into the workspace**"));
    }

    @Test
    void theValidationPolicyNamesOnlyTheSelectedValidators() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "mvn"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.ISSUE_AGENT);

        assertTrue(out.contains("**After a change, validation is mandatory**"));
        assertTrue(out.contains("`mvn`"));
        assertFalse(out.contains("`gradle`"));
    }

    @Test
    void aReadOnlyStageGetsNoValidationPolicy() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, toolCatalog,
                Set.of("cat", "mvn"), McpToolCatalog.empty(), ToolingMode.NATIVE,
                PromptKind.WRITER_AGENT);

        assertFalse(out.contains("validation is mandatory"));
    }
}
