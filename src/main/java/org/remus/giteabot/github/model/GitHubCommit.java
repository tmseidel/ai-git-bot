package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.PullRequestCommit;

/**
 * API response model for a GitHub pull request commit.
 * Returned by GET /repos/{owner}/{repo}/pulls/{pull_number}/commits
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubCommit {

    private String sha;

    private GitHubCommitDetails commit;

    public PullRequestCommit toPullRequestCommit() {
        return new PullRequestCommit(sha, commit != null ? commit.getMessage() : null);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubCommitDetails {
        private String message;
    }
}
