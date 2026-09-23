package org.remus.giteabot.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.ai.AiRetryContext;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the comment target of provider-overload retry notices: the pull
 * request or issue a running workflow belongs to.
 *
 * <p>Orchestrators hand over the repository coordinates they already resolved
 * for the run, so neither {@link GiteaClientFactory} nor
 * {@link RepositoryApiClient} has to appear in the orchestration layer.</p>
 *
 * <p>Best-effort by design, matching {@link AiRetryContext}: without a
 * resolvable target (or with incomplete coordinates) the retry still happens,
 * it just cannot be reported as a comment.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowRetryNotices {

    private final GiteaClientFactory giteaClientFactory;

    /** Points the retry notices of a PR workflow run at the pull request it belongs to. */
    public void installForPullRequest(Bot bot, String workflowKey,
                                      String owner, String repoName, Long prNumber) {
        if (owner == null || repoName == null || prNumber == null) {
            return;
        }
        resolveClient(bot, workflowKey).ifPresent(client ->
                AiRetryContext.install(new AiRetryContext.Notice(workflowKey,
                        body -> client.postPullRequestComment(owner, repoName, prNumber, body))));
    }

    /** Points the retry notices of an issue workflow run at the issue it belongs to. */
    public void installForIssue(Bot bot, String workflowKey,
                                String owner, String repoName, Long issueNumber) {
        if (owner == null || repoName == null || issueNumber == null) {
            return;
        }
        resolveClient(bot, workflowKey).ifPresent(client ->
                AiRetryContext.install(new AiRetryContext.Notice(workflowKey,
                        body -> client.postIssueComment(owner, repoName, issueNumber, body))));
    }

    private Optional<RepositoryApiClient> resolveClient(Bot bot, String workflowKey) {
        try {
            return Optional.of(giteaClientFactory.getApiClient(bot.getGitIntegration()));
        } catch (RuntimeException e) {
            log.debug("[Workflow '{}'] No retry-notice target: {}", workflowKey, e.getMessage());
            return Optional.empty();
        }
    }
}
