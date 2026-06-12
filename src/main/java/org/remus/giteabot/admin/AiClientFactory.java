package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.AuditingAiClient;
import org.remus.giteabot.aiusage.AiUsageService;
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
@RequiredArgsConstructor
public class AiClientFactory {

    private final AiIntegrationService aiIntegrationService;
    private final AiProviderRegistry providerRegistry;
    private final AiUsageService aiUsageService;

    /** Cache key = integrationId, value = (updatedAt-millis, client). */
    private final ConcurrentMap<Long, CachedClient> cache = new ConcurrentHashMap<>();

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

        String decryptedApiKey = provider.requiresApiKey()
                ? aiIntegrationService.decryptApiKey(integration)
                : null;

        RestClient restClient = provider.buildRestClient(integration, decryptedApiKey);
        AiClient client = provider.createClient(restClient, integration);

        AiAuditRecorder recorder = new IntegrationAuditRecorder(integration.getName(), aiUsageService);
        if (client instanceof AbstractAiClient abstractClient) {
            abstractClient.setAuditRecorder(recorder);
        }
        return new AuditingAiClient(client, recorder);
    }

    /**
     * Bridges client-side audit callbacks to the {@link AiUsageService},
     * tagging every record with the integration name and the session id from
     * the {@link AiAuditContext} thread-local.
     */
    private record IntegrationAuditRecorder(String integrationName,
                                            AiUsageService aiUsageService) implements AiAuditRecorder {

        @Override
        public void recordUsage(long inputTokens, long outputTokens) {
            aiUsageService.recordUsage(integrationName, AiAuditContext.getSessionId(),
                    inputTokens, outputTokens);
        }

        @Override
        public void recordError(Throwable error) {
            aiUsageService.recordError(integrationName, AiAuditContext.getSessionId(), error);
        }
    }

    private record CachedClient(long updatedAtMillis, AiClient client) {}
}
