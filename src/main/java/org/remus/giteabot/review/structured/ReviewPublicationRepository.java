package org.remus.giteabot.review.structured;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewPublicationRepository extends JpaRepository<ReviewPublication, Long> {

    Optional<ReviewPublication> findTopByReviewIdOrderByRenderRevisionDesc(Long reviewId);

    List<ReviewPublication> findByReviewIdOrderByRenderRevisionAsc(Long reviewId);

    boolean existsByReviewIdAndState(Long reviewId, ReviewPublicationState state);
}
