package org.remus.giteabot.repository;

/**
 * Provider-agnostic status of a CI workflow run / pipeline returned by
 * {@link RepositoryApiClient#getWorkflowRun(String, String, String)}.
 *
 * <p>Maps each provider's native state onto five categories the bot's
 * {@code CiActionPoller} reasons about:</p>
 * <ul>
 *     <li>{@link #QUEUED} – accepted by the provider, not yet started.</li>
 *     <li>{@link #IN_PROGRESS} – currently executing.</li>
 *     <li>{@link #COMPLETED_SUCCESS} – terminal, succeeded.</li>
 *     <li>{@link #COMPLETED_FAILURE} – terminal, failed / cancelled / timed out.</li>
 *     <li>{@link #NOT_FOUND} – the provider no longer knows about this run id
 *         (deleted, retention expired, wrong project). The poller treats this
 *         as a terminal failure.</li>
 * </ul>
 */
public enum WorkflowRunStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED_SUCCESS,
    COMPLETED_FAILURE,
    NOT_FOUND;

    public boolean isTerminal() {
        return this == COMPLETED_SUCCESS || this == COMPLETED_FAILURE || this == NOT_FOUND;
    }
}

