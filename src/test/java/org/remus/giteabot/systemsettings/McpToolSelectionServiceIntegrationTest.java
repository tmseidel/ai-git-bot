package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link McpToolSelectionService#saveSelection} against the real H2
 * schema. Regression test for the unique-constraint violation on uk_mcp_selected_tool that
 * occurred when re-saving a selection still containing already persisted tools: with
 * GenerationType.IDENTITY the inserts from saveAll execute immediately, before the queued
 * deletes, unless the delete is flushed first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpToolSelectionServiceIntegrationTest {

    @Autowired
    private McpToolSelectionService service;

    @Autowired
    private McpConfigurationRepository mcpConfigurationRepository;

    @Autowired
    private McpSelectedToolRepository mcpSelectedToolRepository;

    @MockitoBean
    private McpOrchestrationService mcpOrchestrationService;

    @Test
    void ordinarySelectionDoesNotApproveButExplicitApprovalSurvivesReplacement() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setName("itest-mcp-explicit-approval");
        configuration.setJsonContent("[{\"name\":\"github\",\"url\":\"https://example.test/mcp\"}]");
        Long id = mcpConfigurationRepository.save(configuration).getId();
        String name = "mcp:github:get_commit";
        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(new McpToolCatalog(List.of(
                new McpToolDefinition("github", "get_commit", "get_commit", "desc", Map.of(), name))));

        service.saveSelection(id, List.of(name));
        assertTrue(service.readReviewReadOnlyApprovals(configuration,
                mcpOrchestrationService.discoverTools(configuration)).isEmpty());

        service.saveSelection(id, List.of(name), List.of(name), List.of(), service.loadSelectionSnapshot(id).approvalCandidates());
        var approved = service.readReviewReadOnlyApprovals(configuration, mcpOrchestrationService.discoverTools(configuration));
        assertEquals(1, approved.size());
        service.saveSelection(id, List.of(name));
        assertEquals(approved, service.readReviewReadOnlyApprovals(configuration,
                mcpOrchestrationService.discoverTools(configuration)));
    }

    @Test
    void saveSelection_savingAgainWithOverlappingSelection_persistsWithoutUniqueViolation() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setName("itest-mcp-tool-selection");
        configuration.setJsonContent("[{\"name\":\"github\",\"url\":\"https://example.test/mcp\"}]");
        mcpConfigurationRepository.save(configuration);
        Long id = configuration.getId();

        when(mcpOrchestrationService.discoverTools(any(McpConfiguration.class))).thenReturn(new McpToolCatalog(List.of(
                new McpToolDefinition("github", "get_commit", "get_commit", "desc", Map.of(), "mcp:github:get_commit"),
                new McpToolDefinition("github", "list_issues", "list_issues", "desc", Map.of(), "mcp:github:list_issues")
        )));

        service.saveSelection(id, List.of("mcp:github:get_commit"));
        // Second save keeps an already persisted tool and adds a new one - this previously
        // failed with a unique constraint violation on uk_mcp_selected_tool.
        service.saveSelection(id, List.of("mcp:github:get_commit", "mcp:github:list_issues"));

        List<McpSelectedTool> persisted = mcpSelectedToolRepository.findByMcpConfigurationId(id);
        assertEquals(2, persisted.size());
        assertTrue(persisted.stream().anyMatch(tool -> tool.getQualifiedName().equals("mcp:github:get_commit")));
        assertTrue(persisted.stream().anyMatch(tool -> tool.getQualifiedName().equals("mcp:github:list_issues")));
    }
}
