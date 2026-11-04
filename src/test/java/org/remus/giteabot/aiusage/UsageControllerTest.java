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
import org.remus.giteabot.config.AiUsageProperties;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
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
    private AiUsageProperties usageProperties;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    private static AiUsageRow row(long id, Integer round) {
        return new AiUsageRow(id, Instant.parse("2026-06-01T10:15:30Z"), "my-openai", "owner/repo#42", round,
                120, 35, 0, 0);
    }

    @Test
    void usage_rendersDetailsButtonThatLoadsThePayloadOnDemand() throws Exception {
        when(usageProperties.isRawPayloadsEnabled()).thenReturn(true);
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(row(7, null)), PageRequest.of(0, 20), 1));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-bs-target=\"#rawPayloadModal\"")))
                .andExpect(content().string(containsString("data-raw-url=\"/usage/7/raw\"")))
                .andExpect(content().string(containsString("id=\"rawPayloadModal\"")));
        // the page only carries rows: the service is never asked for payloads while rendering
        verify(aiUsageService, never()).findRawPayload(anyLong());
    }

    @Test
    void rawPayload_returnsTheEntryPayloadsAsJson() throws Exception {
        when(aiUsageService.findRawPayload(7L))
                .thenReturn(Optional.of(new AiUsageRawPayload("{\"model\":\"gpt-4o\"}", null)));

        mockMvc.perform(get("/usage/7/raw").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawRequest").value("{\"model\":\"gpt-4o\"}"))
                .andExpect(jsonPath("$.rawResponse").doesNotExist());
    }

    @Test
    void rawPayload_isNotFoundForMissingEntriesOrDisabledCapture() throws Exception {
        when(aiUsageService.findRawPayload(anyLong())).thenReturn(Optional.empty());

        mockMvc.perform(get("/usage/8/raw").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rawPayload_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/usage/7/raw"))
                .andExpect(status().is3xxRedirection());
        verify(aiUsageService, never()).findRawPayload(anyLong());
    }

    @Test
    void usage_hidesDetailsColumnWhenRawPayloadsDisabled() throws Exception {
        when(usageProperties.isRawPayloadsEnabled()).thenReturn(false);
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(row(7, null)), PageRequest.of(0, 20), 1));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AI usage")))
                .andExpect(content().string(containsString("my-openai")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Raw LLM payloads"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("data-raw-url="))));
    }

    @Test
    void usage_rendersUsageAndErrorSections() throws Exception {
        AiUsageRow usage = row(1, null);

        AiErrorLog error = new AiErrorLog();
        error.setId(2L);
        error.setTimestamp(Instant.parse("2026-06-02T11:00:00Z"));
        error.setAiIntegrationName("my-anthropic");
        error.setSessionId("owner/repo#7");
        error.setErrorMessage("401 Unauthorized");
        error.setStackTrace("org.example.SomeException: 401 Unauthorized");

        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(usage), PageRequest.of(0, 20), 1));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
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
    void usage_acceptsPageSizeParameters() throws Exception {
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/usage")
                        .param("usageSize", "100")
                        .param("errorSize", "50")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("usage/list"));

        verify(aiUsageService).findUsage(any(), any(), anyInt(), eq(100), anyString(), anyBoolean());
        verify(aiUsageService).findErrors(any(), any(), anyInt(), eq(50), anyString(), anyBoolean());
    }

    @Test
    void usage_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/usage"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void usage_rendersAgentLoopRoundColumn() throws Exception {
        AiUsageRow entry = row(3, 4);

        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Round")))
                .andExpect(content().string(containsString(">4</td>")));
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

    @Test
    void usage_rendersPaginationControlsForMultiPageList() throws Exception {
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(4, 20), 100));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"AI usage pagination\"")))
                .andExpect(content().string(containsString(">«<")))
                .andExpect(content().string(containsString(">»<")))
                .andExpect(content().string(containsString(">3</a>")))
                .andExpect(content().string(containsString(">4</a>")))
                .andExpect(content().string(containsString(">5</a>")))
                .andExpect(content().string(containsString("id=\"usageJumpTo\"")))
                .andExpect(content().string(containsString("id=\"usagePageSize\"")));
    }

    @Test
    void usage_jumpToPage_convertsOneBasedToZeroBasedIndex() throws Exception {
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 100));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        // Jump form submits a 1-based page number; the service must receive the
        // zero-based index (page 3 -> index 2).
        mockMvc.perform(get("/usage").param("usagePage", "3").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(aiUsageService).findUsage(any(), any(), eq(2), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void usage_firstPage_convertsToZeroBasedIndex() throws Exception {
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 100));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        // Entering page 1 must show the first page (index 0), not the second.
        mockMvc.perform(get("/usage").param("usagePage", "1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(aiUsageService).findUsage(any(), any(), eq(0), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void usage_defaultPage_isFirstPage() throws Exception {
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 100));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        // No page parameter at all: default is 1 (first page) -> index 0.
        mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(aiUsageService).findUsage(any(), any(), eq(0), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void usage_errorJumpToPage_convertsOneBasedToZeroBasedIndex() throws Exception {
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 100));

        // Jump in the error table: page 3 -> zero-based index 2.
        mockMvc.perform(get("/usage").param("errorPage", "3").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(aiUsageService).findErrors(any(), any(), eq(2), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void usage_rowsPerPageForm_resetsPageToOneAndKeepsOtherTablesPage() throws Exception {
        // Usage on page 5 (index 4) of 5 pages (81 rows), errors on page 3 (index 2) of 3 pages (60 rows).
        when(aiUsageService.findUsage(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(4, 20), 81));
        when(aiUsageService.findErrors(any(), any(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 60));

        String html = mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The usage rows-per-page form must submit page 1 (not the current page 5): page 5 may
        // no longer exist at a larger page size (81 rows at 100/page is a single page).
        // The other table's page is preserved (error page 3), and vice versa (usage page 5).
        assertTrue(html.contains("name=\"usagePage\" value=\"1\""));
        assertTrue(html.contains("name=\"errorPage\" value=\"1\""));
        assertTrue(html.contains("name=\"errorPage\" value=\"3\""));
        assertTrue(html.contains("name=\"usagePage\" value=\"5\""));
    }
}
