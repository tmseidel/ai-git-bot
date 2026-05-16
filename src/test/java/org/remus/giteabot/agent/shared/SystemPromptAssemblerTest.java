package org.remus.giteabot.agent.shared;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.SystemPromptAssembler.PromptKind;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptAssemblerTest {

    private static final String CLEAN_BASE = "You are an autonomous software implementation agent.";

    /** Pre-V13 prompt where V12 has wrapped the JSON-protocol block in markers. */
    private static final String BASE_WITH_MARKERS = """
            You are an autonomous software implementation agent.
            <!-- BEGIN_LEGACY_TOOL_PROTOCOL -->
            ## Output Format
            Respond with a JSON object using runTools / requestTools / requestFiles.
            <!-- END_LEGACY_TOOL_PROTOCOL -->
            """;

    private final McpToolCatalog catalog = new McpToolCatalog(List.of(
            new McpToolDefinition("github", "search_repositories",
                    "Search repositories", "Search GitHub repositories",
                    Map.of("type", "object"), "mcp:github:search_repositories")));

    @Test
    void legacyMode_appendsLegacyTemplateAndMcpCatalog() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, catalog,
                ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);

        assertTrue(out.startsWith(CLEAN_BASE), "Base role description must come first");
        assertTrue(out.contains("## Output Format"),
                "LEGACY mode must append the JSON-protocol template");
        assertTrue(out.contains("runTools"));
        assertTrue(out.contains("## Security"),
                "Security note is part of the legacy template");
        assertTrue(out.contains("Available MCP tools"),
                "MCP catalog must be appended in LEGACY mode");
        assertTrue(out.contains("mcp:github:search_repositories"));
    }

    @Test
    void nativeMode_appendsNativeHintAndOmitsMcpCatalog() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, catalog,
                ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);

        assertTrue(out.startsWith(CLEAN_BASE));
        assertFalse(out.contains("## Output Format"),
                "NATIVE mode must NOT append the JSON-protocol template");
        assertFalse(out.contains("runTools"));
        assertFalse(out.contains("Available MCP tools"),
                "MCP catalog goes through the API in NATIVE mode and must not be inlined");
        assertFalse(out.contains("mcp:github:search_repositories"));
        assertTrue(out.contains("native function-calling API"),
                "Native-mode hint must explain that tools come via the API");
        assertTrue(out.contains("## Security"),
                "Security note must remain in NATIVE mode too");
    }

    @Test
    void legacyMode_unmigratedPromptWithMarkers_stripsMarkerBlockAndAppendsTemplate() {
        // Operator's DB still has a V12-wrapped block. The assembler must strip the
        // wrapped content (templates carry it now) and not produce duplicate guidance.
        String out = new SystemPromptAssembler().assemble(BASE_WITH_MARKERS, catalog,
                ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);

        assertFalse(out.contains(SystemPromptAssembler.BEGIN_MARKER));
        assertFalse(out.contains(SystemPromptAssembler.END_MARKER));
        assertFalse(out.contains("Respond with a JSON object using runTools / requestTools / requestFiles."),
                "Wrapped content must be stripped to avoid duplication with the template");
        assertTrue(out.contains("## Output Format"),
                "Template still appends the canonical Output Format section");
    }

    @Test
    void nativeMode_unmigratedPromptWithMarkers_stripsBlockAndAppendsHint() {
        String out = new SystemPromptAssembler().assemble(BASE_WITH_MARKERS, catalog,
                ToolingMode.NATIVE, PromptKind.ISSUE_AGENT);

        assertFalse(out.contains(SystemPromptAssembler.BEGIN_MARKER));
        assertFalse(out.contains("## Output Format"));
        assertFalse(out.contains("runTools"));
        assertTrue(out.contains("native function-calling API"));
    }

    @Test
    void writerKind_loadsWriterTemplate() {
        String legacyOut = new SystemPromptAssembler().assemble("You are a writer.", catalog,
                ToolingMode.LEGACY, PromptKind.WRITER_AGENT);
        assertTrue(legacyOut.contains("Reasoning tools:"),
                "Writer LEGACY template must be loaded for WRITER_AGENT");
        assertTrue(legacyOut.contains("Available MCP tools"));

        String nativeOut = new SystemPromptAssembler().assemble("You are a writer.", catalog,
                ToolingMode.NATIVE, PromptKind.WRITER_AGENT);
        assertTrue(nativeOut.contains("native function-calling API"));
        assertFalse(nativeOut.contains("Available MCP tools"));
    }

    @Test
    void nullPromptReturnsEmptyString() {
        assertEquals("", new SystemPromptAssembler().assemble(null, McpToolCatalog.empty(),
                ToolingMode.LEGACY, PromptKind.ISSUE_AGENT));
    }

    @Test
    void emptyCatalogProducesNoMcpFragmentInLegacyMode() {
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, McpToolCatalog.empty(),
                ToolingMode.LEGACY, PromptKind.ISSUE_AGENT);
        assertFalse(out.contains("Available MCP tools"));
    }

    @Test
    void deprecated3ArgOverloadStillWorks() {
        @SuppressWarnings("deprecation")
        String out = new SystemPromptAssembler().assemble(CLEAN_BASE, catalog, ToolingMode.LEGACY);
        assertTrue(out.contains("## Output Format"),
                "3-arg overload must default to ISSUE_AGENT and apply the same logic");
    }
}
