package org.remus.giteabot.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.RepositoryUser;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotAccountResolverTest {

    @Mock private RepositoryPlatformFactory platformFactory;
    @Mock private RepositoryPlatform platform;
    @Mock private BotService botService;

    private BotAccountResolver resolver;
    private Bot bot;

    @BeforeEach
    void setUp() {
        resolver = new BotAccountResolver(platformFactory, botService);
        GitIntegration integration = new GitIntegration();
        integration.setName("contoso-devops");
        bot = new Bot();
        bot.setName("reviewer");
        bot.setGitIntegration(integration);
    }

    @Test
    void resolve_returnsTheAccountOfTheBotsIntegration() {
        RepositoryUser account = new RepositoryUser("42", "ai_bot", null, null);
        when(platformFactory.getPlatform(bot.getGitIntegration())).thenReturn(platform);
        when(platform.getUser("contoso")).thenReturn(account);

        assertSame(account, resolver.resolve(bot, "contoso"));
        verifyNoInteractions(botService);
    }

    @Test
    void resolve_accountLookupFails_returnsNullAndRecordsTheBotsError() {
        when(platformFactory.getPlatform(bot.getGitIntegration())).thenReturn(platform);
        when(platform.getUser(any())).thenThrow(new IllegalStateException("401 Unauthorized"));

        assertNull(resolver.resolve(bot, null));
        verify(botService).recordError(eq(bot), contains("'contoso-devops': 401 Unauthorized"));
    }

    @Test
    void resolve_platformCannotBeBuilt_returnsNullAndRecordsTheBotsError() {
        when(platformFactory.getPlatform(bot.getGitIntegration()))
                .thenThrow(new IllegalArgumentException("unsupported provider"));

        assertNull(resolver.resolve(bot, null));
        verify(botService).recordError(eq(bot), contains("unsupported provider"));
    }
}
