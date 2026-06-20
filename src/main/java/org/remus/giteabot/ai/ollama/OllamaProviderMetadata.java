package org.remus.giteabot.ai.ollama;

import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory for Ollama (local LLM inference) integration.
 */
@Component
public class OllamaProviderMetadata implements AiProviderMetadata {

    public static final String PROVIDER_TYPE = "ollama";
    public static final String DEFAULT_API_URL = "http://localhost:11434";

    /**
     * Ollama models are user-configured, so we don't provide suggested models.
     * Users should specify the model they have pulled/installed locally.
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
        return RestClient.builder()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        return new OllamaClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                !integration.isUseLegacyToolCalling()
        );
    }
}

