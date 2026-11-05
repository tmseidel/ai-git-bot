package org.remus.giteabot.ai;

/**
 * Supported AI provider types. Each type corresponds to one
 * {@link AiProviderMetadata} implementation; the constant name is what
 * {@code ai_integrations.provider_type} stores.
 */
public enum AiProviderType {
    ANTHROPIC("Anthropic"),
    GOOGLE("Google Gemini"),
    LLAMACPP("llama.cpp"),
    OLLAMA("Ollama"),
    OPENAI("OpenAI"),
    OPENROUTER("OpenRouter");

    private final String displayName;

    AiProviderType(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable provider name shown in the admin UI. */
    public String getDisplayName() {
        return displayName;
    }
}
