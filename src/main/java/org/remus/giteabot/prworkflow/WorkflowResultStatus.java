package org.remus.giteabot.prworkflow;

/**
 * Outcome of one {@link PrWorkflow#run(PrWorkflowContext)} invocation. Maps
 * directly onto the persisted {@link PrWorkflowRunStatus} but is intentionally
 * narrower so workflow implementations cannot produce intermediate or
 * orchestrator-only states (such as {@link PrWorkflowRunStatus#QUEUED} or
 * {@link PrWorkflowRunStatus#CANCELLED}).
 */
public enum WorkflowResultStatus {
    /** Workflow finished successfully. */
    SUCCESS,
    /** Workflow finished but produced a negative outcome (e.g. tests failed). */
    FAILED,
    /**
     * Workflow handed off to an external system (M3+) and is awaiting a
     * callback. The orchestrator persists {@link PrWorkflowRunStatus#WAITING_DEPLOY}.
     */
    WAITING_DEPLOY,
    /**
     * Workflow was a no-op for this event (e.g. bot type incompatible,
     * payload not eligible). The orchestrator records the run but does
     * not surface it as an error in metrics.
     */
    SKIPPED
}

