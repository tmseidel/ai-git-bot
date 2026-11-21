package org.remus.giteabot.learning.feedback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowFeedbackEventRepository extends JpaRepository<WorkflowFeedbackEvent, Long> {

    List<WorkflowFeedbackEvent> findByFeedbackIdOrderByIdAsc(Long feedbackId);

    Optional<WorkflowFeedbackEvent> findFirstByFeedbackIdAndEventTypeOrderByIdDesc(
            Long feedbackId, FeedbackEventType eventType);

    /**
     * Moves an acknowledgement from {@code expected} to {@code next}; the row count tells a caller
     * whether it won, so two concurrent redeliveries cannot both re-post a refused confirmation.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WorkflowFeedbackEvent e
            SET e.acknowledgement = :next, e.acknowledgedAt = :at
            WHERE e.id = :id AND e.acknowledgement = :expected
            """)
    int transitionAcknowledgement(@Param("id") Long id,
                                  @Param("expected") FeedbackAcknowledgement expected,
                                  @Param("next") FeedbackAcknowledgement next,
                                  @Param("at") Instant at);
}
