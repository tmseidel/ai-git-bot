package org.remus.giteabot.admin.configexchange;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AdminUser;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationKind;
import org.remus.giteabot.prworkflow.config.WorkflowSelection;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionParam;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolSelection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the property set the document exposes. These are the traps a naive
 * entity-to-JSON mapping falls into: back-references, derived accessors and an inverse
 * accessor that would write the same fact twice.
 */
class ConfigExchangeJsonTest {

    @Test
    void serializeDropsBackReferencesAndDerivedAccessors() {
        Bot bot = bot();

        ObjectNode node = ConfigExchangeJson.mapper().valueToTree(bot);

        assertFalse(node.has("webhookPath"), "derived path is not state");
        JsonNode selection = node.path("workflowConfiguration").path("selectedWorkflows").get(0);
        assertFalse(selection.has("configuration"), "back-reference must not be serialized");
        assertEquals("review", selection.path("workflowKey").asString());
        JsonNode tool = node.path("toolConfiguration").path("selectedTools").get(0);
        assertFalse(tool.has("configuration"), "back-reference must not be serialized");
        assertFalse(node.path("gitIntegration").has("managedSshKeyTracking"));
        assertFalse(node.path("aiIntegration").has("enableNativeToolCalling"),
                "the inverse of useLegacyToolCalling must never be written");
    }

    @Test
    void inverseToolCallingAccessorIsIgnoredOnDeserialize() {
        String json = """
                {
                  "name": "b",
                  "useLegacyToolCalling": false,
                  "enableNativeToolCalling": true
                }
                """;

        AiIntegration ai = ConfigExchangeJson.mapper().readValue(json, AiIntegration.class);

        assertFalse(ai.isUseLegacyToolCalling(),
                "enableNativeToolCalling is derived; both keys must not fight over the same fact");
    }

    @Test
    void adminUserNeverExportsThePasswordHash() {
        AdminUser user = new AdminUser();
        user.setUsername("admin");
        user.setPasswordHash("$2a$10$hash");

        ObjectNode node = ConfigExchangeJson.mapper().valueToTree(user);

        assertFalse(node.has("passwordHash"));
        assertEquals("admin", node.path("username").asString());
    }

    @Test
    void cleanRemovesIdentityAndNulls() {
        Bot bot = bot();
        ObjectNode node = ConfigExchangeJson.mapper().valueToTree(bot);

        ConfigExchangeJson.clean(node);

        assertFalse(node.has("id"));
        assertFalse(node.has("createdAt"));
        assertFalse(node.has("mcpConfiguration"), "null values are omitted");
        assertTrue(node.path("workflowConfiguration").has("selectedWorkflows"));
    }

    private static Bot bot() {
        AiIntegration ai = new AiIntegration();
        ai.setId(1L);
        ai.setName("Trial AI");

        GitIntegration git = new GitIntegration();
        git.setId(2L);
        git.setName("Trial Git");
        git.setUsername("ai_bot");

        WorkflowSelection selection = new WorkflowSelection();
        selection.setWorkflowKey("review");
        selection.replaceParams(java.util.Map.of("maxDiffChunks", "4"));

        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(3L);
        configuration.setName("Trial PR");
        configuration.setKind(WorkflowConfigurationKind.PR);
        configuration.setSelectedWorkflows(List.of(selection));

        BotToolSelection tool = new BotToolSelection();
        tool.setToolName("rg");
        tool.setToolKind("CONTEXT");

        BotToolConfiguration tools = new BotToolConfiguration();
        tools.setId(4L);
        tools.setName("Default");
        tools.setSelectedTools(List.of(tool));

        Bot bot = new Bot();
        bot.setId(5L);
        bot.setName("trial");
        bot.setWebhookSecret("11111111-2222-3333-4444-555555555555");
        bot.setAiIntegration(ai);
        bot.setGitIntegration(git);
        bot.setWorkflowConfiguration(configuration);
        bot.setToolConfiguration(tools);
        return bot;
    }
}
