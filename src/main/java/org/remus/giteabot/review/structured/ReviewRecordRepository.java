package org.remus.giteabot.review.structured;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {

    Optional<ReviewRecord> findByOutputId(Long outputId);

    /** The review of an output, row-locked until the transaction ends: serializes its deliveries. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReviewRecord r where r.output.id = :outputId")
    Optional<ReviewRecord> findForUpdateByOutputId(@Param("outputId") Long outputId);
}
