package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemSettingsControllerTest {

    @Test
    void preview_missingPrompt_returnsNotFound() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        when(systemPromptService.findById(99L)).thenReturn(Optional.empty());
        SystemSettingsController controller = new SystemSettingsController(systemPromptService,
                mcpConfigurationService, mcpToolSelectionService);

        assertEquals(404, controller.preview(99L).getStatusCode().value());
    }

    @Test
    void saveMcp_redirectsToToolSelectionAfterSave() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        SystemSettingsController controller = new SystemSettingsController(systemPromptService,
                mcpConfigurationService, mcpToolSelectionService);
        McpConfiguration config = new McpConfiguration();
        config.setName("GitHub");
        config.setJsonContent("{\"url\":\"https://example.test/mcp\"}");
        McpConfiguration saved = new McpConfiguration();
        saved.setId(5L);
        when(mcpConfigurationService.save(config)).thenReturn(saved);

        String view = controller.saveMcp(config, new ConcurrentModel(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/mcp-configurations/5/tools", view);
    }

    @Test
    void saveMcpToolSelection_persistsSelectionAndRedirects() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        SystemSettingsController controller = new SystemSettingsController(systemPromptService,
                mcpConfigurationService, mcpToolSelectionService);

        String view = controller.saveMcpToolSelection(7L,
                List.of("mcp:github:search", "mcp:github:get_file"),
                new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(mcpToolSelectionService).saveSelection(7L, List.of("mcp:github:search", "mcp:github:get_file"));
    }
}
