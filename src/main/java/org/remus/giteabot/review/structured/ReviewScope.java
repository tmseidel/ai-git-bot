package org.remus.giteabot.review.structured;

/**
 * Persisted identity of one review draft. A tool call is only ever applied to the draft this
 * scope names; the executor compares it with the trusted invocation before touching anything.
 */
public record ReviewScope(
        long reviewId,
        String outputReference,
        Long botId,
        Long gitIntegrationId,
        String repoOwner,
        String repoName,
        Long prNumber,
        Long prWorkflowRunId,
        String invocationId,
        ReviewState state) {
}
