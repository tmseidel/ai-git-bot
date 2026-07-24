package org.remus.giteabot.aiusage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {

    Page<AiUsageLog> findByTimestampBetween(Instant from, Instant to, Pageable pageable);

    @Query("select coalesce(sum(u.inputTokens), 0) from AiUsageLog u")
    long sumInputTokens();

    @Query("select coalesce(sum(u.outputTokens), 0) from AiUsageLog u")
    long sumOutputTokens();

    @Query("select coalesce(sum(u.inputTokens), 0) from AiUsageLog u where u.aiIntegrationName = :name")
    long sumInputTokensByAiIntegrationName(@Param("name") String name);

    @Query("select coalesce(sum(u.outputTokens), 0) from AiUsageLog u where u.aiIntegrationName = :name")
    long sumOutputTokensByAiIntegrationName(@Param("name") String name);

    @Query("select distinct u.aiIntegrationName from AiUsageLog u")
    List<String> findDistinctAiIntegrationNames();
}
