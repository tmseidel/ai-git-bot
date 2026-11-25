package org.remus.giteabot.learning.knowledge;

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
 * One delivery of a lesson revision into an agentic review. Lesson retrieval writes these rows;
 * listings count them. A count says how often guidance was supplied — it is adoption, not evidence
 * that the guidance improved anything. One invocation records each revision at most once.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "lesson_usage",
        uniqueConstraints = @UniqueConstraint(name = "uk_lesson_usage_invocation_revision",
                columnNames = {"invocation_id", "revision_id"}),
        indexes = @Index(name = "idx_lesson_usage_lesson", columnList = "lesson_id, revision_id"))
public class LessonUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    /** The agentic-review output that received the guidance, once it exists. */
    @Column(name = "output_id")
    private Long outputId;

    @Column(name = "invocation_id", nullable = false, length = 36)
    private String invocationId;

    @Column(name = "selection_reason", length = 500)
    private String selectionReason;

    @Column(name = "prompt_block_hash", length = 64)
    private String promptBlockHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
