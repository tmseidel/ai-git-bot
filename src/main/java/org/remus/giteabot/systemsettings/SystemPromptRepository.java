package org.remus.giteabot.systemsettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemPromptRepository extends JpaRepository<SystemPrompt, Long> {

    Optional<SystemPrompt> findByDefaultEntryTrue();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

}
