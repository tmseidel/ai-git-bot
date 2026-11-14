package org.remus.giteabot.review.structured;

import java.util.function.BooleanSupplier;

/**
 * Trusted scope of one agentic-review invocation: the draft the review tools may write, the
 * bot/repository/run the caller was dispatched for, and whether the run was cancelled.
 *
 * <p>Constructed only by the review service from the run context — never from tool arguments.
 * A tool call can therefore name neither the review it writes nor the repository it belongs to.</p>
 */
public record ReviewInvocation(
        long reviewId,
        Long botId,
        Long gitIntegrationId,
        String repoOwner,
        String repoName,
        Long prNumber,
        Long prWorkflowRunId,
        BooleanSupplier cancelled) {

    /** Whether the run was cancelled; a cancelled run applies no further mutations. */
    public boolean isCancelled() {
        return cancelled != null && cancelled.getAsBoolean();
    }
}
