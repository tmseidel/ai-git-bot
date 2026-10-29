package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.GitUser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitUserServiceTest {

    private static final Instant START = Instant.parse("2026-10-27T10:00:00Z");
    private static final GitUser BOT = new GitUser("17", "ai_bot", "AI Bot", "bot@example.com");

    @Mock private RepositoryApiClientFactory repositoryApiClientFactory;
    @Mock private RepositoryApiClient client;

    private final MutableClock clock = new MutableClock(START);
    private GitUserService service;
    private GitIntegration integration;

    @BeforeEach
    void setUp() {
        service = new GitUserService(repositoryApiClientFactory, Duration.ofMinutes(10), clock);
        integration = new GitIntegration();
        integration.setId(1L);
        integration.setName("gitea");
        integration.setUpdatedAt(START);
        when(repositoryApiClientFactory.getApiClient(integration)).thenReturn(client);
    }

    @Test
    void cachesTheUserUntilTheTtlExpires() {
        when(client.getAuthenticatedUser()).thenReturn(BOT, new GitUser("17", "renamed", null, null));

        assertEquals(BOT, service.getGitUser(integration));
        clock.advance(Duration.ofMinutes(9));
        assertEquals(BOT, service.getGitUser(integration));
        verify(client, times(1)).getAuthenticatedUser();

        clock.advance(Duration.ofMinutes(2));
        assertEquals("renamed", service.getGitUser(integration).username());
        verify(client, times(2)).getAuthenticatedUser();
    }

    @Test
    void editingTheIntegrationDropsTheCachedUser() {
        when(client.getAuthenticatedUser()).thenReturn(BOT, new GitUser("18", "other_bot", null, null));
        service.getGitUser(integration);

        integration.setUpdatedAt(START.plusSeconds(1));

        assertEquals("other_bot", service.getGitUser(integration).username());
    }

    @Test
    void keepsServingTheExpiredUserWhenTheRefreshFails() {
        when(client.getAuthenticatedUser()).thenReturn(BOT).thenThrow(new IllegalStateException("503"));
        service.getGitUser(integration);
        clock.advance(Duration.ofMinutes(11));

        assertEquals(BOT, service.getGitUser(integration));
    }

    @Test
    void waitsTheRetryDelayBeforeAskingTheProviderAgainAfterAFailedRefresh() {
        when(client.getAuthenticatedUser()).thenReturn(BOT).thenThrow(new IllegalStateException("503"));
        service.getGitUser(integration);
        clock.advance(Duration.ofMinutes(11));
        service.getGitUser(integration);

        clock.advance(GitUserService.RETRY_DELAY.minusSeconds(1));
        assertEquals(BOT, service.getGitUser(integration));
        verify(client, times(2)).getAuthenticatedUser();

        clock.advance(Duration.ofSeconds(1));
        assertEquals(BOT, service.getGitUser(integration));
        verify(client, times(3)).getAuthenticatedUser();
    }

    @Test
    void comparesAMissingUpdatedAtWithoutFailing() {
        integration.setUpdatedAt(null);
        when(client.getAuthenticatedUser()).thenReturn(BOT);
        service.getGitUser(integration);

        assertEquals(BOT, service.getGitUser(integration));
        verify(client, times(1)).getAuthenticatedUser();
    }

    @Test
    void failsWhenNothingIsCachedAndTheProviderCannotBeAsked() {
        when(client.getAuthenticatedUser()).thenThrow(new IllegalStateException("401 Unauthorized"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getGitUser(integration));

        assertEquals("Could not determine the Git user of integration 'gitea': 401 Unauthorized", error.getMessage());
    }

    @Test
    void getBotUsername_returnsTheUsernameOfTheBotsIntegration() {
        when(client.getAuthenticatedUser()).thenReturn(BOT);
        Bot bot = new Bot();
        bot.setGitIntegration(integration);

        assertEquals("ai_bot", service.getBotUsername(bot));
    }

    @Test
    void findCached_returnsAnExpiredUserWithoutAskingTheProviderButNotOneCachedBeforeAnEdit() {
        when(client.getAuthenticatedUser()).thenReturn(BOT);
        service.getGitUser(integration);
        clock.advance(Duration.ofMinutes(11));

        assertEquals(Optional.of(BOT), service.findCached(integration));
        verify(client, times(1)).getAuthenticatedUser();

        integration.setUpdatedAt(START.plusSeconds(1));
        assertTrue(service.findCached(integration).isEmpty());
    }

    @Test
    void findCached_matchesTheIntegrationReloadedWithTheDatabasesTimestampPrecision() {
        // Saving sets updatedAt with the clock's nanoseconds; the copy the next request
        // loads from the database only keeps microseconds.
        Instant savedAt = Instant.parse("2026-10-28T18:47:57.770996293Z");
        integration.setUpdatedAt(savedAt);
        when(client.getAuthenticatedUser()).thenReturn(BOT);
        service.getGitUser(integration);

        GitIntegration reloaded = new GitIntegration();
        reloaded.setId(1L);
        reloaded.setUpdatedAt(savedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));

        assertEquals(Optional.of(BOT), service.findCached(reloaded));
    }

    @Test
    void findCachedUsernames_leavesOutUnresolvedIntegrations() {
        when(client.getAuthenticatedUser()).thenReturn(BOT);
        service.getGitUser(integration);
        GitIntegration unresolved = new GitIntegration();
        unresolved.setId(2L);

        assertEquals(Map.of(1L, "ai_bot"), service.findCachedUsernames(List.of(integration, unresolved)));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
