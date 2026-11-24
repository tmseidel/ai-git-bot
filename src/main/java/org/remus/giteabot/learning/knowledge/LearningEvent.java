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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.learning.feedback.FeedbackAcknowledgement;

import java.time.Instant;

/**
 * Append-only history of lessons: who proposed, revised, activated, disabled or rejected which
 * revision, and why. This is the application's own record; it does not rely on any PR audit chain.
 *
 * <p>Events caused by a Git comment carry that comment's normalized {@code sourceKey}, which is
 * unique: a redelivered webhook finds its own event instead of acting twice. {@code acknowledgement}
 * tracks the bot's confirmation comment exactly like feedback confirmations.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "learning_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_learning_events_source",
                columnNames = "source_key"),
        indexes = @Index(name = "idx_learning_events_lesson", columnList = "lesson_id"))
public class LearningEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    /** The revision the transition concerns, when it concerns one. */
    @Column(name = "revision_number")
    private Integer revisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private LessonEventType eventType;

    @Column(nullable = false, length = 255)
    private String actor;

    @Column(length = 1000)
    private String detail;

    /** E.g. the feedback that was retracted, or the revision that replaced this one. */
    @Column(name = "related_reference", length = 32)
    private String relatedReference;

    @Column(name = "source_key", length = 64)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FeedbackAcknowledgement acknowledgement;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
