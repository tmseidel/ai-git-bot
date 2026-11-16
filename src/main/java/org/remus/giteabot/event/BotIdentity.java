package org.remus.giteabot.event;

import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.remus.giteabot.util.BotMention;

/**
 * The bot as webhook payloads see it: the account its Git integration authenticates as, see
 * {@link BotAccountResolver}.
 * <p>
 * Users are matched by their {@linkplain WebhookPayload.Owner#getId() id}, which every provider
 * puts in its payloads and its users cannot change (Bitbucket: {@code account_id}; Azure DevOps:
 * the identity GUID). The username only gives the alias the bot is mentioned by,
 * {@code @<username>}; Bitbucket mentions use the account id instead.
 *
 * @param account the account the Git integration authenticates as
 */
public record BotIdentity(RepositoryUser account) {

    /** The alias the bot is addressed by in comments, e.g. {@code @ai_bot}. */
    public String alias() {
        return "@" + account.username();
    }

    /** Whether {@code user} is the bot, going by its id. */
    public boolean is(WebhookPayload.Owner user) {
        return user != null && account.id().equals(user.getId());
    }

    /**
     * Whether the bot itself triggered the event: it is the sender, or the author of the
     * comment the event is about.
     */
    public boolean triggered(WebhookPayload payload) {
        return is(payload.getSender())
                || (payload.getComment() != null && is(payload.getComment().getUser()));
    }

    /** Whether the bot is among the pull request's requested reviewers. */
    public boolean isReviewer(WebhookPayload payload) {
        return payload.getPullRequest() != null
                && payload.getPullRequest().getRequestedReviewers() != null
                && payload.getPullRequest().getRequestedReviewers().stream().anyMatch(this::is);
    }

    /** Whether the bot is among the pull request's assignees. */
    public boolean isPullRequestAssignee(WebhookPayload payload) {
        WebhookPayload.PullRequest pullRequest = payload.getPullRequest();
        return pullRequest != null
                && (is(pullRequest.getAssignee())
                || (pullRequest.getAssignees() != null && pullRequest.getAssignees().stream().anyMatch(this::is)));
    }

    /** Whether the bot is among the issue's assignees. */
    public boolean isIssueAssignee(WebhookPayload payload) {
        WebhookPayload.Issue issue = payload.getIssue();
        return issue != null
                && (is(issue.getAssignee())
                || (issue.getAssignees() != null && issue.getAssignees().stream().anyMatch(this::is)));
    }

    /** Whether the comment the event is about mentions the bot. */
    public boolean isMentionedIn(WebhookPayload payload) {
        return payload.getComment() != null && BotMention.isMentioned(payload.getComment().getBody(), alias());
    }
}
