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
    void googleMetadataExposesApiKeyRequirement() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(
                new GoogleAiProviderMetadata(builderProvider()),
                new OllamaProviderMetadata(builderProvider())
        ));

        assertTrue(registry.getApiKeyRequirements().get(AiProviderType.GOOGLE));
        assertFalse(registry.getApiKeyRequirements().get(AiProviderType.OLLAMA));

        // Providers without flavor support expose an empty list (form hides the field).
        assertTrue(registry.getFlavors().get(AiProviderType.GOOGLE).isEmpty());
        assertTrue(registry.getFlavors().get(AiProviderType.OLLAMA).isEmpty());
    }

    @Test
    void registryExposesProviderFlavors_forOpenAi() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(
                new org.remus.giteabot.ai.openai.OpenAiProviderMetadata(builderProvider())
        ));

        java.util.List<ModelFlavor> flavors = registry.getFlavors().get(AiProviderType.OPENAI);
        assertEquals(2, flavors.size());
        assertEquals("standard", flavors.get(0).id());
        assertEquals("no_reasoning", flavors.get(1).id());
        // Each flavor is stamped with its provider type so ids are unique across providers.
        assertEquals(AiProviderType.OPENAI, flavors.get(0).providerType());
        assertEquals(AiProviderType.OPENAI, flavors.get(1).providerType());
    }
}
