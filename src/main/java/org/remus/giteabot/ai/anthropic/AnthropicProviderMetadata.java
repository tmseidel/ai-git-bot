package org.remus.giteabot.ai.anthropic;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderType;
import org.remus.giteabot.config.AnthropicExtendedThinkingProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory for Anthropic Claude AI integration.
 */
@Component
@RequiredArgsConstructor
public class AnthropicProviderMetadata implements AiProviderMetadata {

    public static final AiProviderType PROVIDER_TYPE = AiProviderType.ANTHROPIC;
    public static final String DEFAULT_API_URL = "https://api.anthropic.com";
    public static final String DEFAULT_API_VERSION = "2023-06-01";
    // Current models checked against Anthropic's model list on 2026-10-18.
    public static final List<String> SUGGESTED_MODELS = List.of(
            "claude-fable-5-1",
            "claude-opus-5-5",
            "claude-sonnet-5-5",
            "claude-haiku-5-5"
    );

    private final ObjectProvider<RestClient.Builder> restClientBuilder;
    private final AnthropicExtendedThinkingProperties extendedThinkingProperties;

    @Override
    public AiProviderType getProviderType() {
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

        return restClientBuilder.getObject()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("x-api-key", decryptedApiKey)
                .defaultHeader("anthropic-version", apiVersion)
                .defaultHeader("anthropic-beta", "mcp-client-2025-11-20")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        return new AnthropicAiClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                !integration.isUseLegacyToolCalling(),
                integration.isPromptCachingEnabled(),
                extendedThinkingProperties.isEnabled(),
                extendedThinkingProperties.getEffort()
        );
    }
}
