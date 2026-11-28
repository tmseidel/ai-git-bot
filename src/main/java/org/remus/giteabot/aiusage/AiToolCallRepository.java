package org.remus.giteabot.aiusage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface AiToolCallRepository extends JpaRepository<AiToolCall, Long> {

    /** The tool calls of one interaction, in order. */
    List<AiToolCall> findByInteraction_IdOrderBySequence(Long interactionId);

    /** The tool calls of the given runs, in interaction and sequence order (export). */
    @Query("select t from AiToolCall t where t.interaction.workflowRun.id in :runIds "
            + "order by t.interaction.id, t.sequence")
    List<AiToolCall> findByRunIds(@Param("runIds") Collection<Long> runIds);

    /** Tool call totals per interaction of the given runs; interactions without tool calls are missing. */
    @Query("select new org.remus.giteabot.aiusage.ToolTotals(t.interaction.id, count(t), "
            + "sum(case when t.success = false then 1L else 0L end), coalesce(sum(t.durationMs), 0L)) "
            + "from AiToolCall t where t.interaction.workflowRun.id in :runIds group by t.interaction.id")
    List<ToolTotals> totalsByInteraction(@Param("runIds") Collection<Long> runIds);

    /** Deletes the tool calls of every run that is not running. */
    @Modifying
    @Transactional
    @Query("delete from AiToolCall t where t.interaction.id in (select i.id from AiInteraction i "
            + "where i.workflowRun.id in (select r.id from WorkflowRun r "
            + "where r.state <> org.remus.giteabot.workflowrun.RunStatus.RUNNING))")
    void deleteAllOfFinishedRuns();
}
