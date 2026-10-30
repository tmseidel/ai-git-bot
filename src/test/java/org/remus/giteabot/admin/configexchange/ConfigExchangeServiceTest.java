package org.remus.giteabot.admin.configexchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AdminService;
import org.remus.giteabot.admin.AdminUserRepository;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.AiIntegrationRepository;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GitIntegrationRepository;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationKind;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationRepository;
import org.remus.giteabot.prworkflow.config.WorkflowSelection;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionRepository;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the import path against the fixture the website generator emits.
 *
 * <p>The fixture is the contract between the two repositories: if either side renames a
 * field, this test and {@code website_test2/assets/trial.js} disagree and the build fails
 * here.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfigExchangeServiceTest {

    private static final String FIXTURE = "configexchange/trial-config.json";

    @Autowired private ConfigExchangeService configExchangeService;
    @Autowired private AdminService adminService;
    @Autowired private AdminUserRepository adminUserRepository;
    @Autowired private AiIntegrationRepository aiIntegrationRepository;
    @Autowired private GitIntegrationRepository gitIntegrationRepository;
    @Autowired private WorkflowConfigurationRepository workflowConfigurationRepository;
    @Autowired private WorkflowSelectionRepository workflowSelectionRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private SystemPromptRepository systemPromptRepository;
    @Autowired private EncryptionService encryptionService;

    @BeforeEach
    void resetConfigTables() {
        botRepository.deleteAll();
        workflowConfigurationRepository.deleteAll();
        aiIntegrationRepository.deleteAll();
        gitIntegrationRepository.deleteAll();
        adminUserRepository.deleteAll();
        if (systemPromptRepository.findByDefaultEntryTrue().isEmpty()) {
            systemPromptRepository.deleteAll();
            systemPromptRepository.save(defaultPrompt());
        }
    }

    @Test
    void importsTheGeneratedDocument() throws IOException {
        int created = configExchangeService.importDocument(document());

        assertEquals(6, created, "admin + AI + Git + 2 workflow configurations + bot");
        assertTrue(adminService.findByUsername("trial-admin").isPresent());

        AiIntegration ai = aiIntegrationRepository.findByName("Trial AI").orElseThrow();
        assertEquals("openai", ai.getProviderType());
        assertEquals("deepseek-chat", ai.getModel());
        assertEquals("sk-trial-key", encryptionService.decrypt(ai.getApiKey()),
                "the plaintext key from the document must be encrypted on import");
        assertFalse(ai.isUseLegacyToolCalling(), "native tool calling stays the default");

        GitIntegration git = gitIntegrationRepository.findByName("Trial Git").orElseThrow();
        assertEquals("ai_bot", git.getUsername());
        assertEquals("gitea-token", encryptionService.decrypt(git.getToken()));

        WorkflowConfiguration pr = workflowConfigurationRepository
                .findByName("Trial PR").orElseThrow();
        assertEquals(WorkflowConfigurationKind.PR, pr.getKind());
        assertEquals(List.of("review"), keys(pr.getId()));
        WorkflowConfiguration issues = workflowConfigurationRepository
                .findByName("Trial issues").orElseThrow();
        assertEquals(WorkflowConfigurationKind.ISSUE, issues.getKind());
        assertEquals(List.of("issue-coding"), keys(issues.getId()));

        Bot bot = botRepository.findByName("trial").orElseThrow();
        assertEquals("/api/webhook/11111111-2222-3333-4444-555555555555", bot.getWebhookPath());
        assertTrue(bot.isRunOnPrCreation());
        assertTrue(bot.isRunOnPrUpdate());
        assertTrue(bot.isRunOnIssueCreation());
        assertEquals(pr.getId(), bot.getWorkflowConfiguration().getId());
        assertEquals(issues.getId(), bot.getIssueWorkflowConfiguration().getId());
        assertEquals("0123456789abcdef0123456789abcdef",
                encryptionService.decrypt(bot.getWebhookSigningSecret()));
        assertNotNull(bot.getSystemPrompt(), "the seeded default prompt must be applied");
        assertNotNull(bot.getToolConfiguration(), "BotService assigns the default tool set");
        assertNull(bot.getMcpConfiguration(), "the document never enables MCP");
    }

    @Test
    void secondImportCreatesNothing() throws IOException {
        configExchangeService.importDocument(document());

        assertEquals(0, configExchangeService.importDocument(document()));
        assertEquals(1, botRepository.findAll().size());
    }

    @Test
    void rejectsWorkflowKeyOfTheOtherKindAndNamesTheEntry() throws IOException {
        ObjectNode root = (ObjectNode) document();
        ObjectNode selection = (ObjectNode) root.path("workflowConfigurations").get(1)
                .path("selectedWorkflows").get(0);
        selection.put("workflowKey", "review");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configExchangeService.importDocument(root));

        assertTrue(error.getMessage().startsWith("workflowConfigurations[1]:"),
                () -> "message should name the entry, was: " + error.getMessage());
        assertTrue(error.getMessage().contains("is a pull-request workflow"),
                () -> "message should explain the kind mismatch, was: " + error.getMessage());
    }

    @Test
    void rejectsReferenceToUndeclaredIntegration() throws IOException {
        ObjectNode root = (ObjectNode) document();
        ((ObjectNode) root.path("bots").get(0)).putObject("aiIntegration").put("name", "Missing AI");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configExchangeService.importDocument(root));

        assertTrue(error.getMessage().contains("Missing AI"), error.getMessage());
        assertTrue(error.getMessage().contains("does not declare"), error.getMessage());
    }

    @Test
    void exportedDocumentReImportsWithoutDuplicatingSeeds() throws IOException {
        configExchangeService.importDocument(document());

        JsonNode exported = configExchangeService.export();

        assertEquals("sk-trial-key", exported.path("aiIntegrations").get(0).path("apiKey").asString(),
                "export must decrypt, never ship ciphertext");
        assertEquals("trial-admin",
                exported.path("adminUsers").get(0).path("username").asString());
        assertTrue(exported.path("adminUsers").get(0).path("passwordHash").isMissingNode(),
                "password hashes must never be exported");
        assertEquals("Trial AI",
                exported.path("bots").get(0).path("aiIntegration").path("name").asString(),
                "associations are flattened to name references");

        // Re-importing the export into the instance it came from adds nothing.
        assertEquals(0, configExchangeService.importDocument(exported));
    }

    private JsonNode document() throws IOException {
        String json = new ClassPathResource(FIXTURE).getContentAsString(StandardCharsets.UTF_8);
        return ConfigExchangeJson.mapper().readTree(json);
    }

    private List<String> keys(Long configurationId) {
        return workflowSelectionRepository.findByConfigurationId(configurationId).stream()
                .map(WorkflowSelection::getWorkflowKey)
                .toList();
    }

    private static SystemPrompt defaultPrompt() {
        SystemPrompt prompt = new SystemPrompt();
        prompt.setName("Default");
        prompt.setReviewSystemPrompt("review");
        prompt.setReviewAgentSystemPrompt("review-agent");
        prompt.setIssueAgentSystemPrompt("issue-agent");
        prompt.setWriterAgentSystemPrompt("writer-agent");
        prompt.setE2ePlannerSystemPrompt("e2e-planner");
        prompt.setE2eAuthorSystemPrompt("e2e-author");
        prompt.setE2eRunnerSystemPrompt("e2e-runner");
        prompt.setUnitTestAuthorSystemPrompt("unit-test");
        prompt.setReadmeSyncSystemPrompt("readme-sync");
        prompt.setI18nCoverageSystemPrompt("i18n");
        prompt.setDefaultEntry(true);
        return prompt;
    }
}
