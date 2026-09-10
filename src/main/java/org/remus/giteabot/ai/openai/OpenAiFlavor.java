package org.remus.giteabot.ai.openai;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.ai.ModelFlavor;

import java.util.List;
import java.util.Locale;

/**
 * Provider-internal behavior for an OpenAI (Chat Completions) model flavor.
 *
 * <p>The persisted {@link org.remus.giteabot.admin.AiIntegration#getModelFlavor()}
 * id maps onto one of these entries; the provider's
 * {@link OpenAiProviderMetadata#createClient} resolves the id here and hands
 * the behavior to {@link OpenAiClient}. Unknown ids fall back to
 * {@link #STANDARD} so a stale id can never break a workflow.
 */
@Getter
@RequiredArgsConstructor
public enum OpenAiFlavor {

    /** Provider default behaviour; no extra request fields. */
    STANDARD("standard", "Standard",
            "Provider default. No extra request fields are sent."),

    /**
     * Forces {@code reasoning_effort: "none"}. Required by newer reasoning
     * models (e.g. gpt-5.6-sol) on the Chat Completions endpoint when the
     * front-end injects a default {@code reasoning_effort}: such models
     * refuse native function tools otherwise.
     */
    NO_REASONING("no_reasoning", "No reasoning effort",
            "Sends reasoning_effort=none. Select this for reasoning models such as gpt-5.6-sol "
                    + "that reject function tools on /v1/chat/completions when a gateway injects "
                    + "a default reasoning_effort.");

    private final String id;
    private final String label;
    private final String description;

    /** The value written into the request body; {@code null} omits the field. */
    public String reasoningEffort() {
        return this == NO_REASONING ? "none" : null;
    }

    /**
     * The admin-UI metadata for every flavor, stamped with the provider type
     * so each entry is globally unique across providers (e.g.
     * {@code openai/no_reasoning} can never be confused with another
     * provider's {@code no_reasoning}).
     */
    public static List<ModelFlavor> modelFlavors(String providerType) {
        return java.util.Arrays.stream(values())
                .map(f -> new ModelFlavor(providerType, f.id, f.label, f.description))
                .toList();
    }

    /**
     * Resolves a persisted flavor id to its behavior. Blank and unknown ids
     * degrade to {@link #STANDARD} (the caller logs the fallback).
     */
    public static OpenAiFlavor fromId(String id) {
        if (id != null) {
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            for (OpenAiFlavor flavor : values()) {
                if (flavor.id.equals(normalized)) {
                    return flavor;
                }
            }
        }
        return STANDARD;
    }
}
