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
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only history of a feedback record: who submitted, superseded or retracted it, and when.
 *
 * <p>{@code acknowledgement} is set on events the bot confirms in a comment (submission and
 * retraction) and tracks that confirmation, so a redelivered webhook neither posts a second
 * confirmation nor re-posts one whose delivery is uncertain.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_feedback_events",
        indexes = @Index(name = "idx_workflow_feedback_events_feedback",
                columnList = "feedback_id, event_type"))
public class WorkflowFeedbackEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feedback_id", nullable = false)
    private Long feedbackId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private FeedbackEventType eventType;

    @Column(nullable = false, length = 255)
    private String actor;

    /** The feedback reference that caused a {@code SUPERSEDED} event. */
    @Column(name = "related_reference", length = 32)
    private String relatedReference;

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
