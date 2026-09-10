package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.issueworkflow.IssueWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
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
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.util.BranchFilter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Handles webhook events for persisted {@link Bot} entities using their
 * specific {@link AiIntegration} and {@link GitIntegration} configurations.
 * <p>
 * This is the bridge between the admin data model and the code-review / agent
 * services.  Each bot gets its own {@link AiClient} (via {@link AiClientFactory})
 * and its own {@link RepositoryApiClient} (via {@link GiteaClientFactory}).
 * <p>
 * PR events are delegated to the {@link PrWorkflowOrchestrator}, issue events
 * (assignments and comments, including follow-ups on agent-created PRs) to the
 * {@link IssueWorkflowOrchestrator} — both resolve what runs from the bot's
 * workflow configurations, never from a hardcoded bot category.
 */
@Slf4j
@Service
public class BotWebhookService {

    private final GiteaClientFactory giteaClientFactory;
    private final AgentSessionService agentSessionService;
    private final BotService botService;
    private final PrWorkflowOrchestrator prWorkflowOrchestrator;
    private final E2eTestPrCloseHandler e2eTestPrCloseHandler;
    private final E2eTestSlashCommandHandler e2eTestSlashCommandHandler;
    private final UnitTestSlashCommandHandler unitTestSlashCommandHandler;
    private final AgentReviewSlashCommandHandler agentReviewSlashCommandHandler;
    private final ReadmeSyncSlashCommandHandler readmeSyncSlashCommandHandler;
    private final I18nCoverageSlashCommandHandler i18nCoverageSlashCommandHandler;
    private final WorkflowSelectionService workflowSelectionService;
    private final IssueWorkflowOrchestrator issueWorkflowOrchestrator;

    public BotWebhookService(GiteaClientFactory giteaClientFactory,
                             AgentSessionService agentSessionService,
                             BotService botService,
                             PrWorkflowOrchestrator prWorkflowOrchestrator,
                             E2eTestPrCloseHandler e2eTestPrCloseHandler,
                             E2eTestSlashCommandHandler e2eTestSlashCommandHandler,
                             UnitTestSlashCommandHandler unitTestSlashCommandHandler,
                             AgentReviewSlashCommandHandler agentReviewSlashCommandHandler,
                             ReadmeSyncSlashCommandHandler readmeSyncSlashCommandHandler,
                             I18nCoverageSlashCommandHandler i18nCoverageSlashCommandHandler,
                             WorkflowSelectionService workflowSelectionService,
                             IssueWorkflowOrchestrator issueWorkflowOrchestrator) {
        this.giteaClientFactory = giteaClientFactory;
        this.agentSessionService = agentSessionService;
        this.botService = botService;
        this.prWorkflowOrchestrator = prWorkflowOrchestrator;
        this.e2eTestPrCloseHandler = e2eTestPrCloseHandler;
        this.e2eTestSlashCommandHandler = e2eTestSlashCommandHandler;
        this.unitTestSlashCommandHandler = unitTestSlashCommandHandler;
        this.agentReviewSlashCommandHandler = agentReviewSlashCommandHandler;
        this.readmeSyncSlashCommandHandler = readmeSyncSlashCommandHandler;
        this.i18nCoverageSlashCommandHandler = i18nCoverageSlashCommandHandler;
        this.workflowSelectionService = workflowSelectionService;
        this.issueWorkflowOrchestrator = issueWorkflowOrchestrator;
    }

    /**
     * Reviews a pull request via the {@link PrWorkflowOrchestrator}, which
     * dispatches to the {@link ReviewWorkflow} (and, in M2+, any other
     * workflows enabled for the bot via its {@code WorkflowConfiguration}).
     */
    @Async
    public void reviewPullRequest(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));

        if (!prWorkflowAllowedForBranch(bot, payload)) {
            return;
        }
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        try {
            prWorkflowOrchestrator.runAll(bot, payload);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to run PR workflows: {}", bot.getName(), e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
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
     * Handles a bot-mention command in a PR comment.
     * <p>
     * Routing order:
     * <ol>
     *   <li>{@link E2eTestSlashCommandHandler} — recognised E2E slash commands.</li>
     *   <li>{@link UnitTestSlashCommandHandler} — recognised unit-test slash
     *       commands ({@code @bot generate-tests} / {@code @bot rerun-unit-tests}).</li>
     *   <li>{@link CodeReviewService#handleBotCommand(WebhookPayload, String)} —
     *       general-purpose review fallback, <em>only</em> when the
     *       {@link ReviewWorkflow review workflow} is enabled on the bot's
     *       configuration. A bot that is not configured to run code reviews
     *       must never silently fall into the reviewer prompt; instead we
     *       post a short "command not understood" reply.</li>
     * </ol>
     */
    @Async
    public void handleBotCommand(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (!isPullRequestAuthor(payload)) {
            log.debug("[Bot '{}'] Ignoring pull request command from non-author", bot.getName());
            return;
        }
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        if (hasNoEnabledPrWorkflows(bot)) {
            log.debug("[Bot '{}'] No PR workflows enabled, ignoring pull request command", bot.getName());
            return;
        }
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
            log.error("[Bot '{}'] Failed to handle command: {}", bot.getName(), e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles a comment on a PR discussion thread.
     * <p>
     * Access control: if the bot has no {@code userWhitelist}, any user mentioning the bot
     * may interact.  If a whitelist is configured, only the PR author <em>or</em> users listed
     * in the whitelist may interact — all other commenters are ignored.
     * <p>
     * Routes to the configured issue-assigned workflow(s) when an agent session exists for the
     * PR (i.e. the PR was created by the issue workflow and can be continued — the comment is a
     * follow-up in the same flow lifecycle).  For manually created PRs (no active session), the
     * comment is routed to the code-review handler.
     */
    @Async
    public void handlePrComment(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (!isPrCommenterAllowed(bot, payload)) {
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        Long issueNumber = payload.getIssue().getNumber(); // equals prNumber for PRs in Gitea

        boolean hasAgentSession =
                agentSessionService.getSessionByIssue(owner, repo, issueNumber).isPresent()
                || agentSessionService.getSessionByPr(owner, repo, prNumber).isPresent();

        if (hasAgentSession) {
            // The PR was created by the bot's issue workflow (an agent session
            // exists): the comment continues that same flow, so it routes
            // through the configured issue-workflow resolution exactly like a
            // comment on the original issue would.
            log.debug("[Bot '{}'] Agent session found for PR #{}, routing to issue workflow", bot.getName(), prNumber);
            try {
                issueWorkflowOrchestrator.runComment(bot, payload);
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
    }

    /**
     * Handles an inline review comment mentioning the bot.
     * Delegates to {@link CodeReviewService#handleInlineComment(WebhookPayload, String)}.
     */
    @Async
    public void handleInlineComment(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (!isPullRequestAuthor(payload)) {
            log.debug("[Bot '{}'] Ignoring inline review comment from non-author", bot.getName());
            return;
        }
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
    }

    /**
     * Handles a review submitted event (responds to pending review comments).
     * Delegates to {@link CodeReviewService#handleReviewSubmitted(WebhookPayload, String)}.
     */
    @Async
    public void handleReviewSubmitted(Bot bot, WebhookPayload payload) {
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
                if (!mentionsBot(bot, question)) {
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
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        try {
            issueWorkflowOrchestrator.runAssigned(bot, payload);
        } catch (Exception e) {
            // Defense-in-depth: the orchestrator already records errors per workflow.
            log.error("[Bot '{}'] Failed to handle issue assignment: {}", bot.getName(), e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
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
        if (issue != null && bot.getUsername() != null && !bot.getUsername().isBlank()) {
            WebhookPayload.Owner assignee = new WebhookPayload.Owner();
            assignee.setLogin(bot.getUsername());
            issue.setAssignee(assignee);
        }
        try {
            issueWorkflowOrchestrator.runAssigned(bot, payload);
        } catch (Exception e) {
            // Defense-in-depth: the orchestrator already records errors per workflow.
            log.error("[Bot '{}'] Failed to handle issue creation: {}", bot.getName(), e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles a comment on an issue by routing it through the same
     * {@code IssueWorkflow}(s) resolved from the bot's issue-assigned
     * {@code WorkflowConfiguration}.
     */
    @Async
    public void handleIssueComment(Bot bot, WebhookPayload payload) {
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
        String body = buildUnrecognisedCommandReply(bot);
        try {
            RepositoryApiClient client = giteaClientFactory.getApiClient(bot.getGitIntegration());
            client.postIssueComment(owner, repo, prNumber, body);
        } catch (RuntimeException e) {
            log.warn("[Bot '{}'] Failed to post unrecognised-command reply on PR #{}: {}",
                    bot.getName(), prNumber, e.getMessage());
        }
    }

    private String buildUnrecognisedCommandReply(Bot bot) {
        String mention = bot.getUsername() == null ? "bot" : bot.getUsername();
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
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        return payload.getNumber();
    }

    /**
     * Access-control check specific to {@link #handlePrComment(Bot, WebhookPayload)}.
     *
     * <p>Semantics:</p>
     * <ul>
     *   <li>If the bot has <strong>no</strong> {@code userWhitelist} → every commenter is allowed.</li>
     *   <li>If a whitelist <strong>is</strong> configured → only the PR author <em>or</em> users
     *       present in the whitelist may interact; everyone else is rejected.</li>
     * </ul>
     */
    boolean isPrCommenterAllowed(Bot bot, WebhookPayload payload) {
        if (bot == null) {
            return true;
        }
        Set<String> allowed = botService.getAllowedUsernames(bot);
        if (allowed.isEmpty()) {
            // No whitelist → everyone may interact with the bot on PRs.
            return true;
        }
        // Whitelist exists → allow PR author or whitelisted users.
        if (isPullRequestAuthor(payload)) {
            return true;
        }
        String caller = resolveCallerUsername(payload);
        if (botService.isUsernameInSet(allowed, caller)) {
            return true;
        }
        log.info("[Bot '{}'] Ignoring PR comment from '{}' — not PR author and not in whitelist ({} entries)",
                bot.getName(),
                caller == null ? "<unknown>" : caller,
                allowed.size());
        return false;
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
    boolean isCallerAllowed(Bot bot, WebhookPayload payload) {
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

    /**
     * Checks whether the webhook event was triggered by this bot's own user.
     */
    public boolean isBotUser(Bot bot, WebhookPayload payload) {
        String botUsername = bot.getUsername();
        if (botUsername == null || botUsername.isBlank()) {
            return false;
        }

        if (payload.getSender() != null && botUsername.equalsIgnoreCase(payload.getSender().getLogin())) {
            return true;
        }

        return payload.getComment() != null
                && payload.getComment().getUser() != null
                && botUsername.equalsIgnoreCase(payload.getComment().getUser().getLogin());
    }

    /**
     * Returns the bot alias used for @-mention detection,
     * or an empty string if the bot has no username configured.
     */
    public String getBotAlias(Bot bot) {
        String username = bot.getUsername();
        if (username == null || username.isBlank()) {
            return "";
        }
        return "@" + username;
    }

    /**
     * Returns {@code true} when {@code text} @-mentions the bot. Used to gate
     * agentic responses so the bot only reacts when it is explicitly addressed,
     * never on unrelated activity (e.g. another reviewer's approval or comment).
     */
    private boolean mentionsBot(Bot bot, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String alias = getBotAlias(bot);
        return !alias.isEmpty() && text.contains(alias);
    }

    public boolean isPullRequestAuthor(WebhookPayload payload) {
        String author = null;
        if (payload.getPullRequest() != null && payload.getPullRequest().getUser() != null) {
            author = payload.getPullRequest().getUser().getLogin();
        } else if (payload.getIssue() != null && payload.getIssue().getUser() != null) {
            author = payload.getIssue().getUser().getLogin();
        }

        String commenter = null;
        if (payload.getComment() != null && payload.getComment().getUser() != null) {
            commenter = payload.getComment().getUser().getLogin();
        } else if (payload.getSender() != null) {
            commenter = payload.getSender().getLogin();
        }

        return author != null && author.equalsIgnoreCase(commenter);
    }

    public boolean isReviewAgainRequestFromPullRequestAuthor(WebhookPayload payload, String botAlias) {
        if (!isPullRequestAuthor(payload)) {
            return false;
        }
        return isReviewAgainRequest(payload, botAlias);
    }

    public boolean isReviewAgainRequest(WebhookPayload payload, String botAlias) {
        String body = payload.getComment() != null ? payload.getComment().getBody() : null;
        if (body == null || botAlias == null || !body.contains(botAlias)) {
            return false;
        }
        String normalized = body.toLowerCase();
        return normalized.contains("review")
                && (normalized.contains("again") || normalized.contains("re-review") || normalized.contains("repeat"));
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
