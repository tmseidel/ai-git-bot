package org.remus.giteabot.review.structured;

/**
 * Trusted invocation scope for a new review draft. Every field is supplied by the calling
 * service from the webhook/run context — none of it is model-writable.
 */
public record ReviewDraftRequest(
        Long gitIntegrationId,
        Long botId,
        String repoOwner,
        String repoName,
        String providerRepositoryId,
        Long prNumber,
        Long prWorkflowRunId,
        String invocationId,
        String baseSha,
        String headSha) {
}
