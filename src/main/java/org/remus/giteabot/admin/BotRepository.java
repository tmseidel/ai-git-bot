package org.remus.giteabot.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

@Repository
public interface BotRepository extends JpaRepository<Bot, Long> {
    @Query("SELECT b FROM Bot b LEFT JOIN FETCH b.aiIntegration LEFT JOIN FETCH b.gitIntegration LEFT JOIN FETCH b.systemPrompt LEFT JOIN FETCH b.mcpConfiguration LEFT JOIN FETCH b.toolConfiguration LEFT JOIN FETCH b.workflowConfiguration LEFT JOIN FETCH b.issueWorkflowConfiguration WHERE b.webhookSecret = :secret")
    Optional<Bot> findByWebhookSecret(@Param("secret") String webhookSecret);

    @Query("SELECT b FROM Bot b LEFT JOIN FETCH b.aiIntegration LEFT JOIN FETCH b.gitIntegration LEFT JOIN FETCH b.systemPrompt LEFT JOIN FETCH b.mcpConfiguration LEFT JOIN FETCH b.toolConfiguration LEFT JOIN FETCH b.workflowConfiguration LEFT JOIN FETCH b.issueWorkflowConfiguration")
    List<Bot> findAllWithIntegrations();

    @Query("SELECT b FROM Bot b LEFT JOIN FETCH b.aiIntegration LEFT JOIN FETCH b.gitIntegration LEFT JOIN FETCH b.systemPrompt LEFT JOIN FETCH b.mcpConfiguration LEFT JOIN FETCH b.toolConfiguration LEFT JOIN FETCH b.workflowConfiguration LEFT JOIN FETCH b.issueWorkflowConfiguration WHERE b.id = :id")
    Optional<Bot> findByIdWithIntegrations(@Param("id") Long id);

    List<Bot> findBySystemPromptId(Long systemPromptId);

    List<Bot> findByMcpConfigurationId(Long mcpConfigurationId);

    List<Bot> findByToolConfigurationId(Long toolConfigurationId);

    List<Bot> findByWorkflowConfigurationId(Long workflowConfigurationId);

    List<Bot> findByIssueWorkflowConfigurationId(Long issueWorkflowConfigurationId);

    boolean existsByName(String name);

    boolean existsByGitIntegrationId(Long gitIntegrationId);

    @Modifying
    @Query("update Bot b set b.webhookCallCount = b.webhookCallCount + 1, b.lastWebhookAt = :at where b.id = :id")
    int incrementWebhookCallCount(Long id, Instant at);

    @Modifying
    @Query("update Bot b set b.lastErrorMessage = :message, b.lastErrorAt = :at where b.id = :id")
    int recordError(Long id, String message, Instant at);
}
