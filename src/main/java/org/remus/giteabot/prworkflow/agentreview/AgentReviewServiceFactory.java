package org.remus.giteabot.prworkflow.agentreview;

import lombok.RequiredArgsConstructor;

import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.stereotype.Component;

/**
 * Factory for per-bot {@link AgentReviewService} instances.
 *
 * <p>Mirrors {@code BotWebhookService.createIssueImplementationService} /
 * {@code createWriterAgentService}: resolves the bot's AI client, repository
 * client, MCP catalog/config and built-in tool whitelist and wires them into a
 * read-only review service. Extracted as its own {@link Component} so the
 * {@link AgentReviewWorkflow} stays free of bean-wiring concerns.</p>
 */
@Component
@RequiredArgsConstructor
public class AgentReviewServiceFactory {

    private final AiClientFactory aiClientFactory;
    private final GiteaClientFactory giteaClientFactory;
    private final McpToolSelectionService mcpToolSelectionService;
    private final McpOrchestrationService mcpOrchestrationService;
    private final BotToolSelectionService botToolSelectionService;
    private final AgentSessionService agentSessionService;
    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog toolCatalog;
    private final WorkspaceService workspaceService;
    private final AgentConfigProperties agentConfig;

    /**
     * Builds a read-only {@link AgentReviewService} for the given bot using its
     * configured AI and Git integrations.
     */
    public AgentReviewService create(Bot bot) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        McpToolCatalog mcpToolCatalog = mcpToolSelectionService.filterCatalogForPrompt(
                bot.getMcpConfiguration(),
                mcpOrchestrationService.discoverTools(bot.getMcpConfiguration()));

        AgentReviewContext context = new AgentReviewContext(
                repoClient,
                aiClient,
                bot.getSystemPrompt().getReviewAgentSystemPrompt(),
                bot.getUsername(),
                mcpOrchestrationService,
                bot.getMcpConfiguration(),
                mcpToolCatalog,
                botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration()));

        return new AgentReviewService(context, agentSessionService, toolExecutionService,
                toolCatalog, workspaceService, agentConfig);
    }
}

