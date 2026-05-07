package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolSelectionServiceTest {

    @Mock
    private McpConfigurationRepository mcpConfigurationRepository;

    @Mock
    private McpSelectedToolRepository mcpSelectedToolRepository;

    @Mock
    private McpOrchestrationService mcpOrchestrationService;

    @InjectMocks
    private McpToolSelectionService service;

    @Test
    void loadAvailableTools_marksPersistedEntriesAsSelected() {
        McpConfiguration configuration = configuration(3L);
        when(mcpConfigurationRepository.findById(3L)).thenReturn(Optional.of(configuration));
        when(mcpOrchestrationService.discoverTools(configuration)).thenReturn(new McpToolCatalog(List.of(
                tool("mcp:github:search_repositories", "github", "search_repositories"),
                tool("mcp:docs:search", "docs", "search")
        )));

        McpSelectedTool selected = new McpSelectedTool();
        selected.setQualifiedName("mcp:docs:search");
        selected.setServerName("docs");
        selected.setToolName("search");
        when(mcpSelectedToolRepository.findByMcpConfigurationId(3L)).thenReturn(List.of(selected));

        List<McpToolSelectionRow> rows = service.loadAvailableTools(3L);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(row -> row.qualifiedName().equals("mcp:docs:search") && row.selected()));
        assertTrue(rows.stream().anyMatch(row -> row.qualifiedName().equals("mcp:github:search_repositories") && !row.selected()));
    }

    @Test
    void filterCatalogForPrompt_returnsOnlySelectedTools() {
        McpConfiguration configuration = configuration(4L);
        when(mcpSelectedToolRepository.findByMcpConfigurationId(4L)).thenReturn(List.of(selectedTool("mcp:github:get_file")));
        McpToolCatalog catalog = new McpToolCatalog(List.of(
                tool("mcp:github:get_file", "github", "get_file"),
                tool("mcp:github:list_issues", "github", "list_issues")
        ));

        McpToolCatalog filtered = service.filterCatalogForPrompt(configuration, catalog);

        assertEquals(1, filtered.tools().size());
        assertEquals("mcp:github:get_file", filtered.tools().getFirst().qualifiedName());
    }

    @Test
    void saveSelection_replacesPersistedSelection() {
        McpConfiguration configuration = configuration(11L);
        when(mcpConfigurationRepository.findById(11L)).thenReturn(Optional.of(configuration));
        when(mcpOrchestrationService.discoverTools(configuration)).thenReturn(new McpToolCatalog(List.of(
                tool("mcp:github:search", "github", "search")
        )));
        when(mcpSelectedToolRepository.findByMcpConfigurationId(11L)).thenReturn(List.of());

        service.saveSelection(11L, List.of("mcp:github:search"));

        verify(mcpSelectedToolRepository).deleteByMcpConfigurationId(11L);
        verify(mcpSelectedToolRepository).saveAll(any());
    }

    private McpConfiguration configuration(Long id) {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setId(id);
        configuration.setName("Config");
        configuration.setJsonContent("[{\"name\":\"github\",\"url\":\"https://example.test/mcp\"}]");
        return configuration;
    }

    private McpToolDefinition tool(String qualifiedName, String serverName, String name) {
        return new McpToolDefinition(serverName, name, name, "desc", Map.of(), qualifiedName);
    }

    private McpSelectedTool selectedTool(String qualifiedName) {
        McpSelectedTool tool = new McpSelectedTool();
        tool.setQualifiedName(qualifiedName);
        tool.setServerName("github");
        tool.setToolName("get_file");
        return tool;
    }
}

