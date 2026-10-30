package org.remus.giteabot.ai;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry that collects all {@link AiProviderMetadata} implementations
 * and provides convenient access to provider metadata and configuration.
 */
@Service
public class AiProviderRegistry {

    private final Map<AiProviderType, AiProviderMetadata> providersByType;

    public AiProviderRegistry(List<AiProviderMetadata> providers) {
        this.providersByType = new EnumMap<>(AiProviderType.class);
        for (AiProviderMetadata provider : providers) {
            providersByType.put(provider.getProviderType(), provider);
        }
    }

    /**
     * Returns all registered provider types in {@link AiProviderType} declaration order.
     */
    public List<AiProviderType> getProviderTypes() {
        return List.copyOf(providersByType.keySet());
    }

    /**
     * Returns the metadata for a specific provider type.
     */
    public Optional<AiProviderMetadata> getProvider(AiProviderType providerType) {
        return Optional.ofNullable(providersByType.get(providerType));
    }

    /**
     * Returns the metadata for a specific provider type, throwing if not found.
     */
    public AiProviderMetadata getProviderOrThrow(AiProviderType providerType) {
        return getProvider(providerType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI provider type: " + providerType));
    }

    /**
     * Returns a map of provider type to default API URL.
     */
    public Map<AiProviderType, String> getDefaultApiUrls() {
        Map<AiProviderType, String> urls = new EnumMap<>(AiProviderType.class);
        for (AiProviderMetadata provider : providersByType.values()) {
            urls.put(provider.getProviderType(), provider.getDefaultApiUrl());
        }
        return urls;
    }

    /**
     * Returns a map of provider type to suggested models.
     */
    public Map<AiProviderType, List<String>> getSuggestedModels() {
        Map<AiProviderType, List<String>> models = new EnumMap<>(AiProviderType.class);
        for (AiProviderMetadata provider : providersByType.values()) {
            models.put(provider.getProviderType(), provider.getSuggestedModels());
        }
        return models;
    }

    /**
     * Returns a map of provider type to model flavors (per-model request
     * presets). Used by the admin form to render the flavor select for the
     * currently selected provider.
     */
    public Map<AiProviderType, List<ModelFlavor>> getFlavors() {
        Map<AiProviderType, List<ModelFlavor>> flavors = new EnumMap<>(AiProviderType.class);
        for (AiProviderMetadata provider : providersByType.values()) {
            flavors.put(provider.getProviderType(), provider.getFlavors());
        }
        return flavors;
    }

    /**
     * Returns a map of provider type to whether an API key is required.
     */
    public Map<AiProviderType, Boolean> getApiKeyRequirements() {
        Map<AiProviderType, Boolean> requirements = new EnumMap<>(AiProviderType.class);
        for (AiProviderMetadata provider : providersByType.values()) {
            requirements.put(provider.getProviderType(), provider.requiresApiKey());
        }
        return requirements;
    }

}
