package org.remus.giteabot.ai.anthropic;

import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory for Anthropic Claude AI integration.
 */
@Component
public class AnthropicProviderMetadata implements AiProviderMetadata {

    public static final String PROVIDER_TYPE = "anthropic";
    public static final String DEFAULT_API_URL = "https://api.anthropic.com";
    public static final String DEFAULT_API_VERSION = "2023-06-01";
    public static final List<String> SUGGESTED_MODELS = List.of(
            "claude-opus-4-7",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001"
    );

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
        return true;
    }

    @Override
    public RestClient buildRestClient(AiIntegration integration, String decryptedApiKey) {
        if (decryptedApiKey == null || decryptedApiKey.isBlank()) {
            throw new IllegalStateException("Anthropic integration requires an API key");
        }

        String apiVersion = integration.getApiVersion();
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = DEFAULT_API_VERSION;
        }

        return RestClient.builder()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("x-api-key", decryptedApiKey)
                .defaultHeader("anthropic-version", apiVersion)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        return new AnthropicAiClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                integration.getMaxDiffCharsPerChunk(),
                integration.getMaxDiffChunks(),
                integration.getRetryTruncatedChunkChars()
        );
    }
}

