package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.Review;

/**
 * Bitbucket Cloud implementation of {@link Review}.
 * API response model for a Bitbucket pull request activity item that represents an approval.
 * <p>
 * Bitbucket Cloud does not have a direct "review" concept like GitHub/Gitea.
 * Instead, it has approvals and comments. This model maps approval activity entries
 * to the common Review interface.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketReview implements Review {

    private Long id;

    private String body;

    private String state;

    private BitbucketUser user;

    @JsonProperty("submitted_at")
    private String submittedAt;

    @Override
    public String getUserLogin() {
        return user != null ? user.getDisplayName() : null;
    }

    /**
     * Bitbucket Cloud does not include a comments count in approval responses.
     * Returns null; callers should fetch comments separately if needed.
     */
    @Override
    public Integer getCommentsCount() {
        return null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BitbucketUser {
        @JsonProperty("display_name")
        private String displayName;

        private String nickname;

        private String uuid;

        @JsonProperty("account_id")
        private String accountId;
    }
}
