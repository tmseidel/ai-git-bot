package org.remus.giteabot.prworkflow.agentreview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and dispatches the {@code @bot clarify <question>} slash command
 * for the {@link AgentReviewWorkflow}.
 *
 * <p>Follows the same pattern as {@link org.remus.giteabot.prworkflow.e2e.E2eTestSlashCommandHandler}
 * and {@link org.remus.giteabot.prworkflow.unittest.UnitTestSlashCommandHandler}:
 * it acknowledges the comment with a 👀 reaction, hydrates PR details when the
 * webhook payload only carries an issue block, then re-triggers the
 * {@code agentic-review} workflow via the orchestrator with the user's
 * question threaded as a hint. Any freeform {@code @bot <text>} that no
 * other handler consumed is also caught as a fallback (interpreted as a
 * clarification request), but only when the agentic-review workflow is
 * enabled on the bot's configuration.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentReviewSlashCommandHandler {

    /** Matches {@code @bot clarify <question>} — group 1 captures the question. */
    private static final Pattern CLARIFY_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+clarify\\b\\s*(.*)$");

    /**
     * Fallback: any {@code @bot <text>} not caught by other handlers.
     * Group 1 captures the trailing text.
     */
    private static final Pattern ANY_MENTION_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+(.*)$");

    private final PrWorkflowOrchestrator orchestrator;
    private final WorkflowSelectionService selectionService;
    /**
     * Provider-agnostic {@link RepositoryApiClient} factory. Despite the
     * legacy {@code Gitea*} name, this resolves the correct client
     * (Gitea / GitHub / GitLab / Bitbucket) via
     * {@link org.remus.giteabot.repository.RepositoryProviderRegistry}
     * based on {@code bot.getGitIntegration().getProviderType()}.
     */
    private final GiteaClientFactory repositoryClientFactory;

    /**
     * @return {@code true} when the comment was recognized as an agentic-review
     *         slash command and dispatched; {@code false} otherwise.
     */
    public boolean tryHandle(Bot bot, WebhookPayload payload) {
        if (bot == null || payload == null || payload.getComment() == null) {
            return false;
        }
        String body = payload.getComment().getBody();
        if (body == null || body.isBlank()) {
            return false;
        }

        // Primary: @bot clarify <question>
        Matcher matcher = CLARIFY_PATTERN.matcher(body);
        String question;
        if (matcher.find()) {
            question = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (question.isBlank()) {
                return false; // blank clarify — let other handlers or fallback proceed
            }
        } else {
            // Fallback: any @bot <text> not caught by other handlers
            matcher = ANY_MENTION_PATTERN.matcher(body);
            if (!matcher.find()) {
                return false;
            }
            question = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (question.isBlank()) {
                return false;
            }
        }

        if (!isEnabledOnBot(bot)) {
            log.info("[Bot '{}'] agentic-review slash command ignored — workflow not enabled",
                    bot.getName());
            return false;
        }

        // Acknowledge immediately with 👀 so the operator knows the bot saw it.
        addEyesReaction(bot, payload);

        log.info("[Bot '{}'] agentic-review clarification detected (question='{}'), dispatching",
                bot.getName(), abbreviate(question, 80));

        // Hydrate PR details when the webhook only carries an issue block
        // (GitHub issue_comment events lack the pull_request object).
        PrPayloadHydrator.hydrate(payload, () -> repositoryClientFactory.getApiClient(bot.getGitIntegration()));

        try {
            var hints = Map.of(
                    PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, question);
            orchestrator.run(bot, payload, AgentReviewWorkflow.KEY, hints);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] agentic-review clarification dispatch failed: {}",
                    bot.getName(), e.getMessage(), e);
            postInternalErrorComment(bot, payload, e);
        }
        return true;
    }

    private boolean isEnabledOnBot(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) {
            return false;
        }
        try {
            return selectionService
                    .enabledWorkflowKeys(bot.getWorkflowConfiguration().getId())
                    .contains(AgentReviewWorkflow.KEY);
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] agentic-review enabled-check failed: {}",
                    bot.getName(), e.getMessage());
            return false;
        }
    }

    private void addEyesReaction(Bot bot, WebhookPayload payload) {
        if (payload.getComment() == null || payload.getComment().getId() == null
                || payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long commentId = payload.getComment().getId();
        try {
            RepositoryApiClient client = repositoryClientFactory.getApiClient(bot.getGitIntegration());
            client.addReaction(owner, repo, commentId, "eyes");
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to add 👀 reaction to comment #{}: {}",
                    bot.getName(), commentId, e.getMessage());
        }
    }

    private void postInternalErrorComment(Bot bot, WebhookPayload payload, Throwable error) {
        if (payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        Long prNumber = PrPayloadHydrator.resolvePrNumber(payload);
        if (prNumber == null) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String body = "⚠️ **Internal error** while handling `@bot clarify`.\n\n"
                + "The bot could not dispatch the agentic review workflow because of an unexpected error:\n\n"
                + "```\n" + abbreviate(reason, 1500) + "\n```\n\n"
                + "_Please check the bot logs for the full stack trace._";
        try {
            RepositoryApiClient client = repositoryClientFactory.getApiClient(bot.getGitIntegration());
            client.postIssueComment(owner, repo, prNumber, body);
        } catch (RuntimeException postError) {
            log.warn("[Bot '{}'] Failed to post internal-error comment on PR #{}: {}",
                    bot.getName(), prNumber, postError.getMessage());
        }
    }

    private static @NonNull String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
