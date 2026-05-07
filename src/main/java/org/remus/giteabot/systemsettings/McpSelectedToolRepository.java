package org.remus.giteabot.systemsettings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface McpSelectedToolRepository extends JpaRepository<McpSelectedTool, Long> {

    List<McpSelectedTool> findByMcpConfigurationIdOrderByServerNameAscToolNameAsc(Long mcpConfigurationId);

    List<McpSelectedTool> findByMcpConfigurationId(Long mcpConfigurationId);

    void deleteByMcpConfigurationId(Long mcpConfigurationId);
}

