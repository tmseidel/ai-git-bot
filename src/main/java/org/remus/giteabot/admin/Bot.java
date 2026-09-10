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

    /**
     * The issue-assigned workflow configuration: which
     * {@code IssueWorkflow}(s) run when the bot is assigned to an issue and
     * when it receives follow-up issue comments. Independent from
     * {@link #workflowConfiguration} (pull-request events).
     */
    @ManyToOne
    @JoinColumn(name = "issue_workflow_configuration_id")
    private WorkflowConfiguration issueWorkflowConfiguration;

    @ManyToOne
    @JoinColumn(name = "deployment_target_id")
    private DeploymentTarget deploymentTarget;

    private String webhookSecret;

    /**
     * Optional shared secret used to verify the provider's webhook signature
     * (HMAC or token header) in addition to the URL path secret. Stored
     * encrypted via {@link EncryptionService} (see {@link BotService#save}).
     */
    @Column(name = "webhook_signing_secret", length = 1000)
    private String webhookSigningSecret;

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
    private boolean runOnPrCreation = false;

    @Column(nullable = false)
    private boolean runOnPrUpdate = false;

    @Column(nullable = false)
    private boolean runOnIssueCreation = false;

    /**
     * Glob pattern (comma-separated list) that allowlists which PR
     * <em>target</em> branches (base refs) may start a PR workflow — e.g.
     * {@code releases/*} in a git-flow setup. Empty (the default) or {@code *}
     * allows every branch/tag. Matching uses gobwas/glob semantics
     * ({@code *}, {@code ?}, {@code [..]}, {@code {a,b}}, {@code **}) and may use
     * full ref names such as {@code refs/heads/develop}. See
     * {@link org.remus.giteabot.util.BranchFilter}.
     */
    @Column(name = "branch_filter", length = 1000)
    private String branchFilter = "";

    /**
     * @deprecated Issue behaviour is no longer dispatched via the bot type.
     * It is resolved from {@link #getIssueWorkflowConfiguration()} (see the
     * {@code issueworkflow} package); PR behaviour from
     * {@link #getWorkflowConfiguration()}. The column is retained for one
     * release as migration safety and is scheduled for removal.
     */
    @Deprecated
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
