package org.remus.giteabot.review.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestCommit;

import java.util.List;

/**
 * Enriches PR context with commit messages from the pull request.
 * Fetches the PR's commits and formats their messages, truncated to
 * {@link ReviewConfigProperties#getMaxCommitMessages()}.
 */
@Slf4j
public class CommitMessagesEnricher implements ContextEnricher {

    private final RepositoryApiClient repositoryClient;
    private final ReviewConfigProperties config;

    public CommitMessagesEnricher(RepositoryApiClient repositoryClient, ReviewConfigProperties config) {
        this.repositoryClient = repositoryClient;
        this.config = config;
    }

    @Override
    public String enrich(EnrichmentContext context) {
        try {
            List<PullRequestCommit> commits = repositoryClient.getPullRequestCommits(
                    context.owner(), context.repo(), context.prNumber());
            if (commits.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("**Commit messages:**\n");
            int count = 0;
            for (PullRequestCommit commit : commits) {
                if (count >= config.getMaxCommitMessages()) {
                    sb.append("... (").append(commits.size() - count).append(" more commits)\n");
                    break;
                }
                String message = commit.message();
                String sha = commit.sha();
                if (message != null && !message.isBlank()) {
                    sb.append("- ");
                    if (sha != null && sha.length() >= 7) {
                        sb.append(sha, 0, 7).append(" ");
                    }
                    // Only include first line of commit message
                    String firstLine = message.lines().findFirst().orElse(message);
                    sb.append(firstLine).append("\n");
                    count++;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to fetch commits for PR #{} in {}/{}: {}",
                    context.prNumber(), context.owner(), context.repo(), e.getMessage());
            return "";
        }
    }
}

