package org.remus.giteabot.prworkflow;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PrWorkflowRunRepository extends JpaRepository<PrWorkflowRun, Long> {

    /** Serializes required completion/audit/outbox writers within the caller's transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PrWorkflowRun r WHERE r.id = :id")
    Optional<PrWorkflowRun> findByIdForUpdate(@Param("id") Long id);

    List<PrWorkflowRun> findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
            Long botId,
            String repoOwner,
            String repoName,
            Long prNumber,
            String workflowKey,
            List<PrWorkflowRunStatus> statuses);

    /**
     * Lists runs currently in the given status, ordered by oldest first.
     * Used by {@link org.remus.giteabot.prworkflow.deployment.CiActionPoller}
     * to fan-out polling across in-flight {@code CI_ACTION} deployments.
     */
    List<PrWorkflowRun> findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus status);

    /**
     * M7 promotion GC: lists every run that already produced a follow-up
     * PR ({@code followUpPrNumber} set) and finished before the cutoff.
     * Used by {@link org.remus.giteabot.prworkflow.e2e.promotion.PromotedSuiteGarbageCollector}
     * to retire stale generated suites while keeping the promoted PR
     * itself untouched.
     */
    List<PrWorkflowRun> findByFollowUpPrNumberIsNotNullAndFinishedAtBefore(Instant cutoff);
}
