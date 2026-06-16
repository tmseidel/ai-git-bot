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

    @Column(nullable = false)
    private int maxDiffCharsPerChunk = 120000;

    @Column(nullable = false)
    private int maxDiffChunks = 8;

    @Column(nullable = false)
    private int retryTruncatedChunkChars = 60000;

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

    @Column(nullable = false)
    private int contextWindowTokens = 200_000;

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
