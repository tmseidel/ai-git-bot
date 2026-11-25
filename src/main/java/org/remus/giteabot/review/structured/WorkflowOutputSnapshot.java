package org.remus.giteabot.review.structured;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Supplemental evidence captured only when the agentic-review configuration enables feedback
 * collection: the task the model was given, the diff it judged, the prompt and provider/model
 * fingerprint, and the revision it was produced against.
 *
 * <p>It exists so later analysis can work from what the review actually saw instead of re-deriving
 * it from a pull request that has moved on. It is never a source of truth for the review, holds no
 * credentials, and is size-bounded: content beyond the configured limit is dropped and the row is
 * marked incomplete instead of being silently shortened. The structured review record exists and
 * works whether or not this row does.</p>
 *
 * <p>Fork provenance is not persisted here. Bitbucket can provide a source repository to resolve an
 * abbreviated SHA during review; this does not establish historical fork identity for replay.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_output_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_workflow_output_snapshots_review",
                columnNames = "review_id"))
public class WorkflowOutputSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "output_id", nullable = false)
    private Long outputId;

    @Column(name = "pr_title", columnDefinition = "TEXT")
    private String prTitle;

    @Column(name = "pr_body", columnDefinition = "TEXT")
    private String prBody;

    /** The diff the review judged, truncated to the configured snapshot budget. */
    @Column(name = "diff_text", columnDefinition = "TEXT")
    private String diffText;

    /** Resolved system prompt the model saw; the operator prompt plus the protocol. */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "system_prompt_hash", length = 64)
    private String systemPromptHash;

    @Column(name = "base_sha", length = 64)
    private String baseSha;

    @Column(name = "head_sha", length = 64)
    private String headSha;

    @Column(name = "base_ref", length = 512)
    private String baseRef;

    @Column(name = "head_ref", length = 512)
    private String headRef;

    /** Provider repository identity (opaque), independent of the owner/name the review used. */
    @Column(name = "provider_repository_id", length = 255)
    private String providerRepositoryId;

    /** Git provider and AI model the review was produced with (never a credential or endpoint). */
    @Column(length = 64)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(name = "workflow_configuration_id")
    private Long workflowConfigurationId;

    /** Fingerprint of the effective agentic-review settings this review ran with. */
    @Column(name = "settings_hash", length = 64)
    private String settingsHash;

    /**
     * Whether learned guidance was supplied, or why not — the name of a lesson-bundle status such as
     * {@code SELECTED}, {@code NONE}, {@code LEARNING_OFF} or {@code UNAVAILABLE}. A review whose
     * retrieval was unavailable ran without lessons and is no lesson's evidence.
     */
    @Column(name = "lesson_selection", length = 16)
    private String lessonSelection;

    /** The exact lesson revisions supplied ({@code L17@1, L18@2}); {@code null} for none. */
    @Column(name = "lesson_revisions", length = 1000)
    private String lessonRevisions;

    /** SHA-256 of the guidance block the model received; {@code null} for none. */
    @Column(name = "lesson_context_hash", length = 64)
    private String lessonContextHash;

    @Column(nullable = false)
    private boolean complete;

    /** Why the evidence is incomplete; {@code null} when it is complete. */
    @Column(name = "incomplete_reason", length = 1024)
    private String incompleteReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
