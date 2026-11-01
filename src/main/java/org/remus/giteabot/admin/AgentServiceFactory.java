package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.agent.AgentCollaborators;
import org.remus.giteabot.agent.IssueImplementationContext;
import org.remus.giteabot.agent.IssueImplementationService;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.agent.writerimpl.WriterAgentService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.stereotype.Component;

/**
 * Creates the per-bot agent services ({@link IssueImplementationService} and
 * {@link WriterAgentService}) from a {@link Bot}'s AI and Git integrations.
 * <p>
 * Extracted from {@link BotWebhookService} to keep webhook routing free of
 * object-construction concerns and to reduce its direct dependencies.
 * <p>
 * A shared Spring bean: injected by {@link BotWebhookService} (PR-comment
 * agent path) and by the built-in issue workflows
 * ({@code issue-coding} / {@code issue-writer}) which delegate to the
 * services created here.
 */
@Component
@RequiredArgsConstructor
public class AgentServiceFactory {

    private final AiClientFactory aiClientFactory;
    private final GitUserService gitUserService;
    private final RepositoryApiClientFactory repositoryApiClientFactory;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService agentSessionService;
    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpToolSelectionService mcpToolSelectionService;
    private final BotToolSelectionService botToolSelectionService;
    private final PythonExecutionService pythonExecution;

    /**
     * Creates a per-bot {@link IssueImplementationService} using the bot's AI and Git integrations.
     */
    public IssueImplementationService createIssueImplementationService(Bot bot) {
        AiClient aiClient = getAiClient(bot);
        RepositoryApiClient repoClient = repositoryApiClientFactory.getApiClient(bot.getGitIntegration());
        McpToolCatalog mcpToolCatalog = discoverMcpToolCatalog(bot);
        requireSystemPrompt(bot);
        IssueImplementationContext context = new IssueImplementationContext(
                repoClient,
                aiClient,
                bot.getSystemPrompt().getIssueAgentSystemPrompt(),
                gitUserService.getGitUser(bot.getGitIntegration()),
                mcpOrchestrationService,
                bot.getMcpConfiguration(),
                mcpToolCatalog,
                botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration()),
                getContextWindowTokens(bot));
        return new IssueImplementationService(context, collaborators());
    }

    private AgentCollaborators collaborators() {
        return new AgentCollaborators(promptService, agentConfig, agentSessionService,
                toolExecutionService, toolCatalog, workspaceService, pythonExecution);
    }

    /**
     * Creates a per-bot {@link WriterAgentService} using the bot's AI and Git integrations.
     */
    public WriterAgentService createWriterAgentService(Bot bot) {
        AiClient aiClient = getAiClient(bot);
        RepositoryApiClient repoClient = repositoryApiClientFactory.getApiClient(bot.getGitIntegration());
        McpToolCatalog mcpToolCatalog = discoverMcpToolCatalog(bot);
        requireSystemPrompt(bot);
        return new WriterAgentService(repoClient, aiClient, promptService, agentConfig,
                agentSessionService, toolExecutionService, toolCatalog, workspaceService,
                bot.getSystemPrompt().getWriterAgentSystemPrompt(), gitUserService.getGitUser(bot.getGitIntegration()),
                mcpOrchestrationService, bot.getMcpConfiguration(), mcpToolCatalog,
                botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration()),
                getContextWindowTokens(bot), pythonExecution);
    }

    private McpToolCatalog discoverMcpToolCatalog(Bot bot) {
        return mcpToolSelectionService.filterCatalogForPrompt(
                bot.getMcpConfiguration(),
                mcpOrchestrationService.discoverTools(bot.getMcpConfiguration()));
    }

    private static void requireSystemPrompt(Bot bot) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
    }

    private AiClient getAiClient(Bot bot) {
        return aiClientFactory.getClient(bot.getAiIntegration());
    }

    private int getContextWindowTokens(Bot bot) {
        AiIntegration ai = bot.getAiIntegration();
        return ai != null ? ai.getContextWindowTokens() : 200_000;
    }
}
