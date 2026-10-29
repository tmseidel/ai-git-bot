package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BotService botService;

    @MockitoBean
    private org.remus.giteabot.aiusage.AiUsageService aiUsageService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @MockitoBean
    private GitUserService gitUserService;

    @Test
    void dashboard_rendersFaviconInsideTheDocumentHead() throws Exception {
        when(botService.findAll()).thenReturn(List.of());

        String html = mockMvc.perform(get("/dashboard").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Thymeleaf does no HTML tree correction: if the page opened <body> before
        // pulling in the layout, the head would land inside the body and browsers
        // would ignore the icon link.
        assertThat(html.indexOf("rel=\"icon\"")).isGreaterThan(html.indexOf("<head"));
        assertThat(html.indexOf("rel=\"icon\"")).isLessThan(html.indexOf("</head>"));
        assertThat(html.indexOf("<head")).isLessThan(html.indexOf("<body"));
    }

    @Test
    void dashboard_showsTheBotsCachedUsername() throws Exception {
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setId(3L);
        gitIntegration.setName("Gitea");
        AiIntegration aiIntegration = new AiIntegration();
        aiIntegration.setName("Claude");
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("Reviewer");
        bot.setGitIntegration(gitIntegration);
        bot.setAiIntegration(aiIntegration);
        when(botService.findAll()).thenReturn(List.of(bot));
        when(gitUserService.findCachedUsernames(List.of(gitIntegration))).thenReturn(Map.of(3L, "ai_bot"));

        mockMvc.perform(get("/dashboard").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ai_bot")));
    }

    @Test
    void dashboard_rendersSharedBrandingImage() throws Exception {
        when(botService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/dashboard").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(content().string(containsString("/images/favicon.png")))
                .andExpect(content().string(containsString("brand-icon")));
    }
}
