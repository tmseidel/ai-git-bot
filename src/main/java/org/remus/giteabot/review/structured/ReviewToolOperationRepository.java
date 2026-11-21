package org.remus.giteabot.review.structured;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewToolOperationRepository extends JpaRepository<ReviewToolOperation, Long> {

    Optional<ReviewToolOperation> findByReviewIdAndOperationId(Long reviewId, String operationId);
}
