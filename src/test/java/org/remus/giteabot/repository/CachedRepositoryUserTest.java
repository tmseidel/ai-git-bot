package org.remus.giteabot.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.model.RepositoryUser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachedRepositoryUserTest {

    private static final Instant START = Instant.parse("2026-10-27T10:00:00Z");
    private static final RepositoryUser BOT = new RepositoryUser("17", "ai_bot", "AI Bot", "bot@example.com");

    @Mock private RepositoryApiClient client;

    private final MutableClock clock = new MutableClock(START);
    private CachedRepositoryUser user;

    @BeforeEach
    void setUp() {
        user = new CachedRepositoryUser(client::getAuthenticatedUser, Duration.ofMinutes(10), clock);
    }

    @Test
    void cachesTheUserUntilTheTtlExpires() {
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT, new RepositoryUser("17", "renamed", null, "bot@example.com"));

        assertEquals(BOT, user.get("owner"));
        clock.advance(Duration.ofMinutes(9));
        assertEquals(BOT, user.get("owner"));
        verify(client, times(1)).getAuthenticatedUser("owner");

        clock.advance(Duration.ofMinutes(2));
        assertEquals("renamed", user.get("owner").username());
        verify(client, times(2)).getAuthenticatedUser("owner");
    }

    @Test
    void keepsServingTheExpiredUserWhenTheRefreshFails() {
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT).thenThrow(new IllegalStateException("503"));
        user.get("owner");
        clock.advance(Duration.ofMinutes(11));

        assertEquals(BOT, user.get("owner"));
    }

    @Test
    void waitsTheRetryDelayBeforeAskingTheProviderAgainAfterAFailedRefresh() {
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT).thenThrow(new IllegalStateException("503"));
        user.get("owner");
        clock.advance(Duration.ofMinutes(11));
        user.get("owner");

        clock.advance(CachedRepositoryUser.RETRY_DELAY.minusSeconds(1));
        assertEquals(BOT, user.get("owner"));
        verify(client, times(2)).getAuthenticatedUser("owner");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(BOT, user.get("owner"));
        verify(client, times(3)).getAuthenticatedUser("owner");
    }

    @Test
    void failsWhenNothingIsCachedAndTheProviderCannotBeAsked() {
        when(client.getAuthenticatedUser("owner")).thenThrow(new IllegalStateException("401 Unauthorized"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> user.get("owner"));

        assertEquals("Could not determine the Git user: 401 Unauthorized", error.getMessage());
    }

    @Test
    void platform_asksTheProviderOnlyWhenTheUserIsRead() {
        RepositoryPlatform platform = TestPlatforms.of(client);
        verifyNoInteractions(client);
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT);

        assertEquals(BOT, platform.getUser("owner"));
        assertEquals(BOT, platform.getUser("owner"));

        verify(client, times(1)).getAuthenticatedUser("owner");
    }

    @Test
    void platform_sharesOneUserAcrossOwners() {
        // The account is the same everywhere; only the first lookup decides where it is asked.
        when(client.getAuthenticatedUser("contoso")).thenReturn(BOT);
        RepositoryPlatform platform = TestPlatforms.of(client);

        assertEquals(BOT, platform.getUser("contoso"));
        assertEquals(BOT, platform.getUser("fabrikam"));

        verify(client, times(1)).getAuthenticatedUser(anyString());
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
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
