package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationKind;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationRepository;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Persistence round-trip: a {@link Bot} stores its PR workflow configuration
 * and its issue-assigned workflow configuration as two independent references
 * (both pointing at {@code workflow_configurations}, discriminated by kind).
 */
@SpringBootTest
@ActiveProfiles("test")
class BotWorkflowConfigurationPersistenceTest {

    @Autowired private BotRepository botRepository;
    @Autowired private WorkflowConfigurationRepository configurationRepository;
    @Autowired private SystemPromptRepository systemPromptRepository;
    @Autowired private BotToolConfigurationRepository botToolConfigurationRepository;
    @Autowired private AiIntegrationRepository aiIntegrationRepository;
    @Autowired private GitIntegrationRepository gitIntegrationRepository;
    @Autowired private TransactionTemplate tx;

    @Test
    void bot_roundTripsPrAndIssueWorkflowConfigurationsIndependently() {
        String suffix = String.valueOf(System.nanoTime());

        Long botId = tx.execute(status -> {
            WorkflowConfiguration prConfiguration = new WorkflowConfiguration();
            prConfiguration.setName("pr-cfg-" + suffix);
            prConfiguration.setKind(WorkflowConfigurationKind.PR);
            configurationRepository.save(prConfiguration);

            WorkflowConfiguration issueConfiguration = new WorkflowConfiguration();
            issueConfiguration.setName("issue-cfg-" + suffix);
            issueConfiguration.setKind(WorkflowConfigurationKind.ISSUE);
            configurationRepository.save(issueConfiguration);

            SystemPrompt prompt = new SystemPrompt();
            prompt.setName("prompt-" + suffix);
            prompt.setReviewSystemPrompt("r");
            prompt.setReviewAgentSystemPrompt("ra");
            prompt.setIssueAgentSystemPrompt("ia");
            prompt.setWriterAgentSystemPrompt("wa");
            prompt.setE2ePlannerSystemPrompt("ep");
            prompt.setE2eAuthorSystemPrompt("ea");
            prompt.setE2eRunnerSystemPrompt("er");
            prompt.setUnitTestAuthorSystemPrompt("ut");
            prompt.setReadmeSyncSystemPrompt("rs");
            prompt.setI18nCoverageSystemPrompt("i18n");
            systemPromptRepository.save(prompt);

            BotToolConfiguration tools = new BotToolConfiguration();
            tools.setName("tools-" + suffix);
            botToolConfigurationRepository.save(tools);

            AiIntegration ai = new AiIntegration();
            ai.setName("ai-" + suffix);
            ai.setProviderType("OPENAI");
            ai.setApiUrl("http://localhost");
            ai.setModel("test-model");
            aiIntegrationRepository.save(ai);

            GitIntegration git = new GitIntegration();
            git.setName("git-" + suffix);
            git.setUrl("http://localhost");
            git.setToken("token");
            git.setUsername("dual_cfg_bot");
            gitIntegrationRepository.save(git);

            Bot bot = new Bot();
            bot.setName("dual-cfg-bot-" + suffix);
            bot.setSystemPrompt(prompt);
            bot.setToolConfiguration(tools);
            bot.setAiIntegration(ai);
            bot.setGitIntegration(git);
            bot.setWorkflowConfiguration(prConfiguration);
            bot.setIssueWorkflowConfiguration(issueConfiguration);
            return botRepository.save(bot).getId();
        });
        assertNotNull(botId);

        tx.execute(status -> {
            Bot reloaded = botRepository.findByIdWithIntegrations(botId).orElseThrow();
            assertNotNull(reloaded.getWorkflowConfiguration());
            assertNotNull(reloaded.getIssueWorkflowConfiguration());
            assertNotEquals(reloaded.getWorkflowConfiguration().getId(),
                    reloaded.getIssueWorkflowConfiguration().getId());
            assertEquals(WorkflowConfigurationKind.PR, reloaded.getWorkflowConfiguration().getKind());
            assertEquals(WorkflowConfigurationKind.ISSUE,
                    reloaded.getIssueWorkflowConfiguration().getKind());
            assertEquals("pr-cfg-" + suffix, reloaded.getWorkflowConfiguration().getName());
            assertEquals("issue-cfg-" + suffix, reloaded.getIssueWorkflowConfiguration().getName());
            return null;
        });
    }
}
