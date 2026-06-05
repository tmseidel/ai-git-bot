package org.remus.giteabot.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
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

    @ManyToOne(optional = false)
    @JoinColumn(name = "bot_tool_configuration_id", nullable = false)
    private BotToolConfiguration toolConfiguration;

    @ManyToOne
    @JoinColumn(name = "workflow_configuration_id")
    private WorkflowConfiguration workflowConfiguration;

    @ManyToOne
    @JoinColumn(name = "deployment_target_id")
    private DeploymentTarget deploymentTarget;

    private String webhookSecret;

    @Column(name = "user_whitelist", columnDefinition = "TEXT")
    private String userWhitelist;

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

    @Column(nullable = false)
    private boolean runOnPrCreation = false;

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
