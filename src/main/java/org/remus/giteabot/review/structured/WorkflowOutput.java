package org.remus.giteabot.review.structured;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One finalized-output identity for an agentic-review invocation. The public
 * reference ({@code O1234}) is reserved from {@link ReferenceSequence} before the row
 * is written, so it never depends on a provider comment id or on model output.
 *
 * <p>The scope columns are plain ids rather than JPA associations: the webhook
 * dispatch path persists outputs without re-attaching the runtime bot or repository
 * entities, matching {@code PrWorkflowRun}.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_outputs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_workflow_outputs_reference", columnNames = "reference"),
                @UniqueConstraint(name = "uk_workflow_outputs_invocation_stage",
                        columnNames = {"invocation_id", "workflow_key", "stage"})
        },
        indexes = {
                @Index(name = "idx_workflow_outputs_pr",
                        columnList = "git_integration_id, bot_id, repo_owner, repo_name, pr_number")
        })
public class WorkflowOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String reference;

    @Column(name = "git_integration_id", nullable = false)
    private Long gitIntegrationId;

    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "repo_owner", nullable = false)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    /** Provider repository id (opaque, when the provider exposes one). */
    @Column(name = "provider_repository_id", length = 255)
    private String providerRepositoryId;

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    @Column(name = "workflow_key", nullable = false, length = 64)
    private String workflowKey;

    @Column(nullable = false, length = 32)
    private String stage;

    @Column(name = "pr_workflow_run_id")
    private Long prWorkflowRunId;

    /** Identity of one review attempt; a resumed or retried attempt reuses it. */
    @Column(name = "invocation_id", nullable = false, length = 36)
    private String invocationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
