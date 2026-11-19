package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryProviderRegistry;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Factory that creates and caches {@link RepositoryPlatform} instances from persisted
 * {@link GitIntegration} entities.
 * <p>
 * Platforms are cached by integration ID and {@link GitIntegration#getUpdatedAt()}
 * so that configuration changes automatically produce fresh ones. A cached platform keeps
 * the decrypted API token in memory, as its API client authenticates with it; the SSH private
 * key is only decrypted when a platform's credentials are read.
 * <p>
 * This relies on every change to an integration — token, SSH key, {@code known_hosts}, URLs —
 * going through JPA so that {@code @PreUpdate} bumps {@code updatedAt}. A cached platform holds
 * values read at build time (e.g. Gitea's pinned {@code known_hosts}) and its SSH credential
 * supplier reads the {@link GitIntegration} instance it was built from, so an update that
 * bypasses the entity lifecycle (a bulk {@code @Modifying} query, a native update) would keep
 * serving stale configuration until the next {@code updatedAt} change.
 * <p>
 * Provider-specific logic (URL resolution, authentication) is delegated to
 * {@link RepositoryProviderMetadata} implementations via {@link RepositoryProviderRegistry}.
 */
@Slf4j
@Service
public class RepositoryPlatformFactory {

    private final GitIntegrationService gitIntegrationService;
    private final RepositoryProviderRegistry providerRegistry;
    /** How long a platform caches the account its token authenticates as. */
    private final Duration userTtl;

    /** Cache key = integrationId, value = (updatedAt-millis, platform). */
    private final ConcurrentMap<Long, CachedPlatform> cache = new ConcurrentHashMap<>();

    public RepositoryPlatformFactory(GitIntegrationService gitIntegrationService,
                                     RepositoryProviderRegistry providerRegistry,
                                     @Value("${giteabot.git-user.cache-ttl:PT5M}") Duration userTtl) {
        this.gitIntegrationService = gitIntegrationService;
        this.providerRegistry = providerRegistry;
        this.userTtl = userTtl;
    }

    /**
     * Returns the {@link RepositoryPlatform} for the given Git integration.
     * It is cached and re-created when the integration's updatedAt changes.
     */
    public RepositoryPlatform getPlatform(GitIntegration integration) {
        return getCachedPlatform(integration).platform;
    }

    /** Returns the API client of the integration's {@linkplain #getPlatform platform}. */
    public RepositoryApiClient getApiClient(GitIntegration integration) {
        return getPlatform(integration).getClient();
    }

    /** Creates an uncached HTTP API client with a supplied plaintext replacement token. */
    public RepositoryApiClient createApiClient(GitIntegration integration, String token) {
        RepositoryProviderMetadata provider = providerRegistry.getProvider(integration.getProviderType());
        return provider.createPlatform(integration, token,
                provider.createHttpCredentials(integration, token), null, userTtl).getClient();
    }

    /**
     * Returns the account the integration's token authenticates as if its platform has resolved
     * it, without asking the provider or building a platform, for display in the admin UI. An
     * expired account is still returned; one resolved before the integration was edited is not.
     */
    public Optional<RepositoryUser> findCachedUser(GitIntegration integration) {
        if (integration.getId() == null || integration.getUpdatedAt() == null) {
            return Optional.empty();
        }
        CachedPlatform cached = cache.get(integration.getId());
        if (cached == null || cached.updatedAtMillis != integration.getUpdatedAt().toEpochMilli()) {
            return Optional.empty();
        }
        return cached.platform.findCachedUser();
    }

    /**
     * Returns the usernames of {@link #findCachedUser} by integration id; integrations whose
     * account has not been resolved yet are left out.
     */
    public Map<Long, String> findCachedUsernames(Collection<GitIntegration> integrations) {
        Map<Long, String> usernames = new HashMap<>();
        for (GitIntegration integration : integrations) {
            findCachedUser(integration).ifPresent(user -> usernames.put(integration.getId(), user.username()));
        }
        return usernames;
    }

    private CachedPlatform getCachedPlatform(GitIntegration integration) {
        CachedPlatform cached = cache.get(integration.getId());
        long updatedMillis = integration.getUpdatedAt().toEpochMilli();

        if (cached != null && cached.updatedAtMillis == updatedMillis) {
            return cached;
        }

        CachedPlatform newPlatform = buildPlatform(integration);
        cache.put(integration.getId(), newPlatform);
        log.info("Built new platform for integration '{}' (type={}, url={})",
                integration.getName(), integration.getProviderType(), integration.getUrl());
        return newPlatform;
    }

    private CachedPlatform buildPlatform(GitIntegration integration) {
        RepositoryProviderMetadata provider = providerRegistry.getProvider(integration.getProviderType());
        String decryptedToken = gitIntegrationService.decryptToken(integration);

        log.debug("Building platform for '{}': apiUrl={}, tokenLength={}",
                integration.getName(),
                integration.getUrl(),
                decryptedToken != null ? decryptedToken.length() : 0);

        HttpCredentials httpCredentials = provider.createHttpCredentials(integration, decryptedToken);
        Supplier<SshCredentials> sshCredentials = integration.getTransport() == GitTransport.SSH
                ? () -> new SshCredentials(gitIntegrationService.decryptSshPrivateKey(integration),
                        integration.getSshKnownHosts())
                : null;
        RepositoryPlatform platform = provider.createPlatform(
                integration, decryptedToken, httpCredentials, sshCredentials, userTtl);

        return new CachedPlatform(integration.getUpdatedAt().toEpochMilli(), platform);
    }

    private record CachedPlatform(long updatedAtMillis, RepositoryPlatform platform) {}
}
