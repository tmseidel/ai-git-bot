package org.remus.giteabot.admin;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.SystemPrompt;

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

    @ManyToOne(optional = false)
    @JoinColumn(name = "system_prompt_id", nullable = false)
    private SystemPrompt systemPrompt;

    @ManyToOne
    @JoinColumn(name = "mcp_configuration_id")
    private McpConfiguration mcpConfiguration;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BotType botType = BotType.CODING;

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
