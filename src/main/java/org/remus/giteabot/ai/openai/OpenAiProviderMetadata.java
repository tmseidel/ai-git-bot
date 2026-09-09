package org.remus.giteabot.ai.openai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.ModelFlavor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;

/**
 * Metadata and factory for OpenAI API integration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiProviderMetadata implements AiProviderMetadata {

    public static final String PROVIDER_TYPE = "openai";
    public static final String DEFAULT_API_URL = "https://api.openai.com";
    public static final List<String> SUGGESTED_MODELS = List.of(
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna"
    );

    private final ObjectProvider<RestClient.Builder> restClientBuilder;

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
        return restClientBuilder.getObject()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("Authorization", "Bearer " + decryptedApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public List<ModelFlavor> getFlavors() {
        // Stamp this provider's type into each flavor so the id is globally
        // unique (e.g. "openai/no_reasoning") and never collides with another
        // provider's "standard"/"no_reasoning".
        return OpenAiFlavor.modelFlavors(PROVIDER_TYPE);
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        String rawFlavor = integration.getModelFlavor();
        String storedFlavor = (rawFlavor == null || rawFlavor.isBlank())
                ? null
                : rawFlavor.trim().toLowerCase(Locale.ROOT);
        OpenAiFlavor flavor = OpenAiFlavor.fromId(storedFlavor);
        // A blank or unknown value degrades to 'standard' silently; only a
        // non-blank value that we do not recognise is worth flagging.
        if (storedFlavor != null && !OpenAiFlavor.STANDARD.getId().equals(storedFlavor)
                && flavor == OpenAiFlavor.STANDARD) {
            log.warn("Unknown model flavor '{}' for OpenAI integration '{}'; using 'standard'",
                    rawFlavor, integration.getName());
        }
        return new OpenAiClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                !integration.isUseLegacyToolCalling(),
                flavor
        );
    }
}
