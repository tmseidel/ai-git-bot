package org.remus.giteabot.prworkflow.e2e;

import lombok.extern.slf4j.Slf4j;
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
public class E2eTestSlashCommandHandler {

    /**
     * Permissive matcher: allows the bot mention to be the literal
     * {@code @bot} marker OR any actual bot alias (configured per bot via
     * {@link Bot#getName()}), followed by either {@code rerun-tests} or
     * {@code regenerate-tests} as the first whitespace-delimited token.
     * Any trailing text after {@code regenerate-tests} is captured as
     * feedback (group 2).
     */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+(rerun-tests|regenerate-tests)\\b\\s*(.*)$");

    private final PrWorkflowOrchestrator orchestrator;
    private final WorkflowSelectionService selectionService;
    /**
     * Provider-agnostic {@link RepositoryApiClient} factory. Despite the
     * legacy {@code Gitea*} name, this resolves the correct client
     * (Gitea / GitHub / GitLab / Bitbucket) via {@link RepositoryProviderRegistry}
     * based on {@code bot.getGitIntegration().getProviderType()}.
     */
    private final GiteaClientFactory repositoryClientFactory;

    public E2eTestSlashCommandHandler(PrWorkflowOrchestrator orchestrator,
                                      WorkflowSelectionService selectionService,
                                      GiteaClientFactory repositoryClientFactory) {
        this.orchestrator = orchestrator;
        this.selectionService = selectionService;
        this.repositoryClientFactory = repositoryClientFactory;
    }

    /**
     * @return {@code true} when the comment was recognised as an E2E slash
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

        // GitHub `issue_comment` events arrive without a `pull_request` object —
        // only the issue is set. The downstream workflow + CI dispatch strategy
        // need the head branch (for `refs/heads/{branch}`) and PR number, so
        // hydrate the payload from the provider API before dispatching.
        hydratePullRequest(bot, payload);

        log.info("[Bot '{}'] E2E slash command '{}' detected (feedback='{}'), dispatching e2e-test workflow",
                bot.getName(), verb, abbreviate(feedback, 80));
        try {
            Map<String, String> hints = feedback.isBlank()
                    ? Map.of()
                    : Map.of(PrWorkflowContext.HINT_E2E_FEEDBACK, feedback);
            orchestrator.run(bot, payload, E2ETestWorkflow.KEY, hints);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] E2E slash command '{}' dispatch failed: {}",
                    bot.getName(), verb, e.getMessage(), e);
        }
        return true;
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
     * Hydrates {@code payload.pullRequest} from the provider's PR API when
     * the incoming webhook was an {@code issue_comment} (GitHub) event —
     * those payloads carry only {@code issue.number}, but the downstream
     * workflow + {@code CiActionTriggerStrategy} need the PR's head branch
     * ({@code refs/heads/{branch}}) and SHA to dispatch a build. No-op when
     * the payload already contains the PR object or no repository/PR can
     * be resolved. Failures are logged but never propagate — the workflow
     * will then fail with a clearer downstream error if the missing data
     * is actually required.
     */
    @SuppressWarnings("unchecked")
    private void hydratePullRequest(Bot bot, WebhookPayload payload) {
        if (payload.getPullRequest() != null
                && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null) {
            return; // Already complete
        }
        if (payload.getRepository() == null
                || payload.getRepository().getOwner() == null
                || payload.getIssue() == null
                || payload.getIssue().getNumber() == null
                || payload.getIssue().getPullRequest() == null) {
            return; // Not a PR issue_comment we can hydrate
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getIssue().getNumber();
        try {
            RepositoryApiClient client = repositoryClientFactory.getApiClient(bot.getGitIntegration());
            Map<String, Object> details = client.getPullRequestDetails(owner, repo, prNumber);
            if (details == null || details.isEmpty()) {
                log.warn("[Bot '{}'] Could not hydrate PR #{} — provider returned no details",
                        bot.getName(), prNumber);
                return;
            }
            WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
            pr.setNumber(prNumber);
            Object title = details.get("title");
            if (title instanceof String s) pr.setTitle(s);
            Object body = details.get("body");
            if (body instanceof String s) pr.setBody(s);
            Object state = details.get("state");
            if (state instanceof String s) pr.setState(s);
            Object merged = details.get("merged");
            if (merged instanceof Boolean b) pr.setMerged(b);
            Object headObj = details.get("head");
            if (headObj instanceof Map) {
                Map<String, Object> head = (Map<String, Object>) headObj;
                WebhookPayload.Head h = new WebhookPayload.Head();
                if (head.get("ref") instanceof String s) h.setRef(s);
                if (head.get("sha") instanceof String s) h.setSha(s);
                pr.setHead(h);
            }
            Object baseObj = details.get("base");
            if (baseObj instanceof Map) {
                Map<String, Object> base = (Map<String, Object>) baseObj;
                WebhookPayload.Head b = new WebhookPayload.Head();
                if (base.get("ref") instanceof String s) b.setRef(s);
                if (base.get("sha") instanceof String s) b.setSha(s);
                pr.setBase(b);
            }
            payload.setPullRequest(pr);
            payload.setNumber(prNumber);
            log.debug("[Bot '{}'] Hydrated PR #{} (head={}) from provider for slash-command dispatch",
                    bot.getName(), prNumber,
                    pr.getHead() == null ? "?" : pr.getHead().getRef());
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to hydrate PR #{} for slash-command dispatch: {}",
                    bot.getName(), prNumber, e.getMessage());
        }
    }
}




