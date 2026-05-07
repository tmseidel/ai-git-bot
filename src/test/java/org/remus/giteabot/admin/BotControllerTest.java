package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.systemsettings.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotControllerTest {

    @Test
    void selectedMcpTools_missingConfiguration_returnsNotFound() {
        BotService botService = mock(BotService.class);
        AiIntegrationService aiIntegrationService = mock(AiIntegrationService.class);
        GitIntegrationService gitIntegrationService = mock(GitIntegrationService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        BotController controller = new BotController(botService, aiIntegrationService, gitIntegrationService,
                systemPromptService, mcpConfigurationService, mcpToolSelectionService);
        when(mcpConfigurationService.findById(55L)).thenReturn(Optional.empty());

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedMcpTools(55L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void selectedMcpTools_existingConfiguration_returnsRows() {
        BotService botService = mock(BotService.class);
        AiIntegrationService aiIntegrationService = mock(AiIntegrationService.class);
        GitIntegrationService gitIntegrationService = mock(GitIntegrationService.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        BotController controller = new BotController(botService, aiIntegrationService, gitIntegrationService,
                systemPromptService, mcpConfigurationService, mcpToolSelectionService);
        McpConfiguration config = new McpConfiguration();
        config.setId(7L);
        when(mcpConfigurationService.findById(7L)).thenReturn(Optional.of(config));
        when(mcpToolSelectionService.loadSelectedTools(7L)).thenReturn(List.of(
                new McpToolSelectionRow("mcp:github:get_file", "github", "get_file", "Get file", "Read file", true)
        ));

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedMcpTools(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("mcp:github:get_file", response.getBody().getFirst().get("qualifiedName"));
    }
}


