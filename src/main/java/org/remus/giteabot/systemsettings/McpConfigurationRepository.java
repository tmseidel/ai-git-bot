package org.remus.giteabot.systemsettings;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface McpConfigurationRepository extends JpaRepository<McpConfiguration, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select configuration from McpConfiguration configuration where configuration.id = :id")
    Optional<McpConfiguration> findByIdForUpdate(Long id);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
