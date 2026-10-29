package org.remus.giteabot.gitlab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.ReviewComment;

/**
 * GitLab-specific implementation of {@link ReviewComment}.
 * Maps a GitLab merge request note (comment) to the provider-agnostic ReviewComment interface.
 * Returned within discussion threads from GET /projects/:id/merge_requests/:iid/discussions
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabReviewComment implements ReviewComment {

    private CommentId.NumericCommentId id;

    private String body;

    @JsonProperty("created_at")
    private String createdAt;

    private GitLabReview.GitLabUser author;

    private GitLabPosition position;

    @Override
    public String getPath() {
        return position != null ? position.getNewPath() : null;
    }

    @Override
    public String getDiffHunk() {
        // GitLab doesn't provide a diff hunk in the same way as Gitea
        return null;
    }

    @Override
    public Integer getLine() {
        return position != null ? position.getNewLine() : null;
    }

    @Override
    public String getUserLogin() {
        return author != null ? author.getUsername() : null;
    }

    @Override
    public String getUserId() {
        return author != null && author.getId() != null ? author.getId().toString() : null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitLabPosition {
        @JsonProperty("new_path")
        private String newPath;

        @JsonProperty("old_path")
        private String oldPath;

        @JsonProperty("new_line")
        private Integer newLine;

        @JsonProperty("old_line")
        private Integer oldLine;

        @JsonProperty("position_type")
        private String positionType;
    }
}
