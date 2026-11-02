package org.remus.giteabot.prworkflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PrWorkflowRunRepository extends JpaRepository<PrWorkflowRun, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PrWorkflowRun r set r.reviewHeadSha = :head, r.reviewBaseSha = :base, r.reviewDiffHash = :diff
            where r.id = :id and r.status = org.remus.giteabot.prworkflow.PrWorkflowRunStatus.RUNNING
            and r.reviewHeadSha is null and r.reviewBaseSha is null and r.reviewDiffHash is null
            """)
    int captureReviewRevision(Long id, String head, String base, String diff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PrWorkflowRun r set r.status = :status, r.finishedAt = :finishedAt, r.summary = :summary
            where r.id = :id and r.status in (org.remus.giteabot.prworkflow.PrWorkflowRunStatus.RUNNING,
            org.remus.giteabot.prworkflow.PrWorkflowRunStatus.WAITING_DEPLOY)
            """)
    int finishIfActive(Long id, PrWorkflowRunStatus status, Instant finishedAt, String summary);

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
