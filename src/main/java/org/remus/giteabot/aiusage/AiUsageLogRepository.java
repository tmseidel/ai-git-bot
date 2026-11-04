package org.remus.giteabot.aiusage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {

    /** The Usage table rows without the raw payload columns, which can be very large. */
    @Query(value = "select new org.remus.giteabot.aiusage.AiUsageRow(u.id, u.timestamp, u.aiIntegrationName, "
            + "u.sessionId, u.round, u.inputTokens, u.outputTokens, u.cacheCreationInputTokens, "
            + "u.cacheReadInputTokens) from AiUsageLog u where u.timestamp between :from and :to",
            countQuery = "select count(u) from AiUsageLog u where u.timestamp between :from and :to")
    Page<AiUsageRow> findRowsByTimestampBetween(@Param("from") Instant from, @Param("to") Instant to,
                                               Pageable pageable);

    @Query("select new org.remus.giteabot.aiusage.AiUsageRawPayload(u.rawRequest, u.rawResponse) "
            + "from AiUsageLog u where u.id = :id")
    Optional<AiUsageRawPayload> findRawPayloadById(@Param("id") Long id);

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
