package org.remus.giteabot.aiusage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.remus.giteabot.admin.AdminUserRepository;
import org.remus.giteabot.admin.SecurityConfig;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(UsageController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class UsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiUsageService aiUsageService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void usage_rendersUsageAndErrorSections() throws Exception {
        AiUsageLog usage = new AiUsageLog();
        usage.setId(1L);
        usage.setTimestamp(Instant.parse("2026-06-01T10:15:30Z"));
        usage.setAiIntegrationName("my-openai");
        usage.setSessionId("owner/repo#42");
        usage.setInputTokens(120);
        usage.setOutputTokens(35);

        AiErrorLog error = new AiErrorLog();
        error.setId(2L);
        error.setTimestamp(Instant.parse("2026-06-02T11:00:00Z"));
        error.setAiIntegrationName("my-anthropic");
        error.setSessionId("owner/repo#7");
        error.setErrorMessage("401 Unauthorized");
        error.setStackTrace("org.example.SomeException: 401 Unauthorized");

        when(aiUsageService.findUsage(any(), any(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(usage), PageRequest.of(0, 20), 1));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(error), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("usage/list"))
                .andExpect(content().string(containsString("AI usage")))
                .andExpect(content().string(containsString("my-openai")))
                .andExpect(content().string(containsString("owner/repo#42")))
                .andExpect(content().string(containsString("401 Unauthorized")))
                .andExpect(content().string(containsString("Export as JSON")));
    }

    @Test
    void usage_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/usage"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void clearUsage_clearsEntriesAndRedirects() throws Exception {
        mockMvc.perform(post("/usage/clear").with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/usage"));

        verify(aiUsageService).clearUsage();
    }

    @Test
    void exportErrors_returnsJsonAttachment() throws Exception {
        doAnswer(invocation -> {
            OutputStream os = invocation.getArgument(2);
            os.write((
                    "[{\"timestamp\":\"2026-06-02T11:00:00Z\"," +
                    "\"aiIntegration\":\"my-anthropic\"," +
                    "\"sessionId\":\"owner/repo#7\"," +
                    "\"errorMessage\":\"401 Unauthorized\"," +
                    "\"stackTrace\":\"org.example.SomeException: 401 Unauthorized\"}]"
            ).getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(aiUsageService).exportErrors(any(), any(), any(OutputStream.class));

        mockMvc.perform(get("/usage/errors/export").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("ai-error-log.json")))
                .andExpect(jsonPath("$[0].aiIntegration").value("my-anthropic"))
                .andExpect(jsonPath("$[0].sessionId").value("owner/repo#7"))
                .andExpect(jsonPath("$[0].errorMessage").value("401 Unauthorized"));
    }
}
