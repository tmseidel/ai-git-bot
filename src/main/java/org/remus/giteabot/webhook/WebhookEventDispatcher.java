package org.remus.giteabot.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.WebhookTranslator;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.learning.feedback.FeedbackCommandHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decides, for every Git provider alike, what the bot does with a translated
 * {@link WebhookEvent}.
 * <p>
 * All trigger rules live here: the bot's own events are dropped, pull request
 * creation and updates honour {@code runOnPrCreation} / {@code runOnPrUpdate},
 * requesting the bot as reviewer or assigning it to a pull request always triggers a
 * review, and comments are only acted on when they mention the bot. Provider-specific concerns — identity
 * matching, telling a push apart from a title edit, resolving inline comment anchors —
 * belong to the {@link WebhookTranslator}s. Pull request comment payloads are completed
 * here ({@link #completePrComment}), since providers deliver only the pull request or only
 * its issue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventDispatcher {

    /** Comments are only acted on when they mention the bot. */
    private static final Set<WebhookEventKind> COMMENT_KINDS = EnumSet.of(
            WebhookEventKind.PR_COMMENT, WebhookEventKind.PR_INLINE_COMMENT, WebhookEventKind.ISSUE_COMMENT);

    /** Comments whose payload is completed by {@link #completePrComment}. */
    private static final Set<WebhookEventKind> PR_COMMENT_KINDS = EnumSet.of(
            WebhookEventKind.PR_COMMENT, WebhookEventKind.PR_INLINE_COMMENT);

    private final BotWebhookService botWebhookService;

    public ResponseEntity<String> dispatch(Bot bot, WebhookEvent event) {
        if (event.kind() == WebhookEventKind.IGNORED) {
            return ignored(bot, event, event.ignoreReason());
        }
        if (event.fromBot()) {
            return ignored(bot, event, "event triggered by the bot itself");
        }
        if (COMMENT_KINDS.contains(event.kind()) && !event.botMentioned()) {
            return ignored(bot, event, "comment does not mention the bot");
        }

        WebhookPayload payload = event.payload();
        if (PR_COMMENT_KINDS.contains(event.kind()) && payload.getPullRequest() == null && payload.getIssue() == null) {
            return ignored(bot, event, "pull request comment carries neither pull request nor issue");
        }
        return switch (event.kind()) {
            case PR_OPENED, PR_REOPENED -> bot.isRunOnPrCreation() || event.botIsReviewer()
                    ? automaticReview(bot, event)
                    : ignored(bot, event, "runOnPrCreation is off and the bot is not a requested reviewer");
            case PR_SYNCHRONIZED -> bot.isRunOnPrUpdate()
                    ? automaticReview(bot, event)
                    : ignored(bot, event, "runOnPrUpdate is off");
            case PR_REVIEWER_ADDED, PR_ASSIGNED -> automaticReview(bot, event);
            case PR_CLOSED -> {
                botWebhookService.handlePrClosed(bot, payload);
                yield ResponseEntity.ok("session closed");
            }
            case PR_REVIEW_SUBMITTED -> {
                // A review body is free text like a comment: a feedback command there must not
                // reach the agentic clarification or the review follow-up.
                if (botWebhookService.tryHandleLearningCommand(bot, payload,
                        FeedbackCommandHandler.COMMENT_KIND_REVIEW, event.botAlias())) {
                    yield ResponseEntity.ok("feedback recorded");
                }
                botWebhookService.handleReviewSubmitted(bot, payload);
                yield ResponseEntity.ok("review comments processing triggered");
            }
            case PR_INLINE_COMMENT -> {
                WebhookPayload completed = completePrComment(payload);
                if (botWebhookService.tryHandleLearningCommand(bot, completed,
                        FeedbackCommandHandler.COMMENT_KIND_INLINE, event.botAlias())) {
                    yield ResponseEntity.ok("feedback recorded");
                }
                botWebhookService.handleInlineComment(bot, completed);
                yield ResponseEntity.ok("inline comment response triggered");
            }
            case PR_COMMENT -> handlePrComment(bot, event);
            case ISSUE_OPENED, ISSUE_REOPENED -> {
                if (!bot.isRunOnIssueCreation()) {
                    yield ignored(bot, event, "runOnIssueCreation is off");
                }
                botWebhookService.handleIssueCreated(bot, payload);
                yield ResponseEntity.ok("agent triggered");
            }
            case ISSUE_ASSIGNED -> {
                botWebhookService.handleIssueAssigned(bot, payload);
                yield ResponseEntity.ok("agent triggered");
            }
            case ISSUE_COMMENT -> {
                botWebhookService.handleIssueComment(bot, payload);
                yield ResponseEntity.ok("issue comment received");
            }
            case IGNORED -> throw new IllegalStateException("IGNORED is handled above");
        };
    }

    /**
     * A conversation comment that mentions the bot. "Review again" re-runs the review,
     * for any caller the whitelist allows, like every other command. Everything else goes to
     * {@link BotWebhookService#handlePrComment}, which continues the issue workflow when
     * the pull request belongs to an agent session and otherwise runs slash commands or
     * the review command.
     * <p>
     * Feedback commands are consumed first: their reason is free text and may contain
     * "review again", which must not rerun the review.
     * <p>
     * A pull request that belongs to an agent session never takes the review path: there
     * a comment is an instruction to the agent, and "review the failing test again and fix
     * it" must not start a review of the bot's own pull request.
     */
    private ResponseEntity<String> handlePrComment(Bot bot, WebhookEvent event) {
        WebhookPayload payload = completePrComment(event.payload());
        if (botWebhookService.tryHandleLearningCommand(bot, payload,
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, event.botAlias())) {
            return ResponseEntity.ok("feedback recorded");
        }
        if (botWebhookService.isReviewAgainRequest(payload, event.botAlias())
                && !botWebhookService.hasAgentSession(bot, payload)) {
            if (!botWebhookService.isCallerAllowed(bot, payload)) {
                return ignored(bot, event, "caller is not on the bot's user whitelist");
            }
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }
        botWebhookService.handlePrComment(bot, payload);
        return ResponseEntity.ok("pr comment received");
    }

    /**
     * A review the bot starts on its own, as opposed to one asked for in a comment.
     * {@link BotWebhookService#reviewPullRequest} checks the whitelist as well, so no other
     * caller can skip it.
     */
    private ResponseEntity<String> automaticReview(Bot bot, WebhookEvent event) {
        if (!botWebhookService.isCallerAllowed(bot, event.payload())) {
            return ignored(bot, event, "caller is not on the bot's user whitelist");
        }
        botWebhookService.reviewPullRequest(bot, event.payload());
        return ResponseEntity.ok("review triggered");
    }

    /**
     * Adds the pull request or the issue of a pull request comment, whichever the provider
     * did not send, and marks the issue as a pull request: the comment handlers read both.
     * Gitea and GitHub send the issue; GitLab, Bitbucket and Azure DevOps the pull request.
     * The two share number, title, body and author; head and base stay unset on a derived
     * pull request, downstream code fetches them by number. {@link #dispatch} has already
     * ignored comments carrying neither.
     */
    private static WebhookPayload completePrComment(WebhookPayload payload) {
        WebhookPayload.PullRequest pullRequest = payload.getPullRequest();
        WebhookPayload.Issue issue = payload.getIssue();
        if (pullRequest == null) {
            pullRequest = new WebhookPayload.PullRequest();
            pullRequest.setNumber(issue.getNumber());
            pullRequest.setTitle(issue.getTitle());
            pullRequest.setBody(issue.getBody());
            pullRequest.setUser(issue.getUser());
            payload.setPullRequest(pullRequest);
        } else if (issue == null) {
            issue = new WebhookPayload.Issue();
            issue.setNumber(pullRequest.getNumber());
            issue.setTitle(pullRequest.getTitle());
            issue.setBody(pullRequest.getBody());
            issue.setUser(pullRequest.getUser());
            payload.setIssue(issue);
        }
        if (issue.getPullRequest() == null) {
            WebhookPayload.IssuePullRequest marker = new WebhookPayload.IssuePullRequest();
            marker.setMerged(pullRequest.getMerged());
            issue.setPullRequest(marker);
        }
        return payload;
    }

    private static ResponseEntity<String> ignored(Bot bot, WebhookEvent event, String reason) {
        log.debug("[Bot '{}'] Ignoring {} webhook event: {}", bot.getName(), event.kind(), reason);
        return ResponseEntity.ok("ignored");
    }
}
