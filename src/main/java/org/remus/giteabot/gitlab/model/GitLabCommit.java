package org.remus.giteabot.gitlab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.PullRequestCommit;

/**
 * API response model for a GitLab merge request commit.
 * Returned by GET /api/v4/projects/{id}/merge_requests/{iid}/commits
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabCommit {

    /** Full commit SHA. */
    private String id;

    private String message;

    public PullRequestCommit toPullRequestCommit() {
        return new PullRequestCommit(id, message);
    }
}
