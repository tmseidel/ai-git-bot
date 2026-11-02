package org.remus.giteabot.prworkflow;

/**
 * Lifecycle status of a {@link PrWorkflowRun}.
 *
 * <p>Rows are inserted directly in {@link #RUNNING} by
 * {@link PrWorkflowRunService#start} — there is intentionally no
 * {@code QUEUED} intermediate state, because the orchestrator owns the
 * transition from "webhook received" to "workflow executing" and there is
 * no separate scheduler that could observe a pre-execution row.</p>
 *
 * <p>Status transitions managed by {@link PrWorkflowRunService}:</p>
 * <pre>
 *   [*] ──▶ RUNNING ──▶ SUCCESS
 *                  │
 *                  ├──▶ FAILED
 *                  │
 *                  └──▶ WAITING_DEPLOY ──▶ RUNNING ──▶ …
 *
 *   * ──▶ CANCELLED        (when a newer run for the same PR supersedes this one)
 * </pre>
 *
 * <p>{@link #WAITING_DEPLOY} is introduced for milestone M3 (deployment
 * callbacks). In M1 only {@link #RUNNING}, {@link #SUCCESS}, {@link #FAILED}
 * and {@link #CANCELLED} are produced.</p>
 */
public enum PrWorkflowRunStatus {
    RUNNING,
    WAITING_DEPLOY,
    SUCCESS,
    /** Terminal diagnostic result that can never authorize publication or formal actions. */
    SHADOW,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == SHADOW || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == RUNNING || this == WAITING_DEPLOY;
    }
}
