package org.remus.giteabot.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiIntegrationRepository extends JpaRepository<AiIntegration, Long> {
    Optional<AiIntegration> findByName(String name);
}
