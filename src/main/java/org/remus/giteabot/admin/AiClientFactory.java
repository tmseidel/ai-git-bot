package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory that creates and caches {@link AiClient} instances from persisted
 * {@link AiIntegration} entities.  Clients are cached by integration ID and
 * {@link AiIntegration#getUpdatedAt()} so that a configuration change in the
 * database automatically triggers a new client to be built.
 */
@Slf4j
@Service
public class AiClientFactory {

    private final AiIntegrationService aiIntegrationService;
    private final AiProviderRegistry providerRegistry;

    /** Cache key = integrationId, value = (updatedAt-millis, client). */
    private final ConcurrentMap<Long, CachedClient> cache = new ConcurrentHashMap<>();

    public AiClientFactory(AiIntegrationService aiIntegrationService,
                           AiProviderRegistry providerRegistry) {
        this.aiIntegrationService = aiIntegrationService;
        this.providerRegistry = providerRegistry;
    }

    /**
     * Returns an {@link AiClient} configured according to the given integration.
     * Results are cached and re-created when the integration's updatedAt changes.
     */
    public AiClient getClient(AiIntegration integration) {
        CachedClient cached = cache.get(integration.getId());
        long updatedMillis = integration.getUpdatedAt().toEpochMilli();
        if (cached != null && cached.updatedAtMillis == updatedMillis) {
            return cached.client;
        }

        AiClient client = buildClient(integration);
        cache.put(integration.getId(), new CachedClient(updatedMillis, client));
        log.info("Built new AiClient for integration '{}' (provider={})",
                integration.getName(), integration.getProviderType());
        return client;
    }

    /**
     * Removes all cached clients (useful after deletion).
     */
    public void evict(Long integrationId) {
        cache.remove(integrationId);
    }

    private AiClient buildClient(AiIntegration integration) {
        String providerType = integration.getProviderType();
        AiProviderMetadata provider = providerRegistry.getProviderOrThrow(providerType);

        String decryptedApiKey = provider.requiresApiKey() || hasConfiguredApiKey(integration)
                ? aiIntegrationService.decryptApiKey(integration)
                : null;

        RestClient restClient = provider.buildRestClient(integration, decryptedApiKey);
        return provider.createClient(restClient, integration);
    }

    private boolean hasConfiguredApiKey(AiIntegration integration) {
        return integration.getApiKey() != null && !integration.getApiKey().isBlank();
    }

    private record CachedClient(long updatedAtMillis, AiClient client) {}
}
