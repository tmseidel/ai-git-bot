package org.remus.giteabot.ai;

import org.remus.giteabot.admin.AiIntegration;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory interface for AI provider integrations.
 * Each AI provider (Anthropic, OpenAI, Ollama, llama.cpp) implements this interface
 * to define its type, default configuration, and how to build its client.
 */
public interface AiProviderMetadata {

    /**
     * Returns the unique provider type identifier (e.g., "anthropic", "openai").
     */
    String getProviderType();

    /**
     * Returns the human-readable provider name shown in the admin UI.
     */
    default String getDisplayName() {
        return getProviderType();
    }

    /**
     * Returns the default API URL for this provider.
     */
    String getDefaultApiUrl();

    /**
     * Returns a list of suggested/recommended models for this provider.
     * May be empty for providers like Ollama where models are user-configured.
     */
    List<String> getSuggestedModels();

    /**
     * Returns the model flavors (per-model request presets) this provider
     * offers. Flavors are selectable per AI integration in the admin UI and
     * are applied by {@link #createClient} from the persisted flavor id.
     *
     * <p>Defaults to an empty list, which hides the flavor field for this
     * provider in the form. Providers that offer flavors must always include
     * a {@code "standard"} entry.
     */
    default java.util.List<ModelFlavor> getFlavors() {
        return java.util.List.of();
    }

    /**
     * Returns whether this provider requires an API key.
     */
    boolean requiresApiKey();

    /**
     * Builds a configured RestClient for this provider.
     *
     * @param integration the AI integration configuration
     * @param decryptedApiKey the decrypted API key (may be null for providers that don't require it)
     * @return configured RestClient
     */
    RestClient buildRestClient(AiIntegration integration, String decryptedApiKey);

    /**
     * Creates an AiClient instance for this provider.
     *
     * @param restClient the configured RestClient
     * @param integration the AI integration configuration
     * @return configured AiClient
     */
    AiClient createClient(RestClient restClient, AiIntegration integration);
}
