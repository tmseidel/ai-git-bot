package org.remus.giteabot.prworkflow.e2e;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryProviderRegistry;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and acts on the M4 / wave-2 slash commands an operator can post
 * on a pull-request to interact with the {@link E2ETestWorkflow}:
 *
 * <ul>
 *   <li>{@code @bot rerun-tests} – re-trigger the {@code e2e-test} workflow
 *       on the current PR (currently a full re-run; a future optimisation
 *       can skip planner+author and only re-run the runner against the
 *       previously persisted {@link PrTestSuite}).</li>
 *   <li>{@code @bot regenerate-tests [feedback...]} – re-trigger the full
 *       workflow. The optional feedback is logged on the new
 *       {@code PrWorkflowRun} so operators can correlate it; the planner
 *       agent will pick it up once the context-hint plumbing lands.</li>
 * </ul>
 *
 * <p>The handler is deliberately conservative — if the bot has no
 * {@code e2e-test} workflow enabled in its {@code WorkflowConfiguration},
 * the command is ignored (returns {@code false}) so the regular
 * {@code CodeReviewService} comment-as-prompt fallback still runs.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class E2eTestSlashCommandHandler {

    /**
     * Permissive matcher: allows the bot mention to be the literal
     * {@code @bot} marker OR any actual bot alias (configured per bot via
     * {@link Bot#getName()}), followed by either {@code rerun-tests} or
     * {@code regenerate-tests} as the first whitespace-delimited token.
     * The trailing {@code s} is optional so that common typos like
     * {@code rerun-test} / {@code regenerate-test} (singular) are also
     * accepted. Any trailing text after the verb is captured as
     * feedback (group 2).
     */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+(rerun-tests?|regenerate-tests?)\\b\\s*(.*)$");

    private final PrWorkflowOrchestrator orchestrator;
    private final WorkflowSelectionService selectionService;
    /**
     * Provider-agnostic {@link RepositoryApiClient} factory. Despite the
     * legacy {@code Gitea*} name, this resolves the correct client
     * (Gitea / GitHub / GitLab / Bitbucket) via {@link RepositoryProviderRegistry}
     * based on {@code bot.getGitIntegration().getProviderType()}.
     */
    private final GiteaClientFactory repositoryClientFactory;

    /**
     * @return {@code true} when the comment was recognized as an E2E slash
     *         command and dispatched (regardless of the dispatched run's
     *         outcome); {@code false} when the comment is something else and
     *         the caller must continue with its default handling.
     */
    public boolean tryHandle(Bot bot, WebhookPayload payload) {
        if (bot == null || payload == null || payload.getComment() == null) {
            return false;
        }
        String body = payload.getComment().getBody();
        if (body == null || body.isBlank()) {
            return false;
        }
        Matcher matcher = COMMAND_PATTERN.matcher(body);
        if (!matcher.find()) {
            return false;
        }
        String verb = matcher.group(1).toLowerCase(Locale.ROOT);
        String feedback = matcher.group(2) == null ? "" : matcher.group(2).trim();

        if (!isE2eEnabledOnBot(bot)) {
            log.info("[Bot '{}'] E2E slash command '{}' ignored — workflow not enabled on configuration",
                    bot.getName(), verb);
            return false;
        }

        // Acknowledge the slash command immediately with an 👀 reaction so
        // the operator knows the bot picked it up — the actual workflow run
        // can take several minutes to complete.
        addEyesReaction(bot, payload);

        log.info("[Bot '{}'] E2E slash command '{}' detected (feedback='{}'), dispatching e2e-test workflow",
                bot.getName(), verb, abbreviate(feedback, 80));
        // GitHub `issue_comment` events do not carry a `pull_request` block — only
        // the issue. The downstream workflow needs head ref / SHA / base for the
        // deployment dispatch and tooling, so we hydrate it now from the provider
        // API while we are still on the synchronous webhook thread.
        try {
            hydratePullRequest(bot, payload);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Could not hydrate pull-request details for '{}': {}",
                    bot.getName(), verb, e.getMessage());
        }
        try {
            var hints = getHints(verb, feedback);
            orchestrator.run(bot, payload, E2ETestWorkflow.KEY, hints);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] E2E slash command '{}' dispatch failed: {}",
                    bot.getName(), verb, e.getMessage(), e);
            postInternalErrorComment(bot, payload, verb, e);
        }
        return true;
    }

    private static @NonNull Map<String, String> getHints(String verb, String feedback) {
        Map<String, String> hints;
        // verb is already lower-cased; accept both singular ("rerun-test") and
        // plural ("rerun-tests") forms — same for regenerate-test(s).
        if (verb.startsWith("rerun-test")) {
            // Skip Planner+Author; re-run the existing test files from the last suite.
            hints = Map.of(PrWorkflowContext.HINT_RERUN_ONLY, "true");
        } else {
            // regenerate-test(s): full flow. Optionally pass operator feedback to the planner.
            hints = feedback.isBlank()
                    ? Map.of()
                    : Map.of(PrWorkflowContext.HINT_E2E_FEEDBACK, feedback);
        }
        return hints;
    }

    private boolean isE2eEnabledOnBot(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) {
            return false;
        }
        try {
            return selectionService
                    .enabledWorkflowKeys(bot.getWorkflowConfiguration().getId())
                    .contains(E2ETestWorkflow.KEY);
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] E2E enabled-check failed: {}", bot.getName(), e.getMessage());
            return false;
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Best-effort 👀 reaction on the triggering comment. Mirrors the UX in
     * {@code CodeReviewService.handleBotCommand} so {@code @bot rerun-tests}
     * / {@code @bot regenerate-tests} get the same instant acknowledgement
     * as the regular review slash commands. Failures are swallowed — a
     * missing reaction must never block dispatch.
     */
    private void addEyesReaction(Bot bot, WebhookPayload payload) {
        if (payload.getComment() == null
                || payload.getComment().getId() == null
                || payload.getRepository() == null
                || payload.getRepository().getOwner() == null) {
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

    /**
     * Posts a best-effort comment on the PR informing the operator that an internal
     * error prevented the slash command from being dispatched. Swallows any
     * follow-up exception - failing to post the notice must never propagate to
     * the webhook caller.
     */
    private void postInternalErrorComment(Bot bot, WebhookPayload payload, String verb, Throwable error) {
        if (payload.getRepository() == null
                || payload.getRepository().getOwner() == null) {
            return;
        }
        Long prNumber = resolvePrNumber(payload);
        if (prNumber == null) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String body = "⚠️ **Internal error** while handling `@bot " + verb + "`.\n\n"
                + "The bot could not dispatch the E2E workflow because of an unexpected error:\n\n"
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

    private Long resolvePrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        return payload.getNumber();
    }

    /**
     * Populates {@code payload.pullRequest} (number, head ref / SHA, base ref)
     * from the provider's REST API when it is missing or incomplete.
     *
     * <p>Background: GitHub fires {@code issue_comment} events for slash
     * commands on pull-request conversations, but the payload only carries an
     * {@code issue} object — no {@code pull_request}. Downstream consumers
     * ({@code CI_ACTION} deployment, workflow comments, …) require the head
     * branch to dispatch against, so we fetch it here once on the synchronous
     * webhook thread. No-op when the payload already has a head ref.</p>
     */
    @SuppressWarnings("unchecked")
    private void hydratePullRequest(Bot bot, WebhookPayload payload) {
        if (payload.getPullRequest() != null
                && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null
                && !payload.getPullRequest().getHead().getRef().isBlank()) {
            return; // already complete
        }
        if (payload.getRepository() == null || payload.getRepository().getOwner() == null) {
            return;
        }
        Long prNumber = resolvePrNumber(payload);
        if (prNumber == null || prNumber <= 0) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        RepositoryApiClient client = repositoryClientFactory.getApiClient(bot.getGitIntegration());
        Map<String, Object> pr = client.getPullRequestDetails(owner, repo, prNumber);
        if (pr == null || pr.isEmpty()) {
            log.debug("[Bot '{}'] getPullRequestDetails returned empty for {}/{}#{} — provider may not support hydration",
                    bot.getName(), owner, repo, prNumber);
            return;
        }
        WebhookPayload.PullRequest target = payload.getPullRequest();
        if (target == null) {
            target = new WebhookPayload.PullRequest();
            payload.setPullRequest(target);
        }
        target.setNumber(prNumber);
        if (pr.get("title") instanceof String t) target.setTitle(t);
        if (pr.get("body") instanceof String b) target.setBody(b);
        if (pr.get("state") instanceof String s) target.setState(s);
        Map<String, Object> head = pr.get("head") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (head != null) {
            WebhookPayload.Head h = new WebhookPayload.Head();
            if (head.get("ref") instanceof String r) h.setRef(r);
            if (head.get("sha") instanceof String s) h.setSha(s);
            target.setHead(h);
        }
        Map<String, Object> base = pr.get("base") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (base != null) {
            WebhookPayload.Head b = new WebhookPayload.Head();
            if (base.get("ref") instanceof String r) b.setRef(r);
            if (base.get("sha") instanceof String s) b.setSha(s);
            target.setBase(b);
        }
        log.info("[Bot '{}'] Hydrated PR #{} for {}/{} — head={} sha={}",
                bot.getName(), prNumber, owner, repo,
                target.getHead() == null ? null : target.getHead().getRef(),
                target.getHead() == null ? null : target.getHead().getSha());
    }
}




