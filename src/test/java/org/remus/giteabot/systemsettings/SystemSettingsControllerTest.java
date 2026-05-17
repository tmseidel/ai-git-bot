package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemSettingsControllerTest {

    private SystemSettingsController newController(SystemPromptService systemPromptService,
                                                   McpConfigurationService mcpConfigurationService,
                                                   McpToolSelectionService mcpToolSelectionService,
                                                   BotToolConfigurationService botToolConfigurationService,
                                                   BotToolSelectionService botToolSelectionService) {
        return new SystemSettingsController(systemPromptService, mcpConfigurationService,
                mcpToolSelectionService, botToolConfigurationService, botToolSelectionService);
    }

    @Test
    void preview_missingPrompt_returnsNotFound() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        when(systemPromptService.findById(99L)).thenReturn(Optional.empty());
        SystemSettingsController controller = newController(systemPromptService,
                mock(McpConfigurationService.class), mock(McpToolSelectionService.class),
                mock(BotToolConfigurationService.class), mock(BotToolSelectionService.class));

        assertEquals(404, controller.preview(99L).getStatusCode().value());
    }

    @Test
    void saveMcp_redirectsToToolSelectionAfterSave() {
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mcpConfigurationService, mock(McpToolSelectionService.class),
                mock(BotToolConfigurationService.class), mock(BotToolSelectionService.class));
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
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mock(McpConfigurationService.class), mcpToolSelectionService,
                mock(BotToolConfigurationService.class), mock(BotToolSelectionService.class));

        String view = controller.saveMcpToolSelection(7L,
                List.of("mcp:github:search", "mcp:github:get_file"),
                new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(mcpToolSelectionService).saveSelection(7L, List.of("mcp:github:search", "mcp:github:get_file"));
    }

    // ---- Bot Tool Configurations ----

    @Test
    void saveBotTool_redirectsToToolSelectionAfterSave() {
        BotToolConfigurationService botToolConfigurationService = mock(BotToolConfigurationService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mock(McpConfigurationService.class), mock(McpToolSelectionService.class),
                botToolConfigurationService, mock(BotToolSelectionService.class));
        BotToolConfiguration input = new BotToolConfiguration();
        input.setName("Java");
        BotToolConfiguration saved = new BotToolConfiguration();
        saved.setId(11L);
        when(botToolConfigurationService.save(input)).thenReturn(saved);

        String view = controller.saveBotTool(input, new ConcurrentModel(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/bot-tools/11/tools", view);
    }

    @Test
    void saveBotTool_serviceError_rendersFormWithError() {
        BotToolConfigurationService botToolConfigurationService = mock(BotToolConfigurationService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mock(McpConfigurationService.class), mock(McpToolSelectionService.class),
                botToolConfigurationService, mock(BotToolSelectionService.class));
        BotToolConfiguration input = new BotToolConfiguration();
        input.setName("dup");
        when(botToolConfigurationService.save(input)).thenThrow(new IllegalArgumentException("duplicate"));

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.saveBotTool(input, model, new RedirectAttributesModelMap());

        assertEquals("system-settings/bot-tools-form", view);
        assertTrue(String.valueOf(model.getAttribute("error")).contains("duplicate"));
    }

    @Test
    void saveBotToolSelection_persistsAndRedirects() {
        BotToolSelectionService botToolSelectionService = mock(BotToolSelectionService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mock(McpConfigurationService.class), mock(McpToolSelectionService.class),
                mock(BotToolConfigurationService.class), botToolSelectionService);

        String view = controller.saveBotToolSelection(8L, List.of("mvn", "cat"),
                new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(botToolSelectionService).saveSelection(8L, List.of("mvn", "cat"));
    }

    @Test
    void saveBotToolSelection_serviceError_redirectsBackToEditor() {
        BotToolSelectionService botToolSelectionService = mock(BotToolSelectionService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mock(McpConfigurationService.class), mock(McpToolSelectionService.class),
                mock(BotToolConfigurationService.class), botToolSelectionService);
        org.mockito.Mockito.doThrow(new IllegalStateException("default config"))
                .when(botToolSelectionService).saveSelection(any(), any());

        String view = controller.saveBotToolSelection(1L, List.of("mvn"),
                new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/bot-tools/1/tools", view);
    }

    @Test
    void deleteBotTool_propagatesServiceErrorAsFlash() {
        BotToolConfigurationService botToolConfigurationService = mock(BotToolConfigurationService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mock(McpConfigurationService.class), mock(McpToolSelectionService.class),
                botToolConfigurationService, mock(BotToolSelectionService.class));
        org.mockito.Mockito.doThrow(new IllegalStateException("used by Bot X"))
                .when(botToolConfigurationService).deleteById(5L);

        String view = controller.deleteBotTool(5L, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(botToolConfigurationService).deleteById(5L);
    }

    @Test
    void cloneBotTool_unknownId_redirectsWithError() {
        BotToolConfigurationService botToolConfigurationService = mock(BotToolConfigurationService.class);
        SystemSettingsController controller = newController(mock(SystemPromptService.class),
                mock(McpConfigurationService.class), mock(McpToolSelectionService.class),
                botToolConfigurationService, mock(BotToolSelectionService.class));
        when(botToolConfigurationService.cloneConfiguration(42L))
                .thenThrow(new IllegalArgumentException("Tool configuration not found"));

        String view = controller.cloneBotToolForm(42L, new ConcurrentModel(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
    }
}
