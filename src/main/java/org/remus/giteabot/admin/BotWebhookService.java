package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.IssueImplementationContext;
import org.remus.giteabot.agent.IssueImplementationService;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.agent.writerimpl.WriterAgentService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.e2e.E2eTestPrCloseHandler;
import org.remus.giteabot.prworkflow.e2e.E2eTestSlashCommandHandler;
import org.remus.giteabot.prworkflow.review.CodeReviewServiceFactory;
import org.remus.giteabot.prworkflow.review.ReviewWorkflow;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    private final AiClientFactory aiClientFactory;
    private final GiteaClientFactory giteaClientFactory;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService agentSessionService;
    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final BotService botService;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpToolSelectionService mcpToolSelectionService;
    private final BotToolSelectionService botToolSelectionService;
    private final PrWorkflowOrchestrator prWorkflowOrchestrator;
    private final CodeReviewServiceFactory codeReviewServiceFactory;
    private final E2eTestPrCloseHandler e2eTestPrCloseHandler;
    private final E2eTestSlashCommandHandler e2eTestSlashCommandHandler;

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
                              E2eTestSlashCommandHandler e2eTestSlashCommandHandler) {
        this.aiClientFactory = aiClientFactory;
        this.giteaClientFactory = giteaClientFactory;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.agentSessionService = agentSessionService;
        this.toolExecutionService = toolExecutionService;
        this.toolCatalog = toolCatalog;
        this.workspaceService = workspaceService;
        this.botService = botService;
        this.mcpOrchestrationService = mcpOrchestrationService;
        this.mcpToolSelectionService = mcpToolSelectionService;
        this.botToolSelectionService = botToolSelectionService;
        this.prWorkflowOrchestrator = prWorkflowOrchestrator;
        this.codeReviewServiceFactory = codeReviewServiceFactory;
        this.e2eTestPrCloseHandler = e2eTestPrCloseHandler;
        this.e2eTestSlashCommandHandler = e2eTestSlashCommandHandler;
    }

    /**
     * Reviews a pull request via the {@link PrWorkflowOrchestrator}, which
     * dispatches to the {@link ReviewWorkflow} (and, in M2+, any other
     * workflows enabled for the bot via its {@code WorkflowConfiguration}).
     */
    @Async
    public void reviewPullRequest(Bot bot, WebhookPayload payload) {
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores pull request review event", bot.getName());
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
     * Delegates to {@link CodeReviewService#handleBotCommand(WebhookPayload, String)}.
     */
    @Async
    public void handleBotCommand(Bot bot, WebhookPayload payload) {
        if (!isPullRequestAuthor(payload)) {
            log.debug("[Bot '{}'] Ignoring pull request command from non-author", bot.getName());
            return;
        }
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores pull request command", bot.getName());
            return;
        }
        try {
            if (e2eTestSlashCommandHandler.tryHandle(bot, payload)) {
                return;
            }
            createCodeReviewService(bot).handleBotCommand(payload, null);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to handle command: {}", bot.getName(), e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles a comment on a PR discussion thread.
     * <p>
     * Routes to the agent when an agent session exists for the PR (i.e. the PR was created by the
     * agent and can be continued).  The agent-session path intentionally skips the PR-author check
     * because the coding agent is the PR author; human follow-up comments must still reach the agent.
     * For manually created PRs (no active session), only the PR author may issue commands.
     */
    @Async
    public void handlePrComment(Bot bot, WebhookPayload payload) {
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores pull request comment", bot.getName());
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
            // Agent-session path: no author restriction – the coding agent created the PR,
            // so any human commenter should be able to continue the implementation workflow.
            log.debug("[Bot '{}'] Agent session found for PR #{}, routing to agent", bot.getName(), prNumber);
            try {
                createIssueImplementationService(bot).handleIssueComment(payload);
            } catch (Exception e) {
                log.error("[Bot '{}'] Failed to handle PR comment via agent: {}", bot.getName(), e.getMessage(), e);
                botService.recordError(bot, e.getMessage());
            }
        } else {
            // Code-review path: only the PR author may issue bot commands.
            if (!isPullRequestAuthor(payload)) {
                log.debug("[Bot '{}'] Ignoring pull request comment from non-author", bot.getName());
                return;
            }
            log.debug("[Bot '{}'] No agent session for PR #{}, routing to code-review handler",
                    bot.getName(), prNumber);
            try {
                if (e2eTestSlashCommandHandler.tryHandle(bot, payload)) {
                    return;
                }
                createCodeReviewService(bot).handleBotCommand(payload, null);
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
        if (!isPullRequestAuthor(payload)) {
            log.debug("[Bot '{}'] Ignoring inline review comment from non-author", bot.getName());
            return;
        }
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores inline review comment", bot.getName());
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
        if (bot.getBotType() == BotType.WRITER) {
            log.debug("[Bot '{}'] Writer bot ignores submitted review", bot.getName());
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

    /**
     * Creates a per-bot {@link IssueImplementationService} using the bot's AI and Git integrations.
     */
    private IssueImplementationService createIssueImplementationService(Bot bot) {
        AiClient aiClient = getAiClient(bot);
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        McpToolCatalog mcpToolCatalog = mcpToolSelectionService.filterCatalogForPrompt(
                bot.getMcpConfiguration(),
                mcpOrchestrationService.discoverTools(bot.getMcpConfiguration()));
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        IssueImplementationContext context = new IssueImplementationContext(
                repoClient,
                aiClient,
                bot.getSystemPrompt().getIssueAgentSystemPrompt(),
                bot.getUsername(),
                mcpOrchestrationService,
                bot.getMcpConfiguration(),
                mcpToolCatalog,
                botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration()));
        return new IssueImplementationService(context, promptService, agentConfig,
                agentSessionService, toolExecutionService, toolCatalog, workspaceService);
    }

    private WriterAgentService createWriterAgentService(Bot bot) {
        AiClient aiClient = getAiClient(bot);
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        McpToolCatalog mcpToolCatalog = mcpToolSelectionService.filterCatalogForPrompt(
                bot.getMcpConfiguration(),
                mcpOrchestrationService.discoverTools(bot.getMcpConfiguration()));
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        return new WriterAgentService(repoClient, aiClient, promptService, agentConfig,
                agentSessionService, toolExecutionService, toolCatalog, workspaceService,
                bot.getSystemPrompt().getWriterAgentSystemPrompt(), bot.getUsername(),
                mcpOrchestrationService, bot.getMcpConfiguration(), mcpToolCatalog,
                botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration()));
    }

    private AiClient getAiClient(Bot bot) {
        return aiClientFactory.getClient(bot.getAiIntegration());
    }
}
