package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.Review;

/**
 * Gitea-specific implementation of {@link Review}.
 * API response model for a Gitea pull request review.
 * Returned by GET /repos/{owner}/{repo}/pulls/{index}/reviews
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiteaReview implements Review {

    private Long id;

    private String body;

    private String state;

    @JsonProperty("submitted_at")
    private String submittedAt;

    @JsonProperty("comments_count")
    private Integer commentsCount;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GiteaUser {
        private Long id;
        private String login;
    }
}

