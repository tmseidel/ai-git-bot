package org.remus.giteabot.prworkflow;

/**
 * Lifecycle status of a {@link PrWorkflowRun}.
 *
 * <p>Status transitions are managed by {@link PrWorkflowRunService}:</p>
 * <pre>
 *   QUEUED ──▶ RUNNING ──▶ SUCCESS
 *                    │
 *                    ├──▶ FAILED
 *                    │
 *                    └──▶ WAITING_DEPLOY ──▶ RUNNING ──▶ …
 *
 *   * ──▶ CANCELLED        (when a newer run for the same PR supersedes this one)
 * </pre>
 *
 * <p>{@link #WAITING_DEPLOY} is introduced for milestone M3 (deployment
 * callbacks). In M1 only {@link #QUEUED}, {@link #RUNNING}, {@link #SUCCESS},
 * {@link #FAILED} and {@link #CANCELLED} are produced.</p>
 */
public enum PrWorkflowRunStatus {
    QUEUED,
    RUNNING,
    WAITING_DEPLOY,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == QUEUED || this == RUNNING || this == WAITING_DEPLOY;
    }
}

