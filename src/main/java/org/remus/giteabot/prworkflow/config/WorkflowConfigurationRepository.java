package org.remus.giteabot.prworkflow.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowConfigurationRepository extends JpaRepository<WorkflowConfiguration, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<WorkflowConfiguration> findByKind(WorkflowConfigurationKind kind);

    Optional<WorkflowConfiguration> findByKindAndDefaultEntryTrue(WorkflowConfigurationKind kind);

    Optional<WorkflowConfiguration> findByName(String name);
}

