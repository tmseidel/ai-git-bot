package org.remus.giteabot.systemsettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BotToolSelectionRepository extends JpaRepository<BotToolSelection, Long> {

    List<BotToolSelection> findByConfigurationIdOrderByToolKindAscToolNameAsc(Long configurationId);

    List<BotToolSelection> findByConfigurationId(Long configurationId);

    void deleteByConfigurationId(Long configurationId);
}
