package org.remus.giteabot.event;

import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.RepositoryUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Builds {@link BotAccountResolver}s for translator tests, whose platforms report a fixed bot account. */
public final class TestBotAccounts {

    private TestBotAccounts() {
    }

    /** The account a platform reports for the bot {@code username}. */
    public static RepositoryUser user(String username) {
        return new RepositoryUser("id-" + username, username, null, null);
    }

    /** A resolver whose platform reports the account {@code username} for every integration. */
    public static BotAccountResolver resolver(String username) {
        return resolver(user(username), mock(RepositoryPlatformFactory.class), mock(BotService.class));
    }

    /**
     * A resolver whose platform reports {@code user} for every integration, stubbed on the
     * given {@code platformFactory}, for tests that also stub or verify it or the {@code botService}.
     */
    public static BotAccountResolver resolver(RepositoryUser user, RepositoryPlatformFactory platformFactory,
                                              BotService botService) {
        RepositoryPlatform platform = mock(RepositoryPlatform.class);
        lenient().when(platform.getUser(nullable(String.class))).thenReturn(user);
        lenient().when(platformFactory.getPlatform(any())).thenReturn(platform);
        return new BotAccountResolver(platformFactory, botService);
    }

    /** A resolver whose platform cannot tell the bot's account: every lookup fails with {@code error}. */
    public static BotAccountResolver unresolvable(String error) {
        return unresolvable(error, mock(BotService.class));
    }

    /**
     * A resolver whose platform cannot tell the bot's account: every lookup fails with
     * {@code error}, which the resolver records on the given {@code botService}.
     */
    public static BotAccountResolver unresolvable(String error, BotService botService) {
        RepositoryPlatform platform = mock(RepositoryPlatform.class);
        lenient().when(platform.getUser(nullable(String.class))).thenThrow(new IllegalStateException(error));
        RepositoryPlatformFactory platformFactory = mock(RepositoryPlatformFactory.class);
        lenient().when(platformFactory.getPlatform(any())).thenReturn(platform);
        return new BotAccountResolver(platformFactory, botService);
    }
}
