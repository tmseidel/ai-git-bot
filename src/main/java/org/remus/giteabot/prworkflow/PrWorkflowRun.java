package org.remus.giteabot.prworkflow;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted record of one {@link PrWorkflow} invocation for a specific bot,
 * repository and pull request. Created by
 * {@link PrWorkflowOrchestrator#run(org.remus.giteabot.admin.Bot,
 * org.remus.giteabot.gitea.model.WebhookPayload, String)} and updated as the
 * workflow progresses.
 *
 * <p>The {@code bot_id} foreign key is modelled as a plain {@code Long}
 * column instead of a {@code @ManyToOne} association so the orchestrator
 * persists rows without forcing the runtime {@code Bot} to be re-attached to
 * the JPA session — this matches the {@code @Async} dispatch pattern of
 * {@link org.remus.giteabot.admin.BotWebhookService}.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "pr_workflow_runs")
public class PrWorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "repo_owner", nullable = false)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    @Column(name = "workflow_key", nullable = false, length = 64)
    private String workflowKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PrWorkflowRunStatus status = PrWorkflowRunStatus.RUNNING;

    @Column(length = 2048)
    private String summary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * Preview URL surfaced once the deployment strategy reports the
     * environment is ready. Populated either synchronously by
     * {@code StaticPreviewUrlStrategy} or asynchronously through
     * {@code POST /api/workflow-callback/{runId}/{secret}}.
     */
    @Column(name = "preview_url", length = 2048)
    private String previewUrl;

    /**
     * Random per-run secret used to HMAC-verify inbound callbacks. Generated
     * by {@link PrWorkflowRunService} when the run is started and exposed to
     * the deployment strategy as part of {@code DeploymentRequest}; never
     * surfaced in the admin UI or logs.
     */
    @Column(name = "callback_secret", length = 128)
    private String callbackSecret;

    /**
     * Opaque JSON returned by a deployment strategy's {@code trigger()} so
     * later {@code poll()} / {@code teardown()} invocations can locate their
     * own state. The bot never inspects the content — it is round-tripped
     * verbatim back into the strategy.
     */
    @Column(name = "deployment_handle_json", columnDefinition = "TEXT")
    private String deploymentHandleJson;

    /**
     * M7 (suite promotion). When the workflow's {@code suiteLifecycle} is
     * {@code offer-as-pr} or {@code promote-on-merge} and the bot has
     * successfully opened a follow-up PR carrying the generated tests, the
     * resulting PR / MR number is stored here. Used as an idempotency
     * guard — {@code SuitePromotionService} skips its work whenever this
     * column is already populated for the run.
     */
    @Column(name = "follow_up_pr_number")
    private Long followUpPrNumber;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<PrWorkflowStep> steps = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (status == null) {
            status = PrWorkflowRunStatus.RUNNING;
        }
    }
}

