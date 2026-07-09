package org.remus.giteabot.ai;

import lombok.NonNull;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.google.GoogleAiProviderMetadata;
import org.remus.giteabot.ai.ollama.OllamaProviderMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderRegistryTest {

    private static ObjectProvider<RestClient.Builder> builderProvider() {
        return new ObjectProvider<>() {
            @Override
            public @NonNull RestClient.Builder getObject() {
                return RestClient.builder();
            }
        };
    }

    @Test
    void googleMetadataExposesDisplayNameAndApiKeyRequirement() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(
                new GoogleAiProviderMetadata(builderProvider()),
                new OllamaProviderMetadata(builderProvider())
        ));

        assertEquals("gemini", registry.getDisplayNames().get("google"));
        assertTrue(registry.getApiKeyRequirements().get("google"));
        assertFalse(registry.getApiKeyRequirements().get("ollama"));
    }
}
