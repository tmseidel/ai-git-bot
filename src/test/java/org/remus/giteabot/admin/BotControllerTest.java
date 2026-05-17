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

    private BotController newController(BotService botService,
                                        McpConfigurationService mcpConfigurationService,
                                        McpToolSelectionService mcpToolSelectionService,
                                        BotToolConfigurationService botToolConfigurationService,
                                        BotToolSelectionService botToolSelectionService) {
        return new BotController(
                botService,
                mock(AiIntegrationService.class),
                mock(GitIntegrationService.class),
                mock(SystemPromptService.class),
                mcpConfigurationService,
                mcpToolSelectionService,
                botToolConfigurationService,
                botToolSelectionService);
    }

    @Test
    void selectedMcpTools_missingConfiguration_returnsNotFound() {
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mcpConfigurationService,
                mcpToolSelectionService, mock(BotToolConfigurationService.class), mock(BotToolSelectionService.class));
        when(mcpConfigurationService.findById(55L)).thenReturn(Optional.empty());

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedMcpTools(55L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void selectedMcpTools_existingConfiguration_returnsRows() {
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mcpConfigurationService,
                mcpToolSelectionService, mock(BotToolConfigurationService.class), mock(BotToolSelectionService.class));
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

    @Test
    void selectedBuiltinTools_missingConfiguration_returnsNotFound() {
        BotToolConfigurationService toolConfigurationService = mock(BotToolConfigurationService.class);
        BotToolSelectionService toolSelectionService = mock(BotToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mock(McpConfigurationService.class),
                mock(McpToolSelectionService.class), toolConfigurationService, toolSelectionService);
        when(toolConfigurationService.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedBuiltinTools(99L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void selectedBuiltinTools_existingConfiguration_returnsRows() {
        BotToolConfigurationService toolConfigurationService = mock(BotToolConfigurationService.class);
        BotToolSelectionService toolSelectionService = mock(BotToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mock(McpConfigurationService.class),
                mock(McpToolSelectionService.class), toolConfigurationService, toolSelectionService);
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setId(8L);
        when(toolConfigurationService.findById(8L)).thenReturn(Optional.of(configuration));
        when(toolSelectionService.loadSelectedTools(8L)).thenReturn(List.of(
                new BotToolSelectionRow("mvn", "VALIDATION", "Run Maven", true)
        ));

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedBuiltinTools(8L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("mvn", response.getBody().getFirst().get("toolName"));
        assertEquals("VALIDATION", response.getBody().getFirst().get("toolKind"));
    }
}