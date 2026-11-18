package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.issueworkflow.IssueWorkflowOrchestrator;
import org.remus.giteabot.learning.feedback.FeedbackCommandHandler;
import org.remus.giteabot.prworkflow.PrPayloadHydrator;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewSlashCommandHandler;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewWorkflow;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.e2e.E2ETestWorkflow;
import org.remus.giteabot.prworkflow.e2e.E2eTestPrCloseHandler;
import org.remus.giteabot.prworkflow.e2e.E2eTestSlashCommandHandler;
import org.remus.giteabot.prworkflow.i18n.I18nCoverageSlashCommandHandler;
import org.remus.giteabot.prworkflow.readmesync.ReadmeSyncSlashCommandHandler;
import org.remus.giteabot.prworkflow.review.ReviewWorkflow;
import org.remus.giteabot.prworkflow.unittest.UnitTestSlashCommandHandler;
import org.remus.giteabot.prworkflow.unittest.UnitTestWorkflow;
import org.remus.giteabot.repository.Reactions;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.util.BotMention;
import org.remus.giteabot.util.BranchFilter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Handles webhook events for persisted {@link Bot} entities using their
 * specific {@link AiIntegration} and {@link GitIntegration} configurations.
 * <p>
 * This is the bridge between the admin data model and the code-review / agent
 * services.  Each bot gets its own {@link AiClient} (via {@link AiClientFactory})
 * and its own {@link RepositoryPlatform} (via {@link RepositoryPlatformFactory}).
 * <p>
 * PR events are delegated to the {@link PrWorkflowOrchestrator}, issue events
 * (assignments and comments, including follow-ups on agent-created PRs) to the
 * {@link IssueWorkflowOrchestrator} — both resolve what runs from the bot's
 * workflow configurations, never from a hardcoded bot category.
 */
@Slf4j
@Service
public class BotWebhookService {

    /**
     * Phrasings that ask for the review to be run again: "re-review", "review (this / the
     * pull request / the diff ...) again", "repeat the (code) review". Deliberately narrow:
     * a comment merely containing "review" and "again" — "can you explain that review
     * comment again?" — is a question for the bot, not a request to rerun every workflow.
     */
    private static final Pattern REVIEW_AGAIN = Pattern.compile(String.join("|",
            // re-review, re review, rereview
            "\\bre-?\\s?review\\b",
            // review again, review (this / the PR / the diff ...) again
            "\\breview\\s+(?:(?:this|it|the|that|my|our)\\s+)?"
                    + "(?:(?:pull[\\s-]?request|pr|merge[\\s-]?request|mr|changes|code|diff)\\s+)?again\\b",
            // repeat the (code) review
            "\\brepeat\\s+(?:(?:the|your|a)\\s+)?(?:code[\\s-]?)?review\\b"),
            Pattern.CASE_INSENSITIVE);

    private final RepositoryPlatformFactory repositoryPlatformFactory;
    private final AgentSessionService agentSessionService;
    private final BotService botService;
    private final PrWorkflowOrchestrator prWorkflowOrchestrator;
    private final E2eTestPrCloseHandler e2eTestPrCloseHandler;
    private final E2eTestSlashCommandHandler e2eTestSlashCommandHandler;
    private final UnitTestSlashCommandHandler unitTestSlashCommandHandler;
    private final AgentReviewSlashCommandHandler agentReviewSlashCommandHandler;
    private final ReadmeSyncSlashCommandHandler readmeSyncSlashCommandHandler;
    private final I18nCoverageSlashCommandHandler i18nCoverageSlashCommandHandler;
    private final FeedbackCommandHandler feedbackCommandHandler;
    private final WorkflowSelectionService workflowSelectionService;
    private final IssueWorkflowOrchestrator issueWorkflowOrchestrator;

    public BotWebhookService(RepositoryPlatformFactory repositoryPlatformFactory,
                             AgentSessionService agentSessionService,
                             BotService botService,
                             PrWorkflowOrchestrator prWorkflowOrchestrator,
                             E2eTestPrCloseHandler e2eTestPrCloseHandler,
                             E2eTestSlashCommandHandler e2eTestSlashCommandHandler,
                             UnitTestSlashCommandHandler unitTestSlashCommandHandler,
                             AgentReviewSlashCommandHandler agentReviewSlashCommandHandler,
                             ReadmeSyncSlashCommandHandler readmeSyncSlashCommandHandler,
                             I18nCoverageSlashCommandHandler i18nCoverageSlashCommandHandler,
                             FeedbackCommandHandler feedbackCommandHandler,
                             WorkflowSelectionService workflowSelectionService,
                             IssueWorkflowOrchestrator issueWorkflowOrchestrator) {
        this.repositoryPlatformFactory = repositoryPlatformFactory;
        this.agentSessionService = agentSessionService;
        this.botService = botService;
        this.prWorkflowOrchestrator = prWorkflowOrchestrator;
        this.e2eTestPrCloseHandler = e2eTestPrCloseHandler;
        this.e2eTestSlashCommandHandler = e2eTestSlashCommandHandler;
        this.unitTestSlashCommandHandler = unitTestSlashCommandHandler;
        this.agentReviewSlashCommandHandler = agentReviewSlashCommandHandler;
        this.readmeSyncSlashCommandHandler = readmeSyncSlashCommandHandler;
        this.i18nCoverageSlashCommandHandler = i18nCoverageSlashCommandHandler;
        this.feedbackCommandHandler = feedbackCommandHandler;
        this.workflowSelectionService = workflowSelectionService;
        this.issueWorkflowOrchestrator = issueWorkflowOrchestrator;
    }

    /**
     * Reviews a pull request via the {@link PrWorkflowOrchestrator}, which
     * dispatches to the {@link ReviewWorkflow} (and, in M2+, any other
     * workflows enabled for the bot via its {@code WorkflowConfiguration}).
     * <p>
     * Checks the user whitelist ({@link #isCallerAllowed}) before anything else, so that no
     * caller can start a review for someone who is not allowed. The
     * {@link org.remus.giteabot.webhook.WebhookEventDispatcher} checks it before every review
     * it starts as well, because it has to decide before it claims the commit.
     *
     * @return completes with {@code true} when at least one workflow ran without failing —
     *         a workflow that skipped itself or was cancelled counts as having run, since
     *         retrying it would not change the outcome; {@code false} when the review was
     *         skipped (caller not allowed, branch filtered, no workflow enabled) or every
     *         workflow failed. The dispatcher uses it to release its debounce claim, so a
     *         re-request is not swallowed.
     */
    @Async
    public CompletableFuture<Boolean> reviewPullRequest(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            if (!isCallerAllowed(bot, payload)) {
                return CompletableFuture.completedFuture(false);
            }

            // A review asked for in a conversation comment ("review again") arrives without
            // the pull request's head and base on providers that deliver such comments as
            // issue comments (GitHub, Gitea). The branch filter and the workflows need both.
            PrPayloadHydrator.hydrate(payload, () -> repositoryPlatformFactory.getApiClient(bot.getGitIntegration()));
            if (!prWorkflowAllowedForBranch(bot, payload)) {
                return CompletableFuture.completedFuture(false);
            }
            acknowledgePullRequest(bot, payload);
            try {
                boolean reviewed = prWorkflowOrchestrator.runAll(bot, payload).stream()
                        .map(PrWorkflowRun::getStatus)
                        .anyMatch(status -> status != PrWorkflowRunStatus.FAILED);
                return CompletableFuture.completedFuture(reviewed);
            } catch (Exception e) {
                log.error("[Bot '{}'] Failed to run PR workflows: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
                return CompletableFuture.completedFuture(false);
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    private void acknowledgeAssignedIssue(Bot bot, WebhookPayload payload) {
        if (payload.getIssue() == null
                || payload.getIssue().getNumber() == null
                || payload.getRepository() == null) {
            return;
        }
        String owner = payload.getRepository().getOwner() != null
                ? payload.getRepository().getOwner().getLogin() : null;
        String repo = payload.getRepository().getName();
        if (owner == null || repo == null) {
            return;
        }
        Long issueNumber = payload.getIssue().getNumber();
        try {
            if (IssueWorkflowOrchestrator.enabledWorkflowKeys(bot, workflowSelectionService).isEmpty()) {
                return;
            }
            repositoryPlatformFactory.getApiClient(bot.getGitIntegration())
                    .addIssueReaction(owner, repo, issueNumber, Reactions.EYES);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to add 👀 reaction to issue #{}: {}: {}",
                    bot.getName(), issueNumber, e.getClass().getSimpleName(), e.getMessage());
            log.debug("[Bot '{}'] 👀 reaction failure details", bot.getName(), e);
        }
    }

    private void acknowledgePullRequest(Bot bot, WebhookPayload payload) {
        if (!"opened".equals(payload.getAction())
                && !"review_requested".equals(payload.getAction())) {
            return;
        }
        if (payload.getRepository() == null
                || payload.getPullRequest() == null
                || payload.getPullRequest().getNumber() == null) {
            return;
        }
        String owner = payload.getRepository().getOwner() != null
                ? payload.getRepository().getOwner().getLogin() : null;
        String repo = payload.getRepository().getName();
        if (owner == null || repo == null) {
            return;
        }
        Long pullNumber = payload.getPullRequest().getNumber();
        try {
            if (PrWorkflowOrchestrator.enabledWorkflowKeys(bot, workflowSelectionService).isEmpty()) {
                return;
            }
            repositoryPlatformFactory.getApiClient(bot.getGitIntegration())
                    .addPullRequestReaction(owner, repo, pullNumber, Reactions.EYES);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to add 👀 reaction to PR #{}: {}: {}",
                    bot.getName(), pullNumber, e.getClass().getSimpleName(), e.getMessage());
            log.debug("[Bot '{}'] 👀 reaction failure details", bot.getName(), e);
        }
    }

    /**
     * Returns {@code true} when the incoming PR's branch/ref is allowed to start a
     * PR workflow under the bot's configured {@code branchFilter}.
     *
     * <p>The ref is taken from the PR's <em>base</em> (the target branch the PR
     * merges into — e.g. {@code releases/1.2} in a git-flow setup), falling back
     * to the <em>head</em> source branch when no base ref is present. Full ref
     * names such as {@code refs/heads/develop} are supported by
     * {@link BranchFilter}.
     */
    private boolean prWorkflowAllowedForBranch(Bot bot, WebhookPayload payload) {
        String branch = prBranchForFilter(payload);
        if (BranchFilter.matches(bot.getBranchFilter(), branch)) {
            return true;
        }
        log.info("Ignoring PR workflow for bot '{}': branch '{}' does not match branch filter '{}'",
                bot.getName(), branch, bot.getBranchFilter());
        return false;
    }

    /**
     * Resolves the branch/ref a PR workflow should be filtered on: the PR base
     * (target branch) when present, otherwise the head (source) branch.
     */
    private String prBranchForFilter(WebhookPayload payload) {
        if (payload.getPullRequest() == null) {
            return null;
        }
        if (payload.getPullRequest().getBase() != null
                && payload.getPullRequest().getBase().getRef() != null) {
            return payload.getPullRequest().getBase().getRef();
        }
        if (payload.getPullRequest().getHead() != null) {
            return payload.getPullRequest().getHead().getRef();
        }
        return null;
    }

    /**
     * Learning/feedback commands are classified and consumed before every other routing decision:
     * before review-again heuristics, before coding-session forwarding, and before the
     * "no PR workflow enabled" short circuit. A recognized command never reaches an AI conversation,
     * so a malformed one cannot start code review, change code, or generate tests.
     * <p>
     * Deliberately <em>not</em> {@code @Async}: callers use the return value to decide whether to
     * continue routing, and the async proxy returns {@code null} for a primitive result — which
     * becomes an {@code AopInvocationException} in the caller.
     *
     * @param effectiveAlias the mention the provider left in the comment, when it rewrote one;
     *                       {@code null} keeps the configured username
     * @return {@code true} when the comment was a learning command and has been handled
     */
    public boolean tryHandleLearningCommand(Bot bot, WebhookPayload payload, String commentKind,
                                           String effectiveAlias) {
        try {
            return feedbackCommandHandler.tryHandle(bot, payload, commentKind, effectiveAlias);
        } catch (RuntimeException e) {
            // Falling through would forward feedback text into an AI conversation.
            log.error("[Bot '{}'] Learning command handling failed: {}", bot.getName(),
                    e.getMessage(), e);
            return true;
        }
    }

    /**
     * Handles a comment on a PR discussion thread.
     * <p>
     * Access control: if the bot has no {@code userWhitelist}, any user mentioning the bot
     * may interact.  If a whitelist is configured, only the users listed in it may interact —
     * all other commenters are ignored, the pull request's author included
     * ({@link #isCallerAllowed}).
     * <p>
     * Routes to the configured issue-assigned workflow(s) when an agent session exists for the
     * PR (i.e. the PR was created by the issue workflow and can be continued — the comment is a
     * follow-up in the same flow lifecycle).  For manually created PRs (no active session), the
     * comment is routed to the code-review handler:
     * <ol>
     *   <li>the slash-command handlers ({@link E2eTestSlashCommandHandler},
     *       {@link UnitTestSlashCommandHandler}, ...) — recognised slash commands;</li>
     *   <li>{@link CodeReviewService#handleBotCommand(WebhookPayload, String)} —
     *       general-purpose review fallback, <em>only</em> when the
     *       {@link ReviewWorkflow review workflow} is enabled on the bot's
     *       configuration. A bot that is not configured to run code reviews
     *       must never silently fall into the reviewer prompt; instead it
     *       posts a short "command not understood" reply.</li>
     * </ol>
     */
    @Async
    public void handlePrComment(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            if (!isCallerAllowed(bot, payload)) {
                return;
            }
            Long prNumber = payload.getPullRequest().getNumber();

            Optional<AgentSession> agentSession = findAgentSession(bot, payload);
            if (agentSession.isPresent()) {
                // The PR was created by the bot's issue workflow (an agent session
                // exists): the comment continues that same flow, so it routes
                // through the configured issue-workflow resolution exactly like a
                // comment on the original issue would.
                log.debug("[Bot '{}'] Agent session found for PR #{}, routing to issue workflow", bot.getName(), prNumber);
                try {
                    issueWorkflowOrchestrator.runComment(bot, payloadFromSession(bot, payload, agentSession.get()));
                } catch (Exception e) {
                    log.error("[Bot '{}'] Failed to handle PR comment via issue workflow: {}", bot.getName(), e.getMessage(), e);
                    botService.recordError(bot, e.getMessage());
                }
            } else {
                if (hasNoEnabledPrWorkflows(bot)) {
                    log.debug("[Bot '{}'] No PR workflows enabled, ignoring pull request comment", bot.getName());
                    return;
                }
                log.debug("[Bot '{}'] No agent session for PR #{}, routing to code-review handler",
                        bot.getName(), prNumber);
                try {
                    if (e2eTestSlashCommandHandler.tryHandle(bot, payload)) {
                        return;
                    }
                    if (unitTestSlashCommandHandler.tryHandle(bot, payload)) {
                        return;
                    }
                    if (agentReviewSlashCommandHandler.tryHandle(bot, payload)) {
                        return;
                    }
                    if (readmeSyncSlashCommandHandler.tryHandle(bot, payload)) {
                        return;
                    }
                    if (i18nCoverageSlashCommandHandler.tryHandle(bot, payload)) {
                        return;
                    }
                    if (isWorkflowEnabled(bot, ReviewWorkflow.KEY)) {
                        // Route through the PrWorkflow orchestrator for uniform lifecycle management.
                        var hints = Map.of(ReviewWorkflow.HINT_REVIEW_ACTION, ReviewWorkflow.ACTION_BOT_COMMAND);
                        prWorkflowOrchestrator.run(bot, payload, ReviewWorkflow.KEY, hints);
                        return;
                    }
                    log.info("[Bot '{}'] Comment mentions bot but no slash command matched and review workflow is not enabled — replying with unrecognised-command notice",
                            bot.getName());
                    postUnrecognisedCommandComment(bot, payload);
                } catch (Exception e) {
                    log.error("[Bot '{}'] Failed to handle PR comment via review handler: {}", bot.getName(), e.getMessage(), e);
                    botService.recordError(bot, e.getMessage());
                }
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    /**
     * Whether the pull request of a pull request comment belongs to an agent session, i.e.
     * was created by the bot's issue workflow, so that comments on it continue that flow.
     * The payload must carry both the pull request and its issue, as the dispatcher's
     * completed comment payloads do.
     * <p>
     * The pull request's number is also looked up as an issue number only where issues and
     * pull requests share one number sequence
     * ({@link RepositoryPlatform#sharesIssueAndPullRequestNumbers}); elsewhere only the
     * session's pull request number identifies it.
     */
    public boolean hasAgentSession(Bot bot, WebhookPayload payload) {
        return findAgentSession(bot, payload).isPresent();
    }

    private Optional<AgentSession> findAgentSession(Bot bot, WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        if (sharesIssueAndPullRequestNumbers(bot)) {
            Long issueNumber = payload.getIssue().getNumber();
            Optional<AgentSession> byIssue = agentSessionService.getSessionByIssue(owner, repo, issueNumber);
            if (byIssue.isPresent()) {
                return byIssue;
            }
        }
        return agentSessionService.getSessionByPr(owner, repo, prNumber);
    }

    /**
     * The payload the issue workflows get for a comment on an agent session's pull request.
     * They find their session by the payload's issue number. Where issues and pull requests
     * share one number sequence, the pull request's number finds it, so the payload stays as
     * it is and replies go to the pull request. Elsewhere the pull request's number belongs
     * to an unrelated issue, if to any: merge request !5 must not continue the session of
     * issue #5. There the issue becomes the session's own issue, which the pull request
     * closes, and the agent's replies go to that issue.
     */
    WebhookPayload payloadFromSession(Bot bot, WebhookPayload payload, AgentSession session) {
        if (sharesIssueAndPullRequestNumbers(bot) || session.getIssueNumber() == null) {
            return payload;
        }
        WebhookPayload.Issue prIssue = payload.getIssue();
        WebhookPayload.Issue sessionIssue = new WebhookPayload.Issue();
        sessionIssue.setNumber(session.getIssueNumber());
        sessionIssue.setTitle(session.getIssueTitle());
        sessionIssue.setUser(prIssue.getUser());
        sessionIssue.setPullRequest(prIssue.getPullRequest());
        // toBuilder() carries every other field over, including ones added to WebhookPayload later
        return payload.toBuilder().issue(sessionIssue).build();
    }

    private boolean sharesIssueAndPullRequestNumbers(Bot bot) {
        return repositoryPlatformFactory.getPlatform(bot.getGitIntegration()).sharesIssueAndPullRequestNumbers();
    }

    /**
     * Handles an inline review comment mentioning the bot. Like a conversation comment, it is
     * answered for anyone the user whitelist allows ({@link #isCallerAllowed}).
     * Delegates to {@link CodeReviewService#handleInlineComment(WebhookPayload, String)}.
     */
    @Async
    public void handleInlineComment(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            if (!isCallerAllowed(bot, payload)) {
                return;
            }
            boolean agenticEnabled = isWorkflowEnabled(bot, AgentReviewWorkflow.KEY);
            boolean reviewEnabled  = isWorkflowEnabled(bot, ReviewWorkflow.KEY);
            if (!agenticEnabled && !reviewEnabled) {
                log.debug("[Bot '{}'] Neither review nor agentic-review enabled — ignoring inline review comment", bot.getName());
                return;
            }
            try {
                if (agenticEnabled) {
                    String question = extractInlineCommentBody(payload);
                    var hints = Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION,
                            question != null ? question : "");
                    prWorkflowOrchestrator.run(bot, payload, AgentReviewWorkflow.KEY, hints);
                    return;
                }
                var hints = Map.of(ReviewWorkflow.HINT_REVIEW_ACTION, ReviewWorkflow.ACTION_INLINE_COMMENT);
                prWorkflowOrchestrator.run(bot, payload, ReviewWorkflow.KEY, hints);
            } catch (Exception e) {
                log.error("[Bot '{}'] Failed to handle inline comment via workflow: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    /**
     * Handles a review submitted event (responds to pending review comments).
     * Delegates to {@link CodeReviewService#handleReviewSubmitted(WebhookPayload, String)}.
     */
    @Async
    public void handleReviewSubmitted(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            if (!isCallerAllowed(bot, payload)) {
                return;
            }
            boolean agenticEnabled = isWorkflowEnabled(bot, AgentReviewWorkflow.KEY);
            boolean reviewEnabled  = isWorkflowEnabled(bot, ReviewWorkflow.KEY);
            if (!agenticEnabled && !reviewEnabled) {
                log.debug("[Bot '{}'] Neither review nor agentic-review enabled — ignoring submitted review", bot.getName());
                return;
            }
            try {
                if (agenticEnabled) {
                    String question = extractReviewBody(payload);
                    if (!BotMention.isMentioned(question, BotIdentity.of(bot).alias())) {
                        log.debug("[Bot '{}'] Submitted review does not mention the bot — ignoring (agentic-review only responds when addressed)",
                                bot.getName());
                        return;
                    }
                    var hints = Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, question);
                    prWorkflowOrchestrator.run(bot, payload, AgentReviewWorkflow.KEY, hints);
                    return;
                }
                var hints = Map.of(ReviewWorkflow.HINT_REVIEW_ACTION, ReviewWorkflow.ACTION_REVIEW_SUBMITTED);
                prWorkflowOrchestrator.run(bot, payload, ReviewWorkflow.KEY, hints);
            } catch (Exception e) {
                log.error("[Bot '{}'] Failed to handle review submitted via workflow: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    /**
     * Handles PR closed event by cleaning up the session.
     * Delegates to {@link CodeReviewService#handlePrClosed(WebhookPayload)}.
     *
     * <p>Also invokes {@link E2eTestPrCloseHandler#onPrClosed} so the M4
     * {@code E2ETestWorkflow} can release any preview deployments,
     * sandbox workspaces and ephemeral test suites it created for the PR.
     * Both close-handlers are wrapped in their own try/catch so a failure
     * in one (e.g. the review-session cleanup) never blocks the other
     * (e.g. the E2E preview teardown) — leaked preview envs and stale
     * test suites on PR close would otherwise accumulate silently.</p>
     */
    public void handlePrClosed(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            try {
                if (isWorkflowEnabled(bot, ReviewWorkflow.KEY)) {
                    var hints = Map.of(ReviewWorkflow.HINT_REVIEW_ACTION, ReviewWorkflow.ACTION_PR_CLOSED);
                    prWorkflowOrchestrator.run(bot, payload, ReviewWorkflow.KEY, hints);
                }
            } catch (RuntimeException e) {
                log.warn("[Bot '{}'] CodeReviewService.handlePrClosed threw {} — continuing with E2E teardown",
                        bot.getName(), e.toString());
            }
            try {
                Long prNumber = payload.getPullRequest() == null
                        ? null
                        : payload.getPullRequest().getNumber();
                String owner = payload.getRepository() == null || payload.getRepository().getOwner() == null
                        ? null
                        : payload.getRepository().getOwner().getLogin();
                String repoName = payload.getRepository() == null
                        ? null
                        : payload.getRepository().getName();
                boolean merged = payload.getPullRequest() != null
                        && Boolean.TRUE.equals(payload.getPullRequest().getMerged());
                e2eTestPrCloseHandler.onPrClosed(bot.getId(), owner, repoName, prNumber, merged, payload);
            } catch (RuntimeException e) {
                log.warn("[Bot '{}'] E2eTestPrCloseHandler threw {} — ignoring",
                        bot.getName(), e.toString());
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    /**
     * Handles an issue assigned event by running the {@code IssueWorkflow}(s)
     * enabled on the bot's issue-assigned {@code WorkflowConfiguration} (kind
     * {@code ISSUE}). The orchestrator owns the run lifecycle
     * ({@code issueassignment.*} outgoing events, bot error recording).
     */
    @Async
    public void handleIssueAssigned(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            if (!isCallerAllowed(bot, payload)) {
                return;
            }
            acknowledgeAssignedIssue(bot, payload);
            try {
                issueWorkflowOrchestrator.runAssigned(bot, payload);
            } catch (Exception e) {
                // Defense-in-depth: the orchestrator already records errors per workflow.
                log.error("[Bot '{}'] Failed to handle issue assignment: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    /**
     * Handles an issue created event by running the {@code IssueWorkflow}(s)
     * enabled on the bot's issue-assigned {@code WorkflowConfiguration} when
     * {@link Bot#isRunOnIssueCreation()} is enabled. Reuses the same lifecycle
     * as assignment via {@link IssueWorkflowOrchestrator#runAssigned(Bot, WebhookPayload)}.
     */
    @Async
    public void handleIssueCreated(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            if (!bot.isRunOnIssueCreation()) {
                log.debug("[Bot '{}'] Ignoring issue creation — runOnIssueCreation is disabled", bot.getName());
                return;
            }
            if (!isCallerAllowed(bot, payload)) {
                return;
            }
            // Issue creation is handled by reusing the issue-assigned workflow. The
            // individual workflow implementations check that the issue is assigned to
            // the bot, so treat this creation event as a virtual assignment to this bot.
            WebhookPayload.Issue issue = payload.getIssue();
            if (issue != null) {
                WebhookPayload.Owner assignee = new WebhookPayload.Owner();
                assignee.setLogin(bot.getGitIntegration().getUsername());
                issue.setAssignee(assignee);
            }
            try {
                issueWorkflowOrchestrator.runAssigned(bot, payload);
            } catch (Exception e) {
                // Defense-in-depth: the orchestrator already records errors per workflow.
                log.error("[Bot '{}'] Failed to handle issue creation: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    /**
     * Handles a comment on an issue by routing it through the same
     * {@code IssueWorkflow}(s) resolved from the bot's issue-assigned
     * {@code WorkflowConfiguration}.
     */
    @Async
    public void handleIssueComment(Bot bot, WebhookPayload payload) {
        try {
            AiAuditContext.setSessionId(auditSessionId(payload));
            if (!isCallerAllowed(bot, payload)) {
                return;
            }
            try {
                issueWorkflowOrchestrator.runComment(bot, payload);
            } catch (Exception e) {
                // Defense-in-depth: the orchestrator already records errors per workflow.
                log.error("[Bot '{}'] Failed to handle issue comment: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
        } finally {
            AiAuditContext.clear();
        }
    }

    /**
     * Checks whether a given workflow key is enabled on the bot's
     * {@link org.remus.giteabot.prworkflow.config.WorkflowConfiguration}.
     *
     * <p>Bots without a workflow configuration fall back to the legacy
     * default (only {@link ReviewWorkflow} is implicitly enabled), matching
     * the behaviour of {@link PrWorkflowOrchestrator#runAll(Bot, WebhookPayload)}.</p>
     */
    boolean isWorkflowEnabled(Bot bot, String workflowKey) {
        if (bot == null || workflowKey == null) {
            return false;
        }
        if (bot.getWorkflowConfiguration() == null) {
            // Legacy: bots without an explicit configuration only run the review workflow.
            return ReviewWorkflow.KEY.equals(workflowKey);
        }
        try {
            return workflowSelectionService
                    .enabledWorkflowKeys(bot.getWorkflowConfiguration().getId())
                    .contains(workflowKey);
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] enabled-check for workflow '{}' failed: {}",
                    bot.getName(), workflowKey, e.getMessage());
            return false;
        }
    }

    /**
     * Returns {@code true} when the bot has an explicit PR
     * {@link org.remus.giteabot.prworkflow.config.WorkflowConfiguration}
     * that enables <em>no</em> workflows at all. Such bots ignore PR comment
     * / command events silently — this preserves the historic "writer bot
     * never reacts on PRs" behavior in configuration terms (migrated writer
     * bots reference the seeded empty {@code No PR workflows} configuration).
     *
     * <p>Bots without a configuration fall back to the legacy default
     * (review enabled), so they return {@code false} here.</p>
     */
    private boolean hasNoEnabledPrWorkflows(Bot bot) {
        if (bot == null || bot.getWorkflowConfiguration() == null) {
            return false;
        }
        try {
            return workflowSelectionService
                    .enabledWorkflowKeys(bot.getWorkflowConfiguration().getId())
                    .isEmpty();
        } catch (RuntimeException e) {
            log.debug("[Bot '{}'] enabled-keys lookup failed: {}", bot.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Posts a short reply telling the user that the bot did not recognise
     * their command. Used when the bot is mentioned on a PR but
     * <ol>
     *   <li>no slash-command handler picked it up, and</li>
     *   <li>the {@link ReviewWorkflow review workflow} is not enabled, so
     *       falling into the generic code-review prompt would mean running
     *       a workflow the bot has not been configured for.</li>
     * </ol>
     * The reply is best-effort: any failure to post is swallowed and
     * logged.
     */
    private void postUnrecognisedCommandComment(Bot bot, WebhookPayload payload) {
        if (payload == null || payload.getRepository() == null
                || payload.getRepository().getOwner() == null) {
            return;
        }
        Long prNumber = resolvePrOrIssueNumber(payload);
        if (prNumber == null) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        try {
            RepositoryPlatform platform = repositoryPlatformFactory.getPlatform(bot.getGitIntegration());
            String body = buildUnrecognisedCommandReply(bot, resolveMentionName(bot, platform));
            platform.getClient().postPullRequestComment(owner, repo, prNumber, body);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to post unrecognised-command reply on PR #{}: {}",
                    bot.getName(), prNumber, e.getMessage());
        }
    }

    /**
     * Name to show after {@code @} in replies: the provider's mention name if it has one,
     * otherwise the bot username. Falls back to "bot" when the provider lookup fails, since
     * the bot username may not identify the bot on that provider (e.g. Bitbucket).
     */
    private String resolveMentionName(Bot bot, RepositoryPlatform platform) {
        try {
            var providerName = platform.getBotMentionName();
            if (providerName.isPresent()) {
                return providerName.get();
            }
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Could not resolve the bot's mention name from the Git provider: {}",
                    bot.getName(), e.getMessage());
            return "bot";
        }
        return bot.getGitIntegration().getUsername();
    }

    private String buildUnrecognisedCommandReply(Bot bot, String mention) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 Sorry, I did not understand that command.\n\n");
        sb.append("This bot is not configured to run code reviews, so I can only respond ");
        sb.append("to the slash commands listed below. Anything else will be ignored.\n\n");
        boolean e2eEnabled = isWorkflowEnabled(bot, E2ETestWorkflow.KEY);
        boolean unitEnabled = isWorkflowEnabled(bot, UnitTestWorkflow.KEY);
        if (e2eEnabled || unitEnabled) {
            sb.append("Available commands:\n");
            if (e2eEnabled) {
                sb.append("- `@").append(mention)
                        .append(" rerun-tests` — re-run the most recent E2E test suite for this PR.\n");
                sb.append("- `@").append(mention)
                        .append(" regenerate-tests [feedback]` — regenerate the E2E test suite from scratch, ")
                        .append("optionally with free-form feedback for the planner.\n");
            }
            if (unitEnabled) {
                sb.append("- `@").append(mention)
                        .append(" generate-tests` — generate white-box unit tests for this PR and commit them to the branch.\n");
                sb.append("- `@").append(mention)
                        .append(" rerun-unit-tests` — regenerate and re-run the unit-test suite for this PR.\n");
            }
        } else {
            sb.append("No interactive commands are configured for this bot.\n");
        }
        return sb.toString();
    }

    /**
     * Builds the logical session id ({@code owner/repo#number}) used to tag AI
     * usage and error audit records for this webhook event.
     */
    private String auditSessionId(WebhookPayload payload) {
        if (payload == null || payload.getRepository() == null
                || payload.getRepository().getOwner() == null) {
            return null;
        }
        Long number = resolvePrOrIssueNumber(payload);
        return payload.getRepository().getOwner().getLogin() + "/"
                + payload.getRepository().getName()
                + (number != null ? "#" + number : "");
    }

    private Long resolvePrOrIssueNumber(WebhookPayload payload) {
        return PrPayloadHydrator.resolvePrNumber(payload);
    }

    /**
     * Token-spend guard for public-repo deployments: returns {@code true}
     * when the bot's configured {@link Bot#getUserWhitelist() user
     * whitelist} permits the webhook caller, or when no whitelist is
     * configured (historical "everyone allowed" behaviour).
     *
     * <p>The whitelist is parsed once via
     * {@link BotService#getAllowedUsernames(Bot)}; the resulting set is
     * then passed directly to
     * {@link BotService#isUsernameInSet(Set, String)} so the blob is
     * never re-parsed for the membership check. All lowercasing uses
     * {@link java.util.Locale#ROOT} for locale-independent identifier
     * comparison.</p>
     */
    public boolean isCallerAllowed(Bot bot, WebhookPayload payload) {
        if (bot == null) {
            return true;
        }
        Set<String> allowed = botService.getAllowedUsernames(bot);
        if (allowed.isEmpty()) {
            return true;
        }
        String caller = resolveCallerUsername(payload);
        if (botService.isUsernameInSet(allowed, caller)) {
            return true;
        }
        log.info("[Bot '{}'] Ignoring webhook from user '{}' — not in whitelist ({} entries)",
                bot.getName(),
                caller == null ? "<unknown>" : caller,
                allowed.size());
        return false;
    }

    /**
     * Resolves the most specific username that the webhook payload
     * exposes for the triggering actor. Mirrors the lookup order
     * documented on {@link #isCallerAllowed(Bot, WebhookPayload)}.
     */
    private String resolveCallerUsername(WebhookPayload payload) {
        if (payload == null) {
            return null;
        }
        if (payload.getComment() != null && payload.getComment().getUser() != null) {
            return payload.getComment().getUser().getLogin();
        }
        if (payload.getSender() != null && payload.getSender().getLogin() != null) {
            return payload.getSender().getLogin();
        }
        if (payload.getPullRequest() != null && payload.getPullRequest().getUser() != null) {
            return payload.getPullRequest().getUser().getLogin();
        }
        if (payload.getIssue() != null && payload.getIssue().getUser() != null) {
            return payload.getIssue().getUser().getLogin();
        }
        return null;
    }

    public boolean isReviewAgainRequest(WebhookPayload payload, String botAlias) {
        String body = payload.getComment() != null ? payload.getComment().getBody() : null;
        if (!BotMention.isMentioned(body, botAlias)) {
            return false;
        }
        // Ignore the alias itself, so a bot named e.g. @reviewbot does not count as asking for a review
        String normalized = BotMention.replaceMention(body, botAlias, " ");
        return REVIEW_AGAIN.matcher(normalized).find();
    }

    /**
     * Extracts the body text from an inline review comment to use as a
     * clarification question for the agentic-review workflow.
     */
    private String extractInlineCommentBody(WebhookPayload payload) {
        if (payload == null || payload.getComment() == null) {
            return null;
        }
        String body = payload.getComment().getBody();
        if (body == null || body.isBlank()) {
            return null;
        }
        // Include the file path for context when available.
        String path = payload.getComment().getPath();
        if (path != null && !path.isBlank()) {
            return "Regarding `" + path + "`: " + body;
        }
        return body;
    }

    /**
     * Extracts the review body text to use as a clarification question for the
     * agentic-review workflow.
     */
    private String extractReviewBody(WebhookPayload payload) {
        if (payload == null || payload.getReview() == null) {
            return null;
        }
        String content = payload.getReview().getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        return content;
    }

}
