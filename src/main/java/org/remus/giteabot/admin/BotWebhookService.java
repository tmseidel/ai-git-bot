package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.IssueImplementationService;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.agent.writerimpl.WriterAgentService;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.e2e.E2ETestWorkflow;
import org.remus.giteabot.prworkflow.e2e.E2eTestPrCloseHandler;
import org.remus.giteabot.prworkflow.e2e.E2eTestSlashCommandHandler;
import org.remus.giteabot.prworkflow.review.CodeReviewServiceFactory;
import org.remus.giteabot.prworkflow.review.ReviewWorkflow;
import org.remus.giteabot.prworkflow.unittest.UnitTestSlashCommandHandler;
import org.remus.giteabot.prworkflow.unittest.UnitTestWorkflow;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Handles webhook events for persisted {@link Bot} entities using their
 * specific {@link AiIntegration} and {@link GitIntegration} configurations.
 * <p>
 * This is the bridge between the admin data model and the code-review / agent
 * services.  Each bot gets its own {@link AiClient} (via {@link AiClientFactory})
 * and its own {@link RepositoryApiClient} (via {@link GiteaClientFactory}).
 * <p>
 * Actual business logic is delegated to {@link CodeReviewService} and
 * {@link IssueImplementationService}, which are instantiated per-bot with the
 * bot's specific AI and Git clients.
 */
@Slf4j
@Service
public class BotWebhookService {

    private final GiteaClientFactory giteaClientFactory;
    private final AgentSessionService agentSessionService;
    private final BotService botService;
    private final PrWorkflowOrchestrator prWorkflowOrchestrator;
    private final CodeReviewServiceFactory codeReviewServiceFactory;
    private final E2eTestPrCloseHandler e2eTestPrCloseHandler;
    private final E2eTestSlashCommandHandler e2eTestSlashCommandHandler;
    private final UnitTestSlashCommandHandler unitTestSlashCommandHandler;
    private final WorkflowSelectionService workflowSelectionService;
    private final AgentServiceFactory agentServiceFactory;

    public BotWebhookService(AiClientFactory aiClientFactory,
                             GiteaClientFactory giteaClientFactory,
                             PromptService promptService,
                             AgentConfigProperties agentConfig,
                             AgentSessionService agentSessionService,
                             ToolExecutionService toolExecutionService,
                             ToolCatalog toolCatalog,
                             WorkspaceService workspaceService,
                             BotService botService,
                             McpOrchestrationService mcpOrchestrationService,
                             McpToolSelectionService mcpToolSelectionService,
                             BotToolSelectionService botToolSelectionService,
                             PrWorkflowOrchestrator prWorkflowOrchestrator,
                             CodeReviewServiceFactory codeReviewServiceFactory,
                             E2eTestPrCloseHandler e2eTestPrCloseHandler,
                             E2eTestSlashCommandHandler e2eTestSlashCommandHandler,
                             UnitTestSlashCommandHandler unitTestSlashCommandHandler,
                             WorkflowSelectionService workflowSelectionService) {
        this.giteaClientFactory = giteaClientFactory;
        this.agentSessionService = agentSessionService;
        this.botService = botService;
        this.prWorkflowOrchestrator = prWorkflowOrchestrator;
        this.codeReviewServiceFactory = codeReviewServiceFactory;
        this.e2eTestPrCloseHandler = e2eTestPrCloseHandler;
        this.e2eTestSlashCommandHandler = e2eTestSlashCommandHandler;
        this.unitTestSlashCommandHandler = unitTestSlashCommandHandler;
        this.workflowSelectionService = workflowSelectionService;
        this.agentServiceFactory = new AgentServiceFactory(aiClientFactory, giteaClientFactory,
                promptService, agentConfig, agentSessionService, toolExecutionService, toolCatalog,
                workspaceService, mcpOrchestrationService, mcpToolSelectionService, botToolSelectionService);
    }

    /**
     * Reviews a pull request via the {@link PrWorkflowOrchestrator}, which
     * dispatches to the {@link ReviewWorkflow} (and, in M2+, any other
     * workflows enabled for the bot via its {@code WorkflowConfiguration}).
     */
    @Async
    public void reviewPullRequest(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores pull request review event", bot.getName());
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
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores pull request command", bot.getName());
            return;
        }
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        try {
            if (e2eTestSlashCommandHandler.tryHandle(bot, payload)) {
                return;
            }
            if (unitTestSlashCommandHandler.tryHandle(bot, payload)) {
                return;
            }
            if (isWorkflowEnabled(bot, ReviewWorkflow.KEY)) {
                createCodeReviewService(bot).handleBotCommand(payload, null);
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
     * Routes to the agent when an agent session exists for the PR (i.e. the PR was created by the
     * agent and can be continued).  For manually created PRs (no active session), the comment is
     * routed to the code-review handler.
     */
    @Async
    public void handlePrComment(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores pull request comment", bot.getName());
            return;
        }
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

        if (hasAgentSession && bot.isAgentEnabled()) {
            log.debug("[Bot '{}'] Agent session found for PR #{}, routing to agent", bot.getName(), prNumber);
            try {
                createIssueImplementationService(bot).handleIssueComment(payload);
            } catch (Exception e) {
                log.error("[Bot '{}'] Failed to handle PR comment via agent: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
        } else {
            log.debug("[Bot '{}'] No agent session for PR #{}, routing to code-review handler",
                    bot.getName(), prNumber);
            try {
                if (e2eTestSlashCommandHandler.tryHandle(bot, payload)) {
                    return;
                }
                if (unitTestSlashCommandHandler.tryHandle(bot, payload)) {
                    return;
                }
                if (isWorkflowEnabled(bot, ReviewWorkflow.KEY)) {
                    createCodeReviewService(bot).handleBotCommand(payload, null);
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
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores inline review comment", bot.getName());
            return;
        }
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        if (!isWorkflowEnabled(bot, ReviewWorkflow.KEY)) {
            log.debug("[Bot '{}'] Review workflow not enabled — ignoring inline review comment", bot.getName());
            return;
        }
        try {
            createCodeReviewService(bot).handleInlineComment(payload, null);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to handle inline comment: {}", bot.getName(), e.getMessage(), e);
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
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores submitted review", bot.getName());
            return;
        }
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        if (!isWorkflowEnabled(bot, ReviewWorkflow.KEY)) {
            log.debug("[Bot '{}'] Review workflow not enabled — ignoring submitted review", bot.getName());
            return;
        }
        try {
            createCodeReviewService(bot).handleReviewSubmitted(payload, null);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to handle review submitted: {}", bot.getName(), e.getMessage(), e);
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
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores pull request closed event", bot.getName());
            return;
        }
        try {
            createCodeReviewService(bot).handlePrClosed(payload);
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
    }

    /**
     * Handles an issue assigned event (agent feature).
     * Delegates to {@link IssueImplementationService#handleIssueAssigned(WebhookPayload)}.
     */
    @Async
    public void handleIssueAssigned(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        if (bot.getBotType() == BotType.WRITER) {
            try {
                createWriterAgentService(bot).handleIssueAssigned(payload);
            } catch (Exception e) {
                log.error("[Bot '{}'] Failed to handle writer issue assignment: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
            return;
        }
        if (!bot.isAgentEnabled()) {
            log.debug("[Bot '{}'] Agent feature disabled, ignoring issue assignment", bot.getName());
            return;
        }
        try {
            createIssueImplementationService(bot).handleIssueAssigned(payload);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to handle issue assignment: {}", bot.getName(), e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles a comment on an issue (agent follow-up).
     * Delegates to {@link IssueImplementationService#handleIssueComment(WebhookPayload)}.
     */
    @Async
    public void handleIssueComment(Bot bot, WebhookPayload payload) {
        AiAuditContext.setSessionId(auditSessionId(payload));
        if (!isCallerAllowed(bot, payload)) {
            return;
        }
        if (bot.getBotType() == BotType.WRITER) {
            try {
                createWriterAgentService(bot).handleIssueComment(payload);
            } catch (Exception e) {
                log.error("[Bot '{}'] Failed to handle writer issue comment: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
            return;
        }
        if (!bot.isAgentEnabled()) {
            log.debug("[Bot '{}'] Agent feature disabled, ignoring issue comment", bot.getName());
            return;
        }
        try {
            createIssueImplementationService(bot).handleIssueComment(payload);
        } catch (Exception e) {
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
     * Creates a per-bot {@link CodeReviewService} via the shared
     * {@link CodeReviewServiceFactory}. Used by the non-review webhook
     * handlers (bot commands, inline comments, review submissions, PR-closed)
     * that have not yet been extracted into their own {@code PrWorkflow}.
     */
    private CodeReviewService createCodeReviewService(Bot bot) {
        return codeReviewServiceFactory.create(bot);
    }

    private IssueImplementationService createIssueImplementationService(Bot bot) {
        return agentServiceFactory.createIssueImplementationService(bot);
    }

    private WriterAgentService createWriterAgentService(Bot bot) {
        return agentServiceFactory.createWriterAgentService(bot);
    }
}
