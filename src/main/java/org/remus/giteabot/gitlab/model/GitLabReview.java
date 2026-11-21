package org.remus.giteabot.gitlab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.Review;

import java.util.List;

/**
 * GitLab-specific implementation of {@link Review}.
 * Maps a GitLab merge request discussion thread to the provider-agnostic Review interface.
 * Returned by GET /projects/:id/merge_requests/:iid/discussions
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabReview implements Review {

    private String id;

    @JsonProperty("individual_note")
    private Boolean individualNote;

    private List<GitLabNote> notes;

    @Override
    public Long getId() {
        // GitLab discussion IDs are strings; use the first note's ID as the numeric ID
        if (notes != null && !notes.isEmpty()) {
            return notes.getFirst().getId();
        }
        return null;
    }

    @Override
    public String getBody() {
        if (notes != null && !notes.isEmpty()) {
            return notes.getFirst().getBody();
        }
        return null;
    }

    @Override
    public String getState() {
        // Map GitLab resolved status to a review state
        if (notes != null && !notes.isEmpty() && Boolean.TRUE.equals(notes.getFirst().getResolved())) {
            return "APPROVED";
        }
        return "COMMENT";
    }

    @Override
    public String getSubmittedAt() {
        if (notes != null && !notes.isEmpty()) {
            return notes.getFirst().getCreatedAt();
        }
        return null;
    }

    @Override
    public Integer getCommentsCount() {
        return notes != null ? notes.size() : 0;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitLabNote {
        private Long id;
        private String body;
        private Boolean resolved;

        @JsonProperty("created_at")
        private String createdAt;

        private GitLabUser author;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitLabUser {
        private Long id;
        private String username;
        private String name;
    }
}
