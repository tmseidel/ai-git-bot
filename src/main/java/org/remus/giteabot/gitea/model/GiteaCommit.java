package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.PullRequestCommit;

/**
 * API response model for a Gitea pull request commit.
 * Returned by GET /api/v1/repos/{owner}/{repo}/pulls/{index}/commits
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiteaCommit {

    private String sha;

    private GiteaCommitDetails commit;

    public PullRequestCommit toPullRequestCommit() {
        return new PullRequestCommit(sha, commit != null ? commit.getMessage() : null);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GiteaCommitDetails {
        private String message;
    }
}
