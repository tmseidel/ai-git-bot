package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;

/**
 * API response model for a GitHub pull request.
 * Returned by GET /repos/{owner}/{repo}/pulls/{pull_number}
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubPullRequest {

    private String title;

    private String body;

    private String state;

    private Boolean merged;

    private GitHubBranch head;

    private GitHubBranch base;

    public PullRequestDetails toPullRequestDetails() {
        return new PullRequestDetails(title, body, toPullRequestState(),
                head != null ? head.getRef() : null,
                head != null ? head.getSha() : null,
                base != null ? base.getRef() : null,
                base != null ? base.getSha() : null);
    }

    /**
     * GitHub reports merged pull requests as {@code closed} with {@code merged=true}.
     * An {@code open} state is only trusted when GitHub also confirms it is not merged.
     */
    private PullRequestState toPullRequestState() {
        if (Boolean.TRUE.equals(merged)) {
            return PullRequestState.MERGED;
        }
        if ("closed".equals(state)) {
            return PullRequestState.CLOSED_WITHOUT_MERGE;
        }
        if ("open".equals(state) && Boolean.FALSE.equals(merged)) {
            return PullRequestState.OPEN;
        }
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubBranch {
        private String ref;
        private String sha;
    }
}
