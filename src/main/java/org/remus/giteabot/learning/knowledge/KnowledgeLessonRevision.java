package org.remus.giteabot.learning.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One immutable wording and scope of a lesson ({@code L17@2}).
 *
 * <p>Text, path scope, source pin and recheck date never change after the row is written; only the
 * lifecycle columns do. Editing a lesson — wording or scope, narrowing or widening — always creates
 * the next revision as a new candidate, so an activation always names exactly the text a maintainer
 * read, and an earlier review's guidance stays reconstructible.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "knowledge_lesson_revisions",
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_lesson_revisions_number",
                columnNames = {"lesson_id", "revision_number"}))
public class KnowledgeLessonRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LessonState state = LessonState.CANDIDATE;

    @Column(name = "lesson_text", nullable = false, length = 2000)
    private String text;

    /** One repository-relative glob per line; {@code null} means every path of the repository. */
    @Column(name = "path_scopes", length = 2100)
    private String pathScopes;

    /** Category of the finding the evidence judged, kept as a relevance tag. */
    @Column(length = 64)
    private String category;

    /** Head commit of the review the evidence judged: the source the lesson was written against. */
    @Column(name = "source_commit_sha", length = 64)
    private String sourceCommitSha;

    /** After this, the lesson is due for a maintainer's recheck against the current code. */
    @Column(name = "recheck_after")
    private Instant recheckAfter;

    /** SHA-256 of text and scope, so usage records can prove which wording was supplied. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "activation_provenance", length = 32)
    private LessonActivationProvenance activationProvenance;

    @Column(name = "activated_by", length = 255)
    private String activatedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /** Who or what ended this revision (disabled, rejected, superseded, marked stale), and when. */
    @Column(name = "ended_by", length = 255)
    private String endedBy;

    @Column(name = "ended_at")
    private Instant endedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
