package org.remus.giteabot.ai.vllm;

import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory for vLLM OpenAI-compatible local inference integration.
 */
@Component
public class VllmProviderMetadata implements AiProviderMetadata {

    public static final String PROVIDER_TYPE = "vllm";
    public static final String DEFAULT_API_URL = "http://localhost:8000";

    /**
     * vLLM models are selected when starting the server, so users configure the served model name.
     */
    public static final List<String> SUGGESTED_MODELS = List.of();

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String getDefaultApiUrl() {
        return DEFAULT_API_URL;
    }

    @Override
    public List<String> getSuggestedModels() {
        return SUGGESTED_MODELS;
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public RestClient buildRestClient(AiIntegration integration, String decryptedApiKey) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("Content-Type", "application/json");

        if (decryptedApiKey != null && !decryptedApiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + decryptedApiKey);
        }

        return builder.build();
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        return new VllmClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                integration.getMaxDiffCharsPerChunk(),
                integration.getMaxDiffChunks(),
                integration.getRetryTruncatedChunkChars()
        );
    }
}
