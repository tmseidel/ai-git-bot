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
 * Feedback a lesson revision rests on. The feedback row is referenced by foreign key, so its current
 * state — effective, superseded, retracted — is always read from the source rather than copied, and
 * a retraction is visible on every lesson that cites it.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "knowledge_lesson_evidence",
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_lesson_evidence_revision_feedback",
                columnNames = {"revision_id", "feedback_id"}),
        indexes = {
                @Index(name = "idx_knowledge_lesson_evidence_feedback", columnList = "feedback_id"),
                @Index(name = "idx_knowledge_lesson_evidence_lesson", columnList = "lesson_id")
        })
public class KnowledgeLessonEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(name = "feedback_id", nullable = false)
    private Long feedbackId;

    /** {@code FB…}, kept for readable history without a join. */
    @Column(name = "feedback_reference", nullable = false, length = 32)
    private String feedbackReference;

    /** What the feedback judged ({@code O1234-F2}); lessons sharing a target may conflict. */
    @Column(name = "target_reference", nullable = false, length = 32)
    private String targetReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
