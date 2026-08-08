package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.EncryptionService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpConfigurationServiceTest {

    @Mock
    private McpConfigurationRepository mcpConfigurationRepository;

    @Mock
    private BotRepository botRepository;

    /** Real encryption with a test key so save() encrypts the JSON content. */
    @Spy
    private EncryptionService encryptionService = new EncryptionService("test-key");

    @InjectMocks
    private McpConfigurationService mcpConfigurationService;

    @Test
    void save_rejectsInvalidJson() {
        McpConfiguration mcpConfiguration = configuration("{not json}");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mcpConfigurationService.save(mcpConfiguration));

        assertEquals("MCP configuration must be valid JSON", exception.getMessage());
        verify(mcpConfigurationRepository, never()).save(any());
    }

    @Test
    void save_rejectsStdioTransport() {
        McpConfiguration mcpConfiguration = configuration("""
                {"name":"local","transport":"stdio","command":"github-mcp-server"}
                """);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mcpConfigurationService.save(mcpConfiguration));

        assertEquals("stdio MCP transport is not supported", exception.getMessage());
        verify(mcpConfigurationRepository, never()).save(any());
    }

    @Test
    void save_acceptsRemoteTransport() {
        String jsonContent = """
                {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/"}
                """;
        McpConfiguration mcpConfiguration = configuration(jsonContent);
        when(mcpConfigurationRepository.save(mcpConfiguration)).thenReturn(mcpConfiguration);

        McpConfiguration result = mcpConfigurationService.save(mcpConfiguration);

        assertSame(mcpConfiguration, result);
        assertNotEquals(jsonContent, result.getJsonContent());
        assertEquals(jsonContent, mcpConfigurationService.getDecryptedJsonContent(result));
        assertEquals(jsonContent, mcpConfigurationService.decryptedView(result).getJsonContent());
        verify(mcpConfigurationRepository).save(mcpConfiguration);
    }

    @Test
    void deleteById_configurationUsedByBots_throwsWithBotNames() {
        McpConfiguration mcpConfiguration = configuration("""
                {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/"}
                """);
        mcpConfiguration.setId(1L);
        Bot bot = new Bot();
        bot.setName("Review Bot");
        when(mcpConfigurationRepository.findById(1L)).thenReturn(Optional.of(mcpConfiguration));
        when(botRepository.findByMcpConfigurationId(1L)).thenReturn(List.of(bot));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> mcpConfigurationService.deleteById(1L));

        assertTrue(exception.getMessage().contains("Review Bot"));
        verify(mcpConfigurationRepository, never()).delete(any());
    }

    private McpConfiguration configuration(String jsonContent) {
        McpConfiguration mcpConfiguration = new McpConfiguration();
        mcpConfiguration.setName("GitHub MCP");
        mcpConfiguration.setJsonContent(jsonContent);
        return mcpConfiguration;
    }
}
