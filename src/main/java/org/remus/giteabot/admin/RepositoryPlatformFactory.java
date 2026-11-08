package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryProviderRegistry;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.stereotype.Service;

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
@RequiredArgsConstructor
public class RepositoryPlatformFactory {

    private final GitIntegrationService gitIntegrationService;
    private final RepositoryProviderRegistry providerRegistry;

    /** Cache key = integrationId, value = (updatedAt-millis, platform). */
    private final ConcurrentMap<Long, CachedPlatform> cache = new ConcurrentHashMap<>();

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
                provider.createHttpCredentials(integration, token), null).getClient();
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
                integration, decryptedToken, httpCredentials, sshCredentials);

        return new CachedPlatform(integration.getUpdatedAt().toEpochMilli(), platform);
    }

    private record CachedPlatform(long updatedAtMillis, RepositoryPlatform platform) {}
}
