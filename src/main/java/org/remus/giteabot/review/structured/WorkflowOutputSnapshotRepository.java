package org.remus.giteabot.review.structured;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowOutputSnapshotRepository extends JpaRepository<WorkflowOutputSnapshot, Long> {

    Optional<WorkflowOutputSnapshot> findByReviewId(Long reviewId);

    /** Bulk evidence past its retention date; the review record itself is kept. */
    List<WorkflowOutputSnapshot> findByCreatedAtBefore(Instant cutoff);
}
