package org.remus.giteabot.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolPromptRendererTest {

    @Test
    void render_addsQualifiedMcpToolNamesAndJsonArgumentContract() {
        McpToolCatalog catalog = new McpToolCatalog(List.of(
                new McpToolDefinition(
                        "github",
                        "search_repositories",
                        "Search repositories",
                        "Search GitHub repositories",
                        Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))),
                        "mcp:github:search_repositories")
        ));

        String rendered = new McpToolPromptRenderer().render(catalog);

        assertTrue(rendered.contains("Available MCP tools"));
        assertTrue(rendered.contains("`mcp:github:search_repositories`"));
        assertTrue(rendered.contains("one JSON object as the first arg"));
        assertTrue(rendered.contains("Search GitHub repositories"));
    }

    @Test
    void render_emptyCatalogReturnsEmptyPromptFragment() {
        assertEquals("", new McpToolPromptRenderer().render(McpToolCatalog.empty()));
    }
}

