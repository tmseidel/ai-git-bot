package org.remus.giteabot.ai.google;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory for Google AI Gemini API integration.
 */
@Component
@RequiredArgsConstructor
public class GoogleAiProviderMetadata implements AiProviderMetadata {

    public static final String PROVIDER_TYPE = "google";
    public static final String DEFAULT_API_URL = "https://generativelanguage.googleapis.com";
    public static final List<String> SUGGESTED_MODELS = List.of(
            "gemini-3.1-pro-preview",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite"
    );
    private final ObjectProvider<RestClient.Builder> restClientBuilder;

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String getDisplayName() {
        return "gemini";
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
            throw new IllegalStateException("Google AI integration requires an API key");
        }
        return restClientBuilder.getObject()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("x-goog-api-key", decryptedApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        return new GoogleAiClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                !integration.isUseLegacyToolCalling()
        );
    }
}
