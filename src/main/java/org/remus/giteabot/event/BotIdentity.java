package org.remus.giteabot.event;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.util.BotMention;

/**
 * The bot as webhook payloads see it, for providers that identify users by login: Gitea,
 * GitHub, GitLab and Azure DevOps ({@code uniqueName}). Bitbucket Cloud identifies users by
 * account id instead and uses {@code BitbucketAccount}.
 * <p>
 * Logins are compared case-insensitively; the alias the bot is mentioned by is
 * {@code @<login>}.
 *
 * @param login the Git integration's username
 */
public record BotIdentity(String login) {

    public static BotIdentity of(Bot bot) {
        return new BotIdentity(bot.getGitIntegration().getUsername());
    }

    /** The alias the bot is addressed by in comments, e.g. {@code @ai_bot}. */
    public String alias() {
        return "@" + login;
    }

    /** Whether {@code user} is the bot. */
    public boolean is(WebhookPayload.Owner user) {
        return user != null && login.equalsIgnoreCase(user.getLogin());
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
