package org.remus.giteabot.audit;

/**
 * All known audit event types. Types whose underlying product action does not
 * yet exist are documented as deferred here; their audit events will be emitted
 * when the feature is implemented.
 */
public enum AuditEventType {
    /** Workflow lifecycle — implemented. */
    PR_WORKFLOW_RUN_STARTED,
    PR_WORKFLOW_STEP_APPENDED,
    PR_WORKFLOW_RUN_COMPLETED,

    /** Agent tool-call tracing — implemented. */
    TOOL_CALL_EXECUTED,

    /** Review — implemented. */
    REVIEW_COMPLETED,

    /** Finding posted as part of a review — implemented (v1: one event per posted review). */
    FINDING_POSTED,

    /** Deferred: not yet in product. */
    @Deprecated GATE_OVERRIDDEN,
    @Deprecated FINDING_SUPPRESSED,
    @Deprecated PULL_REQUEST_MERGED,
    ;

    /** True when this event type has a corresponding emission site in the codebase. */
    public boolean isImplemented() {
        return switch (this) {
            case PR_WORKFLOW_RUN_STARTED, PR_WORKFLOW_STEP_APPENDED,
                 PR_WORKFLOW_RUN_COMPLETED, TOOL_CALL_EXECUTED, REVIEW_COMPLETED,
                 FINDING_POSTED -> true;
            default -> false;
        };
    }
}
