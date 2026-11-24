package org.remus.giteabot.aiusage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AiToolCallRepository extends JpaRepository<AiToolCall, Long> {

    /** Deletes the tool calls of every run that is not running. */
    @Modifying
    @Transactional
    @Query("delete from AiToolCall t where t.interaction.id in (select i.id from AiInteraction i "
            + "where i.workflowRun.id in (select r.id from WorkflowRun r "
            + "where r.state <> org.remus.giteabot.workflowrun.RunStatus.RUNNING))")
    void deleteAllOfFinishedRuns();
}
