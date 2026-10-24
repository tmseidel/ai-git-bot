package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class McpToolSelectionServiceTest {

    @Autowired
    private McpConfigurationRepository mcpConfigurationRepository;

    @Autowired
    private McpSelectedToolRepository mcpSelectedToolRepository;

    @MockitoBean
    private McpOrchestrationService mcpOrchestrationService;

    @Autowired
    private McpToolSelectionService service;

    @Test
    void loadAvailableTools_marksPersistedEntriesAsSelected() {
        McpConfiguration configuration = configuration();
        when(mcpOrchestrationService.discoverTools(configuration)).thenReturn(new McpToolCatalog(List.of(
                tool("mcp:github:search_repositories", "github", "search_repositories"),
                tool("mcp:docs:search", "docs", "search")
        )));

        McpSelectedTool selected = new McpSelectedTool();
        selected.setQualifiedName("mcp:docs:search");
        selected.setServerName("docs");
        selected.setToolName("search");
        selected.setMcpConfiguration(configuration);
        mcpSelectedToolRepository.save(selected);

        List<McpToolSelectionRow> rows = service.loadAvailableTools(configuration.getId());

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(row -> row.qualifiedName().equals("mcp:docs:search") && row.selected()));
        assertTrue(rows.stream().anyMatch(row -> row.qualifiedName().equals("mcp:github:search_repositories") && !row.selected()));
    }

    @Test
    void filterCatalogForPrompt_returnsOnlySelectedTools() {
        McpConfiguration configuration = configuration();
        McpSelectedTool selected = selectedTool("mcp:github:get_file");
        selected.setMcpConfiguration(configuration);
        mcpSelectedToolRepository.save(selected);
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
        McpConfiguration configuration = configuration();
        when(mcpOrchestrationService.discoverTools(configuration)).thenReturn(new McpToolCatalog(List.of(
                tool("mcp:github:search", "github", "search")
        )));

        service.saveSelection(configuration.getId(), List.of("mcp:github:search"));

        assertEquals(List.of("mcp:github:search"), service.loadSelectedTools(configuration.getId()).stream()
                .map(McpToolSelectionRow::qualifiedName).toList());
        assertTrue(service.readReviewReadOnlyApprovals(configuration,
                mcpOrchestrationService.discoverTools(configuration)).isEmpty());
    }

    private McpConfiguration configuration() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setName("Config");
        configuration.setJsonContent("[{\"name\":\"github\",\"url\":\"https://example.test/mcp\"}]");
        return mcpConfigurationRepository.save(configuration);
    }

    @Test
    void legacySixArgumentRowConstructorDoesNotGrantApproval() {
        McpToolSelectionRow row = new McpToolSelectionRow("mcp:docs:read", "docs", "read", "Read", "Read files", true);
        assertTrue(row.selected());
        org.junit.jupiter.api.Assertions.assertFalse(row.reviewReadOnlyApproved());
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
