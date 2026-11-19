package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.Review;

/**
 * GitHub-specific implementation of {@link Review}.
 * API response model for a GitHub pull request review.
 * Returned by GET /repos/{owner}/{repo}/pulls/{pull_number}/reviews
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubReview implements Review {

    private Long id;

    private String body;

    private String state;

    @JsonProperty("submitted_at")
    private String submittedAt;

    /**
     * GitHub reviews don't include a comments_count field in the review object.
     * Returns null; callers should fetch comments separately if needed.
     */
    @Override
    public Integer getCommentsCount() {
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubUser {
        private Long id;
        private String login;
    }
}
