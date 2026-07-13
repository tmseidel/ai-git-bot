package org.remus.giteabot.prworkflow.i18n;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.repository.RepositoryApiClient;
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
    private final GiteaClientFactory giteaClientFactory;
    private final WorkspaceService workspaceService;
    private final I18nCoverageAgent agent;

    public I18nCoverageService create(Bot bot) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        return new I18nCoverageService(repoClient, aiClient, bot.getSystemPrompt(),
                workspaceService, agent);
    }
}
