package org.remus.giteabot.prworkflow.unittest;

import lombok.RequiredArgsConstructor;

import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.prworkflow.WorkflowToolSurfaceFactory;
import org.remus.giteabot.prworkflow.unittest.agents.UnitTestAuthorAgent;
import org.remus.giteabot.prworkflow.unittest.runner.UnitTestRunner;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.springframework.stereotype.Component;

/**
 * Factory for per-bot {@link UnitTestService} instances. Resolves the bot's AI
 * and Git clients and wires the shared singleton collaborators, mirroring
 * {@code AgentReviewServiceFactory}.
 */
@Component
@RequiredArgsConstructor
public class UnitTestServiceFactory {

    private final AiClientFactory aiClientFactory;
    private final RepositoryPlatformFactory repositoryPlatformFactory;
    private final WorkspaceService workspaceService;
    private final FrameworkDetector frameworkDetector;
    private final UnitTestAuthorAgent authorAgent;
    private final UnitTestRunner runner;
    private final UnitTestSuiteRepository suiteRepository;
    private final WorkflowToolSurfaceFactory surfaceFactory;

    public UnitTestService create(Bot bot) {
        if (bot.getSystemPrompt() == null) {
            throw new IllegalStateException("Bot must have a system prompt assigned");
        }
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryPlatform platform = repositoryPlatformFactory.getPlatform(bot.getGitIntegration());
        return new UnitTestService(platform, aiClient, bot.getSystemPrompt(),
                workspaceService, frameworkDetector, authorAgent, runner, suiteRepository, surfaceFactory);
    }
}



