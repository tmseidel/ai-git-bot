package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.PullRequestCommit;

import java.util.List;

/**
 * API response model for a Bitbucket Cloud pull request commit.
 * Returned (paginated, see {@link Page}) by
 * GET /repositories/{workspace}/{repo}/pullrequests/{pr_id}/commits
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketCommit {

    /** Full commit SHA. */
    private String hash;

    private String message;

    public PullRequestCommit toPullRequestCommit() {
        return new PullRequestCommit(hash, message);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {
        private List<BitbucketCommit> values;
    }
}
