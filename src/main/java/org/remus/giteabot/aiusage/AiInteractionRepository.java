package org.remus.giteabot.aiusage;

import org.remus.giteabot.workflowrun.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiInteractionRepository extends JpaRepository<AiInteraction, Long> {

    /** Successful interactions without the raw payload columns, which can be very large. */
    @Query(value = "select new org.remus.giteabot.aiusage.AiUsageRow(i.id, i.startedAt, i.aiIntegrationName, "
            + "i.sessionId, i.roundNumber, i.inputTokens, i.outputTokens, i.cacheCreationInputTokens, "
            + "i.cacheReadInputTokens) from AiInteraction i "
            + "where i.status = org.remus.giteabot.workflowrun.RunStatus.SUCCEEDED "
            + "and i.startedAt between :from and :to",
            countQuery = "select count(i) from AiInteraction i "
                    + "where i.status = org.remus.giteabot.workflowrun.RunStatus.SUCCEEDED "
                    + "and i.startedAt between :from and :to")
    Page<AiUsageRow> findUsageRows(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** Failed interactions without the raw payload columns. */
    @Query(value = "select new org.remus.giteabot.aiusage.AiErrorRow(i.id, i.startedAt, i.aiIntegrationName, "
            + "i.sessionId, i.errorMessage, i.stackTrace) from AiInteraction i "
            + "where i.status = org.remus.giteabot.workflowrun.RunStatus.ERROR "
            + "and i.startedAt between :from and :to",
            countQuery = "select count(i) from AiInteraction i "
                    + "where i.status = org.remus.giteabot.workflowrun.RunStatus.ERROR "
                    + "and i.startedAt between :from and :to")
    Page<AiErrorRow> findErrorRows(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query("select new org.remus.giteabot.aiusage.AiUsageRawPayload(i.rawRequest, i.rawResponse) "
            + "from AiInteraction i where i.id = :id")
    Optional<AiUsageRawPayload> findRawPayloadById(@Param("id") Long id);

    /**
     * Failed AI calls started after {@code after}: interactions with status {@code ERROR} that are
     * the last attempt of their round. A failed attempt that a later attempt of the same round
     * (a retry) replaced does not count.
     */
    @Query("select count(i) from AiInteraction i "
            + "where i.status = org.remus.giteabot.workflowrun.RunStatus.ERROR and i.startedAt > :after "
            + "and not exists (select 1 from AiInteraction later where later.workflowRun = i.workflowRun "
            + "and later.roundNumber = i.roundNumber and later.attempt > i.attempt)")
    long countFailedCallsStartedAfter(@Param("after") Instant after);

    @Query("select coalesce(sum(i.inputTokens), 0) from AiInteraction i")
    long sumInputTokens();

    @Query("select coalesce(sum(i.outputTokens), 0) from AiInteraction i")
    long sumOutputTokens();

    @Query("select coalesce(sum(i.inputTokens), 0) from AiInteraction i where i.aiIntegrationName = :name")
    long sumInputTokensByAiIntegrationName(@Param("name") String name);

    @Query("select coalesce(sum(i.outputTokens), 0) from AiInteraction i where i.aiIntegrationName = :name")
    long sumOutputTokensByAiIntegrationName(@Param("name") String name);

    @Query("select distinct i.aiIntegrationName from AiInteraction i")
    List<String> findDistinctAiIntegrationNames();

    /** Completes an attempt. */
    @Modifying
    @Transactional
    @Query("update AiInteraction i set i.status = :status, i.durationMs = :durationMs, i.stopReason = :stopReason, "
            + "i.inputTokens = :inputTokens, i.outputTokens = :outputTokens, "
            + "i.cacheCreationInputTokens = :cacheCreation, i.cacheReadInputTokens = :cacheRead, "
            + "i.errorMessage = :errorMessage, i.stackTrace = :stackTrace, "
            + "i.rawRequest = :rawRequest, i.rawResponse = :rawResponse where i.id = :id")
    void finish(@Param("id") Long id, @Param("status") RunStatus status,
               @Param("durationMs") long durationMs, @Param("stopReason") String stopReason,
               @Param("inputTokens") long inputTokens, @Param("outputTokens") long outputTokens,
               @Param("cacheCreation") long cacheCreation, @Param("cacheRead") long cacheRead,
               @Param("errorMessage") String errorMessage, @Param("stackTrace") String stackTrace,
               @Param("rawRequest") String rawRequest, @Param("rawResponse") String rawResponse);

    /** Marks every interaction that is still running as failed (startup recovery). */
    @Modifying
    @Transactional
    @Query("update AiInteraction i set i.status = org.remus.giteabot.workflowrun.RunStatus.ERROR, "
            + "i.errorMessage = :message "
            + "where i.status = org.remus.giteabot.workflowrun.RunStatus.RUNNING")
    int failAllRunning(@Param("message") String message);

    /** Deletes the interactions of every run that is not running. */
    @Modifying
    @Transactional
    @Query("delete from AiInteraction i where i.workflowRun.id in (select r.id from WorkflowRun r "
            + "where r.state <> org.remus.giteabot.workflowrun.RunStatus.RUNNING)")
    void deleteAllOfFinishedRuns();
}
