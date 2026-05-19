package org.remus.giteabot.prworkflow.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentTargetRepository extends JpaRepository<DeploymentTarget, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
