package org.remus.giteabot.learning.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One reviewer's assessment of one agentic-review target, as stated in a Git comment.
 *
 * <p>The target is stored as a typed foreign key — the finding row for {@code O…-F…}, the review
 * row for {@code O…} and {@code O…-A} — never as a copied paragraph of the comment, so a rerendered
 * review or a corrected comment cannot silently retarget existing feedback. The reason is the only
 * free text, and it is bounded.</p>
 *
 * <p>Records are append-only in meaning: a later comment by the same actor supersedes the earlier
 * one, and retraction is a state transition. Nothing here is deleted when a configuration changes;
 * runtime rows keep their own integration/bot/repository identity.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_feedback",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_workflow_feedback_reference",
                        columnNames = "reference"),
                @UniqueConstraint(name = "uk_workflow_feedback_source",
                        columnNames = "source_key")
        },
        indexes = {
                @Index(name = "idx_workflow_feedback_output", columnList = "output_id"),
                @Index(name = "idx_workflow_feedback_scope",
                        columnList = "git_integration_id, bot_id, repo_owner, repo_name, pr_number"),
                @Index(name = "idx_workflow_feedback_target_actor",
                        columnList = "output_id, target_type, finding_id, actor")
        })
public class WorkflowFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public {@code FB<n>} reference. */
    @Column(nullable = false, length = 32)
    private String reference;

    @Column(name = "output_id", nullable = false)
    private Long outputId;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    /**
     * The reference this record judges ({@code O1234}, {@code O1234-F2}, {@code O1234-A}), stored as
     * the commenter wrote it. The typed foreign keys below are what the application resolves; this
     * column keeps summaries readable without a join and never changes when a review is rerendered.
     */
    @Column(name = "target_reference", nullable = false, length = 32)
    private String targetReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private FeedbackTargetType targetType;

    /** Set only for a {@link FeedbackTargetType#FINDING} target. */
    @Column(name = "finding_id")
    private Long findingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private FeedbackVerdict verdict;

    @Column(length = 4000)
    private String reason;

    @Column(nullable = false, length = 255)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FeedbackState state = FeedbackState.EFFECTIVE;

    /** Reference of the record that replaced this one, when it was superseded. */
    @Column(name = "superseded_by", length = 32)
    private String supersededBy;

    // Scope, kept independently of the replaceable configuration rows.
    @Column(name = "git_integration_id", nullable = false)
    private Long gitIntegrationId;

    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "repo_owner", nullable = false, length = 255)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false, length = 255)
    private String repoName;

    @Column(name = "provider_repository_id", length = 255)
    private String providerRepositoryId;

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    // Source comment identity.
    @Column(name = "subject_kind", nullable = false, length = 16)
    private String subjectKind;

    @Column(name = "subject_number", nullable = false)
    private Long subjectNumber;

    @Column(name = "comment_kind", nullable = false, length = 32)
    private String commentKind;

    @Column(name = "comment_id", nullable = false, length = 128)
    private String commentId;

    @Column(name = "source_key", nullable = false, length = 64)
    private String sourceKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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
