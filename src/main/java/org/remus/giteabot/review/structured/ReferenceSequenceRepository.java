package org.remus.giteabot.review.structured;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReferenceSequenceRepository extends JpaRepository<ReferenceSequence, String> {

    /** Locks the counter row for the rest of the allocating transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ReferenceSequence s where s.name = :name")
    Optional<ReferenceSequence> findForUpdate(@Param("name") String name);
}
