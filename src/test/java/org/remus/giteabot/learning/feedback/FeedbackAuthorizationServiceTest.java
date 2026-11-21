package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedbackAuthorizationServiceTest {

    private final BotService botService = mock(BotService.class);
    private final FeedbackAuthorizationService authorization =
            new FeedbackAuthorizationService(botService);

    @Test
    void anEmptyWhitelistAcceptsEveryCommenterForCollection() {
        when(botService.getAllowedUsernames(any())).thenReturn(Set.of());

        assertThat(authorization.canContribute(new Bot(), "anyone")).isTrue();
    }

    @Test
    void aConfiguredWhitelistDecidesWithoutRequiringThePullRequestAuthor() {
        Bot bot = new Bot();
        when(botService.getAllowedUsernames(bot)).thenReturn(Set.of("alice"));
        when(botService.isUsernameInSet(Set.of("alice"), "alice")).thenReturn(true);
        when(botService.isUsernameInSet(Set.of("alice"), "mallory")).thenReturn(false);

        assertThat(authorization.canContribute(bot, "alice")).isTrue();
        assertThat(authorization.canContribute(bot, " mallory ")).isFalse();
    }

    @Test
    void anUnknownActorOrBotIsNeverPermitted() {
        assertThat(authorization.canContribute(new Bot(), null)).isFalse();
        assertThat(authorization.canContribute(new Bot(), "  ")).isFalse();
        assertThat(authorization.canContribute(null, "alice")).isFalse();
    }
}
