package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;

import java.util.Set;

/**
 * Per-bot collaborators and settings needed by the agentic PR-review workflow.
 *
 * <p>Read-only analogue of
 * {@link org.remus.giteabot.agent.IssueImplementationContext}: it bundles the
 * bot's resolved {@link AiClient}, {@link RepositoryApiClient}, MCP wiring,
 * built-in tool whitelist and the operator-edited review-agent system prompt
 * so {@link AgentReviewServiceFactory} can hand a single value to
 * {@link AgentReviewService}.</p>
 *
 * @param allowedBuiltinTools whitelist of built-in tool names enabled for this bot;
 *                            {@code null} disables filtering (legacy/test use).
 */
public record AgentReviewContext(
        RepositoryApiClient repositoryClient,
        AiClient aiClient,
        String reviewAgentSystemPrompt,
        String botUsername,
        McpOrchestrationService mcpOrchestrationService,
        McpConfiguration mcpConfiguration,
        McpToolCatalog mcpToolCatalog,
        Set<String> allowedBuiltinTools
) {

    public AgentReviewContext {
        mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
    }
}

