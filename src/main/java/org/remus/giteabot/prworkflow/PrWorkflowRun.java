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
    private PrWorkflowRunStatus status = PrWorkflowRunStatus.QUEUED;

    @Column(length = 2048)
    private String summary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

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
            status = PrWorkflowRunStatus.QUEUED;
        }
    }
}

