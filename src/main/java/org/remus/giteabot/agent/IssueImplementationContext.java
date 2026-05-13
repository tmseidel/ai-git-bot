package org.remus.giteabot.agent;

import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;

/**
 * Per-bot collaborators and settings needed by the coding issue implementation agent.
 * <p>
 * Keeping these values together prevents the agent service constructor from growing every
 * time another bot-specific dependency is added.
 */
public record IssueImplementationContext(
        RepositoryApiClient repositoryClient,
        AiClient aiClient,
        String issueAgentSystemPrompt,
        String botUsername,
        McpOrchestrationService mcpOrchestrationService,
        McpConfiguration mcpConfiguration,
        McpToolCatalog mcpToolCatalog
) {

    public IssueImplementationContext {
        mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
    }
}

