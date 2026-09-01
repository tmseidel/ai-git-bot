package org.remus.giteabot.admin;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GitIntegrationRepository extends JpaRepository<GitIntegration, Long> {
    /** Locks an integration so assignment and lifecycle transitions share one database ordering. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GitIntegration g WHERE g.id = :id")
    Optional<GitIntegration> findByIdForUpdate(@Param("id") Long id);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
