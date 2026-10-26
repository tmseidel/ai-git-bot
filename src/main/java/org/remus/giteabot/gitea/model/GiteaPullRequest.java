package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;

/**
 * API response model for a Gitea pull request.
 * Returned by GET /api/v1/repos/{owner}/{repo}/pulls/{index}
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiteaPullRequest {

    private String title;

    private String body;

    private String state;

    private Boolean merged;

    private GiteaBranch head;

    private GiteaBranch base;

    public PullRequestDetails toPullRequestDetails() {
        return new PullRequestDetails(title, body, toPullRequestState(),
                head != null ? head.getRef() : null,
                head != null ? head.getSha() : null,
                base != null ? base.getRef() : null,
                base != null ? base.getSha() : null);
    }

    /**
     * Gitea reports merged pull requests as {@code closed} with {@code merged=true}.
     * An {@code open} state is only trusted when Gitea also confirms it is not merged.
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
    public static class GiteaBranch {
        private String ref;
        private String sha;
        private GiteaRepository repo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GiteaRepository {
        private String name;

        @JsonProperty("full_name")
        private String fullName;

        private GiteaOwner owner;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GiteaOwner {
        private String login;
    }
}
