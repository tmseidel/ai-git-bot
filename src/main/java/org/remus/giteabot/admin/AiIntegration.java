package org.remus.giteabot.admin;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "ai_integrations")
public class AiIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String providerType;

    @Column(nullable = false)
    private String apiUrl;

    @Column(length = 1000)
    private String apiKey;

    private String apiVersion;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private int maxTokens = 4096;

    /**
     * If {@code false} (the current default), tool descriptors are sent
     * natively via the provider's {@code tools}/{@code tool_use}/{@code
     * tool_calls} API and the model's structured calls are dispatched
     * directly. If {@code true}, the agent loop falls back to the legacy
     * JSON-in-prompt tool-calling path (the historical behaviour).
     *
     * <p>Native function calling is the <strong>recommended</strong> route
     * for frontier models and is therefore enabled by default for new
     * integrations. The legacy JSON path remains available as a fallback for
     * weaker / smaller models or self-hosted backends where native tool use
     * misbehaves in agentic workflows; operators flip the inverse
     * {@code enableNativeToolCalling} switch in the admin UI (see the
     * transient accessors below). llama.cpp always runs in legacy mode
     * regardless of this flag.
     */
    @Column(name = "use_legacy_tool_calling", nullable = false)
    private boolean useLegacyToolCalling = false;

    /**
     * Enables provider-side prompt caching (cache breakpoints on the system
     * prompt and a rolling breakpoint on the conversation tail). In agentic
     * loops every round re-sends the whole prefix, so cache reads (~0.1x
     * input price) cut the input-token cost of a review run to a fraction.
     *
     * <p>On by default. Currently only consulted by the Anthropic client —
     * providers without a caching API ignore the flag entirely.</p>
     */
    @Column(name = "prompt_caching_enabled", nullable = false)
    private boolean promptCachingEnabled = true;

    @Column(nullable = false)
    private int contextWindowTokens = 200_000;

    /**
     * Provider-specific per-model request preset ("flavor"), e.g.
     * {@code "no_reasoning"} for an OpenAI reasoning model whose gateway
     * defaults {@code reasoning_effort} on. Values are provider-defined (see
     * {@link org.remus.giteabot.ai.AiProviderMetadata#getFlavors()}); the
     * conventional value {@code "standard"} selects the provider's default
     * behaviour and is the fallback for unknown ids.
     */
    @Column(name = "model_flavor", nullable = false)
    private String modelFlavor = "standard";

    /**
     * UI-facing inverse of {@link #useLegacyToolCalling}. The admin form
     * binds to this property so the checkbox semantics read positively
     * ("enable experimental native tool calling") while the persisted
     * column keeps the original {@code use_legacy_tool_calling} meaning.
     * Not a JPA column.
     */
    @Transient
    public boolean isEnableNativeToolCalling() {
        return !useLegacyToolCalling;
    }

    public void setEnableNativeToolCalling(boolean enableNativeToolCalling) {
        this.useLegacyToolCalling = !enableNativeToolCalling;
    }

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
