package org.remus.giteabot.prworkflow;

import java.util.Objects;

/**
 * Result returned by {@link PrWorkflow#run(PrWorkflowContext)}.
 *
 * <p>The {@link #summary()} is persisted on the {@link PrWorkflowRun} row so
 * the dashboard (M2+) can render a one-line outcome without re-reading the
 * workflow logs. Keep it short — long-form output belongs in
 * {@link PrWorkflowContext#appendStep(String, String)}.</p>
 */
public record WorkflowResult(WorkflowResultStatus status, String summary) {

    public WorkflowResult {
        Objects.requireNonNull(status, "status");
    }

    public static WorkflowResult success(String summary) {
        return new WorkflowResult(WorkflowResultStatus.SUCCESS, summary);
    }

    public static WorkflowResult failed(String summary) {
        return new WorkflowResult(WorkflowResultStatus.FAILED, summary);
    }

    public static WorkflowResult skipped(String summary) {
        return new WorkflowResult(WorkflowResultStatus.SKIPPED, summary);
    }

    public static WorkflowResult waitingDeploy(String summary) {
        return new WorkflowResult(WorkflowResultStatus.WAITING_DEPLOY, summary);
    }
}

