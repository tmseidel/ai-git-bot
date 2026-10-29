package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.model.GitUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the bot's identity on the Git provider: the account a {@link GitIntegration}'s token
 * authenticates as.
 * <p>
 * Results are cached for {@code giteabot.git-user.cache-ttl}, since the display name and e-mail
 * can be changed on the provider, and dropped as soon as the integration is edited.
 * When a refresh fails the expired entry keeps being served for {@link #RETRY_DELAY} before the
 * provider is asked again, so a provider outage neither makes the bot forget who it is nor makes
 * every caller wait for the provider's timeout.
 */
@Slf4j
@Service
public class GitUserService {

    static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final RepositoryApiClientFactory repositoryApiClientFactory;
    private final Duration ttl;
    private final Clock clock;

    private final ConcurrentMap<Long, CachedUser> cache = new ConcurrentHashMap<>();

    @Autowired
    public GitUserService(RepositoryApiClientFactory repositoryApiClientFactory,
                          @Value("${giteabot.git-user.cache-ttl:PT5M}") Duration ttl) {
        this(repositoryApiClientFactory, ttl, Clock.systemUTC());
    }

    GitUserService(RepositoryApiClientFactory repositoryApiClientFactory, Duration ttl, Clock clock) {
        this.repositoryApiClientFactory = repositoryApiClientFactory;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * Returns the account the integration's token authenticates as.
     *
     * @throws IllegalStateException when the provider cannot be asked and nothing is cached
     */
    public GitUser getGitUser(GitIntegration integration) {
        Instant now = clock.instant();
        CachedUser cached = cache.get(integration.getId());
        if (cached != null && !cached.isFor(integration)) {
            cached = null;
        }
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.user();
        }
        try {
            GitUser user = repositoryApiClientFactory.getApiClient(integration).getAuthenticatedUser();
            cache.put(integration.getId(), new CachedUser(user, integration.getUpdatedAt(), now.plus(ttl)));
            return user;
        } catch (RuntimeException e) {
            if (cached != null) {
                log.warn("Could not refresh the Git user of integration '{}', keeping '{}': {}",
                        integration.getName(), cached.user().username(), e.getMessage());
                cache.put(integration.getId(), new CachedUser(cached.user(), cached.updatedAt(), now.plus(RETRY_DELAY)));
                return cached.user();
            }
            throw new IllegalStateException("Could not determine the Git user of integration '"
                    + integration.getName() + "': " + e.getMessage(), e);
        }
    }

    /** Returns the bot's username on its Git integration's provider. */
    public String getBotUsername(Bot bot) {
        return getGitUser(bot.getGitIntegration()).username();
    }

    /**
     * Returns the cached account without asking the provider, for display in the admin UI.
     * An expired entry is still returned; one cached before the integration was edited is not.
     */
    public Optional<GitUser> findCached(GitIntegration integration) {
        CachedUser cached = cache.get(integration.getId());
        if (cached == null || !cached.isFor(integration)) {
            return Optional.empty();
        }
        return Optional.of(cached.user());
    }

    /**
     * Returns the cached usernames by integration id, see {@link #findCached(GitIntegration)}.
     * Integrations whose account has not been resolved yet are left out.
     */
    public Map<Long, String> findCachedUsernames(Collection<GitIntegration> integrations) {
        Map<Long, String> usernames = new HashMap<>();
        for (GitIntegration integration : integrations) {
            findCached(integration).ifPresent(user -> usernames.put(integration.getId(), user.username()));
        }
        return usernames;
    }

    private record CachedUser(GitUser user, Instant updatedAt, Instant expiresAt) {

        /**
         * Whether this entry was cached for the integration as it is now. Compared in milliseconds,
         * like {@link RepositoryApiClientFactory}: the entity saved in memory carries the clock's
         * nanoseconds, but the copy loaded back from the database only microseconds.
         */
        boolean isFor(GitIntegration integration) {
            if (updatedAt == null || integration.getUpdatedAt() == null) {
                return Objects.equals(updatedAt, integration.getUpdatedAt());
            }
            return updatedAt.toEpochMilli() == integration.getUpdatedAt().toEpochMilli();
        }
    }
}
