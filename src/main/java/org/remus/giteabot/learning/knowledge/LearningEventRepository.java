package org.remus.giteabot.learning.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.remus.giteabot.learning.feedback.FeedbackAcknowledgement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LearningEventRepository extends JpaRepository<LearningEvent, Long> {

    List<LearningEvent> findByLessonIdOrderByIdAsc(Long lessonId);

    Optional<LearningEvent> findBySourceKey(String sourceKey);

    /**
     * Moves a confirmation from {@code expected} to {@code next}; the row count tells a caller
     * whether it won, so two concurrent redeliveries cannot both re-post a refused confirmation.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LearningEvent e
            SET e.acknowledgement = :next, e.acknowledgedAt = :at
            WHERE e.id = :id AND e.acknowledgement = :expected
            """)
    int transitionAcknowledgement(@Param("id") Long id,
                                  @Param("expected") FeedbackAcknowledgement expected,
                                  @Param("next") FeedbackAcknowledgement next,
                                  @Param("at") Instant at);
}
