package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotServiceTest {

    @Mock
    private BotRepository botRepository;

    @Mock
    private BotToolConfigurationRepository botToolConfigurationRepository;

    @InjectMocks
    private BotService botService;

    @Test
    void save_generatesWebhookSecret_whenNull() {
        Bot bot = new Bot();
        bot.setWebhookSecret(null);
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertNotNull(result.getWebhookSecret());
        // UUID format: 8-4-4-4-12 hex characters
        assertTrue(result.getWebhookSecret().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        verify(botRepository).save(bot);
    }

    @Test
    void save_keepsExistingWebhookSecret() {
        Bot bot = new Bot();
        bot.setWebhookSecret("existing-secret");
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertEquals("existing-secret", result.getWebhookSecret());
    }

    @Test
    void save_writerBotDisablesCodingAgentCheckbox() {
        Bot bot = new Bot();
        bot.setWebhookSecret("existing-secret");
        bot.setBotType(BotType.WRITER);
        bot.setAgentEnabled(true);
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertFalse(result.isAgentEnabled());
    }

    @Test
    void incrementWebhookCallCount_incrementsAndSetsTimestamp() {
        Bot bot = new Bot();
        bot.setWebhookCallCount(5);
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        botService.incrementWebhookCallCount(bot);

        assertEquals(6, bot.getWebhookCallCount());
        assertNotNull(bot.getLastWebhookAt());
        verify(botRepository).save(bot);
    }

    @Test
    void recordError_setsErrorInfo() {
        Bot bot = new Bot();
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        botService.recordError(bot, "Something went wrong");

        assertEquals("Something went wrong", bot.getLastErrorMessage());
        assertNotNull(bot.getLastErrorAt());
        verify(botRepository).save(bot);
    }

    @Test
    void findByWebhookSecret_delegatesToRepository() {
        Bot bot = new Bot();
        bot.setWebhookSecret("test-secret");
        when(botRepository.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        Optional<Bot> result = botService.findByWebhookSecret("test-secret");

        assertTrue(result.isPresent());
        assertEquals("test-secret", result.get().getWebhookSecret());
        verify(botRepository).findByWebhookSecret("test-secret");
    }
}
