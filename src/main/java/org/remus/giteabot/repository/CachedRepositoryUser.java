package org.remus.giteabot.repository;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.model.RepositoryUser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * The account a platform's token authenticates as, resolved on first use. The account is the
 * same wherever it is asked, so one cache serves every owner: the owner passed in only says where
 * to ask when nothing valid is cached.
 * <p>
 * The display name and e-mail can be changed on the provider, so the result expires after a TTL.
 * When a refresh fails the expired user keeps being served for {@link #RETRY_DELAY} before the
 * provider is asked again, so a provider outage neither makes the bot forget who it is nor makes
 * every caller wait for the provider's timeout.
 */
@Slf4j
final class CachedRepositoryUser {

    static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final Function<String, RepositoryUser> lookup;
    private final Duration ttl;
    private final Clock clock;

    private volatile Entry entry;

    CachedRepositoryUser(Function<String, RepositoryUser> lookup, Duration ttl, Clock clock) {
        this.lookup = lookup;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * Returns the account, asking the provider when nothing valid is cached.
     *
     * @param owner where to ask the provider, see {@link RepositoryApiClient#getAuthenticatedUser(String)}
     * @throws IllegalStateException when the provider cannot be asked and nothing is cached
     */
    RepositoryUser get(String owner) {
        Instant now = clock.instant();
        Entry cached = entry;
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.user();
        }
        try {
            RepositoryUser user = lookup.apply(owner);
            entry = new Entry(user, now.plus(ttl));
            return user;
        } catch (RuntimeException e) {
            if (cached != null) {
                log.warn("Could not refresh the Git user, keeping '{}': {}", cached.user().username(), e.getMessage());
                entry = new Entry(cached.user(), now.plus(RETRY_DELAY));
                return cached.user();
            }
            throw new IllegalStateException("Could not determine the Git user: " + e.getMessage(), e);
        }
    }

    private record Entry(RepositoryUser user, Instant expiresAt) {
    }
}
