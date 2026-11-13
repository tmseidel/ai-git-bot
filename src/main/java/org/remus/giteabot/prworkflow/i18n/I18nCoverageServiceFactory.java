package org.remus.giteabot.prworkflow.i18n;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.prworkflow.WorkflowToolSurfaceFactory;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.springframework.stereotype.Component;

/**
 * Factory for per-bot {@link I18nCoverageService} instances. Resolves the bot's
 * AI and Git clients and wires the shared singleton collaborators, mirroring
 * {@code ReadmeSyncServiceFactory}.
 */
@Component
@RequiredArgsConstructor
public class I18nCoverageServiceFactory {

    private final AiClientFactory aiClientFactory;
    private final RepositoryPlatformFactory repositoryPlatformFactory;
    private final WorkspaceService workspaceService;
    private final I18nCoverageAgent agent;
    private final WorkflowToolSurfaceFactory surfaceFactory;

    public I18nCoverageService create(Bot bot) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryPlatform platform = repositoryPlatformFactory.getPlatform(bot.getGitIntegration());
        return new I18nCoverageService(platform, aiClient, bot.getSystemPrompt(),
                workspaceService, agent, surfaceFactory);
    }
}
