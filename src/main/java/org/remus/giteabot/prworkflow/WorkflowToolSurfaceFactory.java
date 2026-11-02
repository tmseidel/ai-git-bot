package org.remus.giteabot.prworkflow;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.stereotype.Component;

import org.remus.giteabot.agent.validation.Workspace;
import java.util.Set;

/**
 * Builds the {@link WorkflowToolSurface} a PR-workflow run offers, from the bot's own tool
 * configuration. Mirrors {@code AgentReviewServiceFactory}, which wires the same selection into the
 * agent-review surface: the bot's filtered MCP catalog plus the workflow's tools and the read-only
 * catalogue tools — {@code execute-code} among them — that the bot selects.
 */
@Component
@RequiredArgsConstructor
public class WorkflowToolSurfaceFactory {

    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog toolCatalog;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpToolSelectionService mcpToolSelectionService;
    private final BotToolSelectionService botToolSelectionService;
    private final PythonExecutionService pythonExecution;

    /**
     * @param workflowTools the names the workflow's own executor handles; the read-only catalogue
     *                      tools and {@code execute-code} join only when the bot selects them
     */
    public WorkflowToolSurface create(Bot bot,
                                      RepositoryApiClient repositoryClient,
                                      Set<String> workflowTools,
                                      String owner,
                                      String repo,
                                      Long number,
                                      Workspace workspace) {
        Set<String> botTools = botToolSelectionService.allowedBuiltinTools(bot.getToolConfiguration());
        McpConfiguration mcpConfiguration = bot.getMcpConfiguration();
        McpToolCatalog mcpCatalog = mcpToolSelectionService.filterCatalogForPrompt(mcpConfiguration,
                mcpOrchestrationService.discoverTools(mcpConfiguration));

        Set<String> callable = WorkflowToolSurface.callableTools(workflowTools, botTools);

        AgentToolRouter router = new AgentToolRouter(toolExecutionService, toolCatalog,
                mcpOrchestrationService, mcpConfiguration, mcpCatalog, repositoryClient,
                callable, pythonExecution);

        return new WorkflowToolSurface(workflowTools, toolCatalog, mcpCatalog, callable, router,
                owner, repo, number, workspace);
    }
}
