package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.ReviewComment;

/**
 * Bitbucket Cloud implementation of {@link ReviewComment}.
 * API response model for a Bitbucket pull request comment.
 * Returned by GET /repositories/{workspace}/{repo}/pullrequests/{pr_id}/comments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketReviewComment implements ReviewComment {

    private CommentId.NumericCommentId id;

    @JsonProperty("content")
    private BitbucketContent content;

    private String path;

    @JsonProperty("diff_hunk")
    private String diffHunk;

    private Integer line;

    private BitbucketReview.BitbucketUser user;

    @Override
    public String getBody() {
        return content != null ? content.getRaw() : null;
    }

    @Override
    public String getUserLogin() {
        return user != null ? user.getDisplayName() : null;
    }

    /** Same precedence as {@code BitbucketApiClient#getAuthenticatedUser}: account id, else uuid. */
    @Override
    public String getUserId() {
        if (user == null) {
            return null;
        }
        return user.getAccountId() != null ? user.getAccountId() : user.getUuid();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BitbucketContent {
        private String raw;
        private String markup;
        private String html;
    }
}
