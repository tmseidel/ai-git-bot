package org.remus.giteabot.workflowrun;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {


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
