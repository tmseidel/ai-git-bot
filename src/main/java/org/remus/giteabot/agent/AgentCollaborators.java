package org.remus.giteabot.agent;

import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;

/**
 * Shared (bot-independent) singleton collaborators required by the agent
 * services.  Complements {@link IssueImplementationContext}, which carries
 * the per-bot values, so that agent service constructors stay small.
 */
public record AgentCollaborators(
        PromptService promptService,
        AgentConfigProperties agentConfig,
        AgentSessionService sessionService,
        ToolExecutionService toolExecutionService,
        ToolCatalog toolCatalog,
        WorkspaceService workspaceService
) {
}
