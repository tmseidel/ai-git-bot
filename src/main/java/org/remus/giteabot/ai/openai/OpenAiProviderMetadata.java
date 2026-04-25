package org.remus.giteabot.ai.openai;

import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory for OpenAI API integration.
 */
@Component
public class OpenAiProviderMetadata implements AiProviderMetadata {

    public static final String PROVIDER_TYPE = "openai";
    public static final String DEFAULT_API_URL = "https://api.openai.com";
    public static final List<String> SUGGESTED_MODELS = List.of(
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.3-codex"
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
            throw new IllegalStateException("OpenAI integration requires an API key");
        }
        return RestClient.builder()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("Authorization", "Bearer " + decryptedApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        return new OpenAiClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                integration.getMaxDiffCharsPerChunk(),
                integration.getMaxDiffChunks(),
                integration.getRetryTruncatedChunkChars()
        );
    }
}

