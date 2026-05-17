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
     * If {@code true} (the current default), the agent loop uses the
     * battle-tested JSON-in-prompt tool-calling path (the historical
     * behaviour). If {@code false}, tool descriptors are sent natively via
     * the provider's {@code tools}/{@code tool_use}/{@code tool_calls} API
     * and the model's structured calls are dispatched directly.
     *
     * <p>Native function calling is currently considered <strong>experimental</strong>:
     * it works well with frontier models but is sensitive to provider API
     * changes and to weaker / smaller models that misuse tools. Operators
     * who want to opt in flip the inverse {@code enableNativeToolCalling}
     * switch in the admin UI (see the transient accessors below). New
     * integrations default to legacy mode; native mode is expected to
     * become the default once the upstream APIs stabilise further.
     */
    @Column(name = "use_legacy_tool_calling", nullable = false)
    private boolean useLegacyToolCalling = true;

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
