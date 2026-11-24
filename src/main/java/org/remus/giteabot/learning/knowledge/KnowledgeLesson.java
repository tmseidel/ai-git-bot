package org.remus.giteabot.learning.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One reusable piece of review guidance, owned by exactly one Git integration, bot, repository and
 * workflow ({@code agentic-review}).
 *
 * <p>The text lives in immutable {@link KnowledgeLessonRevision revisions}; this row holds the
 * identity, the scope and the pointer to the revision that is currently active. Every state change
 * goes through this row, and its {@link #version} serializes them: two maintainers activating or
 * revising the same lesson at once cannot both win.</p>
 *
 * <p>Scope is stored independently of the replaceable workflow-configuration rows, so changing,
 * cloning or deleting a configuration never moves, merges or deletes knowledge.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "knowledge_lessons",
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_lessons_reference",
                columnNames = "reference"),
        indexes = @Index(name = "idx_knowledge_lessons_scope",
                columnList = "git_integration_id, bot_id, repo_owner, repo_name, workflow_key, state"))
public class KnowledgeLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public {@code L<n>} reference. */
    @Column(nullable = false, length = 32)
    private String reference;

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

    @Column(name = "workflow_key", nullable = false, length = 64)
    private String workflowKey;

    /** Summary state, maintained with every transition; drives listing and filtering. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LessonState state = LessonState.CANDIDATE;

    /** Highest revision number; only this revision can be activated. */
    @Column(name = "latest_revision", nullable = false)
    private int latestRevision;

    /** The revision later reviews may receive, or {@code null}. */
    @Column(name = "active_revision")
    private Integer activeRevision;

    /** Set when cited feedback was retracted or replaced; cleared by a maintainer's decision. */
    @Column(name = "review_required", nullable = false)
    private boolean reviewRequired;

    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    /** Marks this row changed, so the optimistic lock covers transitions that touch only revisions. */
    void touch() {
        updatedAt = Instant.now();
    }
}
