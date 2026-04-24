package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.IssueImplementationService;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.CodeReviewService;
import org.remus.giteabot.session.SessionService;
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
    private final SessionService sessionService;
    private final AgentConfigProperties agentConfig;
    private final ReviewConfigProperties reviewConfig;
    private final AgentSessionService agentSessionService;
    private final ToolExecutionService toolExecutionService;
    private final WorkspaceService workspaceService;
    private final DiffApplyService diffApplyService;
    private final BotService botService;

    public BotWebhookService(AiClientFactory aiClientFactory,
                             GiteaClientFactory giteaClientFactory,
                             PromptService promptService,
                             SessionService sessionService,
                             AgentConfigProperties agentConfig,
                             ReviewConfigProperties reviewConfig,
                             AgentSessionService agentSessionService,
                             ToolExecutionService toolExecutionService,
                             WorkspaceService workspaceService,
                             DiffApplyService diffApplyService,
                             BotService botService) {
        this.aiClientFactory = aiClientFactory;
        this.giteaClientFactory = giteaClientFactory;
        this.promptService = promptService;
        this.sessionService = sessionService;
        this.agentConfig = agentConfig;
        this.reviewConfig = reviewConfig;
        this.agentSessionService = agentSessionService;
        this.toolExecutionService = toolExecutionService;
        this.workspaceService = workspaceService;
        this.diffApplyService = diffApplyService;
        this.botService = botService;
    }

    /**
     * Reviews a pull request using the bot's specific AI and Git integrations.
     * Delegates to {@link CodeReviewService#reviewPullRequest(WebhookPayload, String)}.
     */
    @Async
    public void reviewPullRequest(Bot bot, WebhookPayload payload) {
        try {
            createCodeReviewService(bot).reviewPullRequest(payload, null);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to review PR: {}", bot.getName(), e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles a bot-mention command in a PR comment.
     * Delegates to {@link CodeReviewService#handleBotCommand(WebhookPayload, String)}.
     */
    @Async
    public void handleBotCommand(Bot bot, WebhookPayload payload) {
        try {
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
     * agent and can be continued), or falls back to the code-review handler for manually created PRs.
     */
    @Async
    public void handlePrComment(Bot bot, WebhookPayload payload) {
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
     */
    public void handlePrClosed(Bot bot, WebhookPayload payload) {
        createCodeReviewService(bot).handlePrClosed(payload);
    }

    /**
     * Handles an issue assigned event (agent feature).
     * Delegates to {@link IssueImplementationService#handleIssueAssigned(WebhookPayload)}.
     */
    @Async
    public void handleIssueAssigned(Bot bot, WebhookPayload payload) {
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

    /**
     * Creates a per-bot {@link CodeReviewService} using the bot's AI and Git integrations.
     */
    private CodeReviewService createCodeReviewService(Bot bot) {
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        return new CodeReviewService(repoClient, aiClient, promptService, sessionService, bot.getUsername(), reviewConfig);
    }

    /**
     * Creates a per-bot {@link IssueImplementationService} using the bot's AI and Git integrations.
     */
    private IssueImplementationService createIssueImplementationService(Bot bot) {
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        return new IssueImplementationService(repoClient, aiClient, promptService, agentConfig,
                agentSessionService, toolExecutionService, workspaceService, diffApplyService);
    }
}
