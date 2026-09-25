package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.remus.giteabot.repository.model.Review;

/**
 * Azure DevOps implementation of {@link Review}, mapped from an
 * {@code IdentityRefWithVote} entry in a pull request's {@code reviewers} array.
 * <p>
 * Azure DevOps has no review object. A reviewer's vote is the closest analogue, so this
 * model translates the vote scale onto the shared {@code state} string.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureDevopsReview implements Review {

    /**
     * Always {@code null} for reviewers. Azure DevOps identity ids are GUIDs and the
     * shared {@link Review} contract types this as {@code Long}, so the identity is not
     * representable here. The one place that needs the bot's own GUID — casting a vote —
     * resolves it from {@code _apis/connectionData} rather than from this model.
     */
    private Long id;

    private String body;

    private Integer vote;

    private String uniqueName;

    private String displayName;

    /** Maps the Azure DevOps vote scale onto the shared review state. */
    @Override
    public String getState() {
        if (vote == null) {
            return "NO_VOTE";
        }
        return switch (vote) {
            case 10 -> "APPROVED";
            case 5 -> "APPROVED_WITH_SUGGESTIONS";
            case -5 -> "WAITING_FOR_AUTHOR";
            case -10 -> "REJECTED";
            default -> "NO_VOTE";
        };
    }

    @Override
    public String getUserLogin() {
        return uniqueName != null ? uniqueName : displayName;
    }

    /** The reviewers array carries no timestamp. */
    @Override
    public String getSubmittedAt() {
        return null;
    }

    /** The reviewers array carries no comment count; fetch threads separately. */
    @Override
    public Integer getCommentsCount() {
        return null;
    }
}
