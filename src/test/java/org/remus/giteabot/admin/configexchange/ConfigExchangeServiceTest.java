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
import org.remus.giteabot.eventhook.EventHookEndpoint;
import org.remus.giteabot.eventhook.EventHookEndpointRepository;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationKind;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationRepository;
import org.remus.giteabot.prworkflow.config.WorkflowSelection;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionRepository;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;
import org.remus.giteabot.systemsettings.BotToolSelection;
import org.remus.giteabot.systemsettings.BotToolSelectionRepository;
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
    @Autowired private BotToolConfigurationRepository botToolConfigurationRepository;
    @Autowired private BotToolSelectionRepository botToolSelectionRepository;
    @Autowired private EncryptionService encryptionService;
    @Autowired private EventHookEndpointRepository eventHookEndpointRepository;

    @BeforeEach
    void resetConfigTables() {
        eventHookEndpointRepository.deleteAll();
        botRepository.deleteAll();
        workflowConfigurationRepository.deleteAll();
        aiIntegrationRepository.deleteAll();
        gitIntegrationRepository.deleteAll();
        adminUserRepository.deleteAll();
        // Rows a document may create must not leak into the next test: prompts are
        // rebuilt from the seed, non-default tool configurations are removed.
        systemPromptRepository.deleteAll();
        systemPromptRepository.save(defaultPrompt());
        botToolConfigurationRepository.findAll().stream()
                .filter(configuration -> !configuration.isDefaultEntry())
                .forEach(botToolConfigurationRepository::delete);
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
        assertEquals(List.of("agentic-review"), keys(pr.getId()));
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
        // The PR-kind view of findAll() must not hide the issue configuration: a bot references
        // it, so a document without it cannot be re-imported anywhere else.
        assertTrue(exported.path("workflowConfigurations").toString().contains("\"Trial issues\""),
                "the ISSUE-kind configuration belongs in the export too");

        // Re-importing the export into the instance it came from adds nothing.
        assertEquals(0, configExchangeService.importDocument(exported));
    }

    @Test
    void aDocumentCanDeclareItsOwnSystemPromptAndToolConfiguration() throws IOException {
        ObjectNode root = (ObjectNode) document();
        root.putArray("systemPrompts").addObject()
                .put("name", "Trial prompt")
                .put("reviewSystemPrompt", "custom review")
                .put("reviewAgentSystemPrompt", "custom agent review")
                .put("issueAgentSystemPrompt", "custom issue")
                .put("writerAgentSystemPrompt", "custom writer")
                .put("e2ePlannerSystemPrompt", "custom planner")
                .put("e2eAuthorSystemPrompt", "custom author")
                .put("e2eRunnerSystemPrompt", "custom runner")
                .put("unitTestAuthorSystemPrompt", "custom unit test")
                .put("readmeSyncSystemPrompt", "custom readme")
                .put("i18nCoverageSystemPrompt", "custom i18n");
        ObjectNode tools = root.putArray("botToolConfigurations").addObject();
        tools.put("name", "Trial tools");
        tools.putArray("selectedTools").addObject().put("toolName", "rg").put("toolKind", "CONTEXT");

        ObjectNode bot = (ObjectNode) root.path("bots").get(0);
        bot.putObject("systemPrompt").put("name", "Trial prompt");
        bot.putObject("toolConfiguration").put("name", "Trial tools");

        configExchangeService.importDocument(root);

        Bot saved = botRepository.findByName("trial").orElseThrow();
        assertEquals("Trial prompt", saved.getSystemPrompt().getName());
        assertEquals("custom review", saved.getSystemPrompt().getReviewSystemPrompt());
        assertEquals("Trial tools", saved.getToolConfiguration().getName());
        assertEquals(List.of("rg"), toolNames(saved.getToolConfiguration().getId()));
    }

    @Test
    void aDocumentOverridesTheSeededRowItDeclares() throws IOException {
        ObjectNode root = (ObjectNode) document();
        root.putArray("systemPrompts").addObject()
                .put("name", "Default")
                .put("reviewSystemPrompt", "document review prompt");
        ObjectNode tools = root.putArray("botToolConfigurations").addObject();
        tools.put("name", "Default");
        tools.putArray("selectedTools").addObject()
                .put("toolName", "injected-tool").put("toolKind", "CONTEXT");

        configExchangeService.importDocument(root);

        SystemPrompt prompt = systemPromptRepository.findByDefaultEntryTrue().orElseThrow();
        assertEquals("document review prompt", prompt.getReviewSystemPrompt(),
                "a declared field overrides the seeded value");
        assertEquals("review-agent", prompt.getReviewAgentSystemPrompt(),
                "fields the document does not declare keep their seeded value");
        assertTrue(prompt.isDefaultEntry(), "a document cannot move the default flag");

        Bot saved = botRepository.findByName("trial").orElseThrow();
        assertTrue(saved.getToolConfiguration().isDefaultEntry());
        assertEquals(List.of("injected-tool"), toolNames(saved.getToolConfiguration().getId()),
                "declared selectedTools replace the seeded tool set");
    }

    @Test
    void anExportedEndpointKeepsItsBotScope() throws IOException {
        ObjectNode root = (ObjectNode) document();
        root.putArray("eventHookEndpoints").addObject()
                .put("name", "Ops hook")
                .put("url", "https://ops.example/hook")
                .put("eventTypes", "PR_WORKFLOW_COMPLETED")
                .putObject("bot").put("name", "trial");

        configExchangeService.importDocument(root);

        Long botId = botRepository.findByName("trial").orElseThrow().getId();
        assertEquals(botId, onlyEndpoint().getBotId(),
                "a declared bot reference binds the endpoint to that bot");

        JsonNode exported = configExchangeService.export();
        JsonNode endpoint = exported.path("eventHookEndpoints").get(0);
        assertEquals("trial", endpoint.path("bot").path("name").asString(),
                "the bot travels as a reference, not as its internal id");
        assertTrue(endpoint.path("botId").isMissingNode(),
                "internal ids never reach the document");

        assertEquals(0, configExchangeService.importDocument(exported),
                "re-importing the export creates no second endpoint");
        assertEquals(1, eventHookEndpointRepository.count());
        assertEquals(botId, onlyEndpoint().getBotId(),
                "the scope survives the round trip instead of becoming global — a null botId "
                        + "means the endpoint receives every bot's events");
    }

    @Test
    void anUnscopedEndpointStaysGlobal() throws IOException {
        ObjectNode root = (ObjectNode) document();
        root.putArray("eventHookEndpoints").addObject()
                .put("name", "Ops hook")
                .put("url", "https://ops.example/hook")
                .put("eventTypes", "PR_WORKFLOW_COMPLETED");

        configExchangeService.importDocument(root);

        assertNull(onlyEndpoint().getBotId());
        JsonNode endpoint = configExchangeService.export().path("eventHookEndpoints").get(0);
        assertTrue(endpoint.path("bot").isMissingNode(), "no scope is exported");
    }

    @Test
    void rejectsAnEndpointScopedToAnUndeclaredBot() throws IOException {
        ObjectNode root = (ObjectNode) document();
        root.putArray("eventHookEndpoints").addObject()
                .put("name", "Ops hook")
                .put("url", "https://ops.example/hook")
                .put("eventTypes", "PR_WORKFLOW_COMPLETED")
                .putObject("bot").put("name", "ghost");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configExchangeService.importDocument(root));

        assertTrue(error.getMessage().startsWith("eventHookEndpoints[0]:"),
                () -> "message should name the entry, was: " + error.getMessage());
        assertTrue(error.getMessage().contains("ghost"), error.getMessage());
    }

    private EventHookEndpoint onlyEndpoint() {
        List<EventHookEndpoint> endpoints = eventHookEndpointRepository.findAll();
        assertEquals(1, endpoints.size());
        return endpoints.getFirst();
    }

    private List<String> toolNames(Long configurationId) {
        return botToolSelectionRepository.findByConfigurationId(configurationId).stream()
                .map(BotToolSelection::getToolName)
                .toList();
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
