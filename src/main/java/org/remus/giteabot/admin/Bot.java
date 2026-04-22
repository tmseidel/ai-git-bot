package org.remus.giteabot.admin;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "bots")
public class Bot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String username;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    private String webhookSecret;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToOne(optional = false)
    @JoinColumn(name = "ai_integration_id", nullable = false)
    private AiIntegration aiIntegration;

    @ManyToOne(optional = false)
    @JoinColumn(name = "git_integration_id", nullable = false)
    private GitIntegration gitIntegration;

    @Column(nullable = false)
    private boolean agentEnabled = false;

    /**
     * Enables provider-specific extended thinking / reasoning tokens.
     * For Anthropic: activates Extended Thinking (claude-3-7-sonnet+).
     * For OpenAI: sets reasoning_effort=high (o-series models).
     * Providers that do not support this feature silently ignore the flag.
     */
    @Column(nullable = false)
    private boolean deepThinkingEnabled = false;

    @Column(nullable = false)
    private long webhookCallCount = 0;

    @Column(nullable = false)
    private long aiTokensSent = 0;

    @Column(nullable = false)
    private long aiTokensReceived = 0;

    private Instant lastWebhookAt;

    private Instant lastAiCallAt;

    @Column(columnDefinition = "TEXT")
    private String lastErrorMessage;

    private Instant lastErrorAt;

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

    public String getWebhookPath() {
        if (webhookSecret == null) {
            return null;
        }
        return "/api/webhook/" + webhookSecret;
    }
}
