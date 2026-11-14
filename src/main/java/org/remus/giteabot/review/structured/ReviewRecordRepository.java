package org.remus.giteabot.review.structured;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {

    Optional<ReviewRecord> findByOutputId(Long outputId);
}
