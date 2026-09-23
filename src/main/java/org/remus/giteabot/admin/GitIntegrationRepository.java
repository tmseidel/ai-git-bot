package org.remus.giteabot.admin;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GitIntegrationRepository extends JpaRepository<GitIntegration, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from GitIntegration i where i.id = :id")
    Optional<GitIntegration> findByIdForUpdate(Long id);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
