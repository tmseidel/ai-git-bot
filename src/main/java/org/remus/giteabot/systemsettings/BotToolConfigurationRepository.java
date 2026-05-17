package org.remus.giteabot.systemsettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BotToolConfigurationRepository extends JpaRepository<BotToolConfiguration, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    Optional<BotToolConfiguration> findByDefaultEntryTrue();
}
