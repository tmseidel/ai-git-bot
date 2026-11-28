package org.remus.giteabot.workflowrun;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long>,
        JpaSpecificationExecutor<WorkflowRun> {

    /**
     * The repositories that have runs, as {@code owner/name} paths (filter options). Runs that
     * split the same path differently into owner and name are one project.
     */
    @Query("select distinct concat(r.repoOwner, '/', r.repoName) from WorkflowRun r "
            + "where r.repoOwner is not null and r.repoName is not null order by 1")
    List<String> findProjects();

    /** The workflow types that have runs (filter options). */
    @Query("select distinct r.workflowType from WorkflowRun r order by r.workflowType")
    List<String> findWorkflowTypes();

    /** The bots that have runs (filter options). */
    @Query("select distinct new org.remus.giteabot.workflowrun.BotOption(r.botId, r.botName) from WorkflowRun r "
            + "where r.botId is not null order by r.botName")
    List<BotOption> findBots();


    /** Ends a run. */
    @Modifying
    @Transactional
    @Query("update WorkflowRun r set r.state = :state, r.endedAt = :endedAt, r.errorMessage = :errorMessage, "
            + "r.roundLimitReached = :roundLimitReached where r.id = :id")
    void finish(@Param("id") Long id, @Param("state") RunStatus state, @Param("endedAt") Instant endedAt,
                @Param("errorMessage") String errorMessage, @Param("roundLimitReached") boolean roundLimitReached);

    /** Marks every run that is still running as failed (startup recovery). */
    @Modifying
    @Transactional
    @Query("update WorkflowRun r set r.state = org.remus.giteabot.workflowrun.RunStatus.ERROR, "
            + "r.endedAt = :endedAt, r.errorMessage = :message "
            + "where r.state = org.remus.giteabot.workflowrun.RunStatus.RUNNING")
    int failAllRunning(@Param("endedAt") Instant endedAt, @Param("message") String message);

    /** Deletes every run that is not running. Their interactions and tool calls must be deleted first. */
    @Modifying
    @Transactional
    @Query("delete from WorkflowRun r where r.state <> org.remus.giteabot.workflowrun.RunStatus.RUNNING")
    void deleteAllFinished();
}
