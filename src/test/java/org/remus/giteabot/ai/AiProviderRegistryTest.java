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

        // Providers without flavor support expose an empty list (form hides the field).
        assertTrue(registry.getFlavors().get("google").isEmpty());
        assertTrue(registry.getFlavors().get("ollama").isEmpty());
    }

    @Test
    void registryExposesProviderFlavors_forOpenAi() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(
                new org.remus.giteabot.ai.openai.OpenAiProviderMetadata(builderProvider())
        ));

        java.util.List<ModelFlavor> flavors = registry.getFlavors().get("openai");
        assertEquals(2, flavors.size());
        assertEquals("standard", flavors.get(0).id());
        assertEquals("no_reasoning", flavors.get(1).id());
        // Each flavor is stamped with its provider type so ids are unique across providers.
        assertEquals("openai", flavors.get(0).providerType());
        assertEquals("openai", flavors.get(1).providerType());
    }
}
