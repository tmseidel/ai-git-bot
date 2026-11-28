package org.remus.giteabot.review.structured;

/**
 * Trusted invocation scope for a new review draft. Every field is supplied by the calling
 * service from the webhook/run context — none of it is model-writable.
 *
 * <p>The pull request's author and title and the size of the reviewed diff are facts for the
 * Review quality page; they are optional and never shown to the model.</p>
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
        String headSha,
        String prAuthor,
        String prTitle,
        Integer diffFiles,
        Integer diffAdditions,
        Integer diffDeletions) {

    /** A request without the pull-request facts of the Review quality page. */
    public ReviewDraftRequest(Long gitIntegrationId, Long botId, String repoOwner, String repoName,
                              String providerRepositoryId, Long prNumber, Long prWorkflowRunId,
                              String invocationId, String baseSha, String headSha) {
        this(gitIntegrationId, botId, repoOwner, repoName, providerRepositoryId, prNumber,
                prWorkflowRunId, invocationId, baseSha, headSha, null, null, null, null, null);
    }
}
