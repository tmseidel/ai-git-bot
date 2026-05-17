package org.remus.giteabot.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotRepository extends JpaRepository<Bot, Long> {
    @Query("SELECT b FROM Bot b LEFT JOIN FETCH b.aiIntegration LEFT JOIN FETCH b.gitIntegration LEFT JOIN FETCH b.systemPrompt LEFT JOIN FETCH b.mcpConfiguration LEFT JOIN FETCH b.toolConfiguration WHERE b.webhookSecret = :secret")
    Optional<Bot> findByWebhookSecret(@Param("secret") String webhookSecret);

    @Query("SELECT b FROM Bot b LEFT JOIN FETCH b.aiIntegration LEFT JOIN FETCH b.gitIntegration LEFT JOIN FETCH b.systemPrompt LEFT JOIN FETCH b.mcpConfiguration LEFT JOIN FETCH b.toolConfiguration")
    List<Bot> findAllWithIntegrations();

    @Query("SELECT b FROM Bot b LEFT JOIN FETCH b.aiIntegration LEFT JOIN FETCH b.gitIntegration LEFT JOIN FETCH b.systemPrompt LEFT JOIN FETCH b.mcpConfiguration LEFT JOIN FETCH b.toolConfiguration WHERE b.id = :id")
    Optional<Bot> findByIdWithIntegrations(@Param("id") Long id);

    List<Bot> findBySystemPromptId(Long systemPromptId);

    long countBySystemPromptId(Long systemPromptId);

    List<Bot> findByMcpConfigurationId(Long mcpConfigurationId);

    List<Bot> findByToolConfigurationId(Long toolConfigurationId);

    boolean existsByName(String name);
}
