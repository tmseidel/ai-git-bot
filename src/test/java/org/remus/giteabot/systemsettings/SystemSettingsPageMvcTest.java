package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.SecurityConfig;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The configuration document must be downloadable at any time, not only from the banner the
 * document-state notice shows — so "General application settings" carries the download.
 *
 * <p>Renders through the real MVC/Thymeleaf stack: a missing message key would leave
 * {@code ??key??} in the page, which the last assertion catches.</p>
 */
@WebMvcTest(SystemSettingsController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class SystemSettingsPageMvcTest {

    @Autowired
    private MockMvc mockMvc;

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
    private org.remus.giteabot.admin.AdminUserRepository adminUserRepository;

    @Test
    void generalSettingsOffersTheConfigurationDownload() throws Exception {
        when(systemPromptService.findAll()).thenReturn(List.of());
        when(mcpConfigurationService.findAll()).thenReturn(List.of());
        when(botToolConfigurationService.findAll()).thenReturn(List.of());
        when(workflowConfigurationService.findAll(org.remus.giteabot.prworkflow.config
                .WorkflowConfigurationKind.PR)).thenReturn(List.of());
        when(workflowConfigurationService.findAll(org.remus.giteabot.prworkflow.config
                .WorkflowConfigurationKind.ISSUE)).thenReturn(List.of());
        when(deploymentTargetService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/system-settings").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"downloadConfiguration\"")))
                .andExpect(content().string(containsString("href=\"/config/export\"")))
                .andExpect(content().string(containsString("Configuration document")))
                .andExpect(content().string(containsString("Download")))
                .andExpect(content().string(not(containsString("??"))));
    }
}
