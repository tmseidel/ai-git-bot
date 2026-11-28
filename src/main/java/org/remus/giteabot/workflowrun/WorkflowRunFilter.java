package org.remus.giteabot.workflowrun;

import java.time.Instant;

/**
 * The filters of the Workflow runs page; every {@code null} (or {@code false}) filter matches
 * every run.
 *
 * @param runId        exact run id
 * @param project      repository as {@code owner/name}; the owner may contain {@code /}
 * @param number       pull request or issue number
 * @param workflowType workflow key (e.g. {@code agentic-review})
 * @param botId        id of the bot
 * @param state        run state
 * @param onlyWarnings only runs that reached the round limit or have a truncated output
 * @param onlyFailedCalls only runs with a failed AI call: an interaction that failed and was not
 *                        retried, i.e. the last attempt of its round failed
 * @param startedFrom  runs that started at or after this instant
 * @param startedUntil runs that started before this instant
 */
public record WorkflowRunFilter(Long runId,
                                String project,
                                Long number,
                                String workflowType,
                                Long botId,
                                RunStatus state,
                                boolean onlyWarnings,
                                boolean onlyFailedCalls,
                                Instant startedFrom,
                                Instant startedUntil) {

    /** No filter: matches every run. */
    public static WorkflowRunFilter none() {
        return new WorkflowRunFilter(null, null, null, null, null, null, false, false, null, null);
    }

    /** The same filter with another state (the state button counts ignore the selected state). */
    public WorkflowRunFilter withState(RunStatus newState) {
        return new WorkflowRunFilter(runId, project, number, workflowType, botId, newState, onlyWarnings,
                onlyFailedCalls, startedFrom, startedUntil);
    }
}
