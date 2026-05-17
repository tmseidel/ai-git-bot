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
}
