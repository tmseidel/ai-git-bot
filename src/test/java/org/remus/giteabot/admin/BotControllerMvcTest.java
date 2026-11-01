package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
import org.remus.giteabot.systemsettings.BotToolConfigurationService;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpConfigurationService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.remus.giteabot.systemsettings.SystemPromptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the bot edit form through the real MVC/Thymeleaf stack.
 *
 * <p>Regression guard for the operator-reported 404: the issue-assigned
 * workflow selector's Details button must fetch the ISSUE-kind
 * workflow-configuration endpoint
 * ({@code /system-settings/issue-workflow-configurations/{id}/selected-workflows}).
 * It previously reused the PR-kind base URL, and the PR controller answers
 * 404 for an issue configuration (and for ids that do not exist over there).</p>
 */
@WebMvcTest(BotController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class BotControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BotService botService;

    @MockitoBean
    private AiIntegrationService aiIntegrationService;

    @MockitoBean
    private GitIntegrationService gitIntegrationService;

    @MockitoBean
    private SystemPromptService systemPromptService;

    @MockitoBean
    private McpConfigurationService mcpConfigurationService;

    @MockitoBean
    private McpToolSelectionService mcpToolSelectionService;

    @MockitoBean
    private BotToolConfigurationService botToolConfigurationService;

    @MockitoBean
    private BotToolSelectionService botToolSelectionService;

    @MockitoBean
    private WorkflowConfigurationService workflowConfigurationService;

    @MockitoBean
    private DeploymentTargetService deploymentTargetService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @MockitoBean
    private GitUserService gitUserService;

    @Test
    void newForm_issueWorkflowDetailsButtonTargetsTheIssueKindEndpoint() throws Exception {
        mockMvc.perform(get("/bots/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "issueWorkflowConfigurationBaseUrl = \"\\/system-settings\\/issue-workflow-configurations\\/\"")))
                .andExpect(content().string(containsString(
                        "fetch(issueWorkflowConfigurationBaseUrl + encodeURIComponent(selectedIssueWorkflowConfigId)"
                                + " + '/selected-workflows')")))
                // the PR selector keeps using the PR-kind endpoint
                .andExpect(content().string(containsString(
                        "fetch(workflowConfigurationBaseUrl + encodeURIComponent(selectedWorkflowConfigId)"
                                + " + '/selected-workflows')")));
    }
}
