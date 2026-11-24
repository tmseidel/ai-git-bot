package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiAuditRecorder;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.ProviderRetryNotifier;
import org.remus.giteabot.ai.RetryAiClient;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.aiusage.AiUsageService;
import org.remus.giteabot.config.AiRetryProperties;
import org.remus.giteabot.config.AiUsageProperties;
import org.springframework.stereotype.Service;

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
    private final AiUsageProperties usageProperties;
    private final AiRetryProperties retryProperties;
    private final ProviderRetryNotifier retryNotifier;

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
     * The provider's own client for a plaintext key, without the retry and
     * audit wrappers {@link #getClient} adds and without caching.
     */
    public AiClient createUnwrappedClient(AiProviderMetadata provider, AiIntegration integration, String apiKey) {
        return provider.createClient(provider.buildRestClient(integration, apiKey), integration);
    }

    private AiClient buildClient(AiIntegration integration) {
        AiProviderMetadata provider = providerRegistry.getProviderOrThrow(integration.getProviderType());
        String decryptedApiKey = provider.requiresApiKey() ? aiIntegrationService.decryptApiKey(integration) : null;
        AiClient client = createUnwrappedClient(provider, integration, decryptedApiKey);
        return decorate(client, integration.getName());
    }

    /**
     * Wraps a provider client the way {@link #getClient} does: provider retries, and audit
     * recording of every logical call (round) and every request to the provider (attempt),
     * tagged with {@code integrationName}.
     */
    public AiClient decorate(AiClient client, String integrationName) {
        AiAuditRecorder recorder = new IntegrationAuditRecorder(integrationName, aiUsageService);
        if (client instanceof AbstractAiClient abstractClient) {
            abstractClient.setAuditRecorder(recorder);
            abstractClient.setUsageProperties(usageProperties);
        }
        return new RetryAiClient(client, retryProperties, retryNotifier, recorder);
    }

    /**
     * Bridges client-side audit callbacks to the {@link AiUsageService}, tagging every record
     * with the integration name. The service attaches it to the current workflow run from the
     * {@link org.remus.giteabot.ai.AiAuditContext} thread-local.
     */
    private record IntegrationAuditRecorder(String integrationName,
                                            AiUsageService aiUsageService) implements AiAuditRecorder {

        @Override
        public void attemptStarted() {
            aiUsageService.attemptStarted(integrationName);
        }

        @Override
        public void recordUsage(long inputTokens, long outputTokens,
                                long cacheCreationInputTokens, long cacheReadInputTokens,
                                String rawRequest, String rawResponse) {
            aiUsageService.usageReported(inputTokens, outputTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, rawRequest, rawResponse);
        }

        @Override
        public void attemptSucceeded(StopReason stopReason) {
            aiUsageService.attemptSucceeded(stopReason);
        }

        @Override
        public void attemptFailed(Throwable error) {
            aiUsageService.attemptFailed(error);
        }

    }

    private record CachedClient(long updatedAtMillis, AiClient client) {}
}
