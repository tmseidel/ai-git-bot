package org.remus.giteabot.workflowrun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.AdminUserRepository;
import org.remus.giteabot.admin.SecurityConfig;
import org.remus.giteabot.ai.TriggerKind;
import org.remus.giteabot.aiusage.AiUsageRawPayload;
import org.remus.giteabot.aiusage.AiUsageService;
import org.remus.giteabot.aiusage.InteractionRow;
import org.remus.giteabot.aiusage.ToolTotals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

@WebMvcTest(WorkflowRunController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class WorkflowRunControllerTest {

    private static final Instant STARTED = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowRunQueryService queryService;

    @MockitoBean
    private AiUsageService aiUsageService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void listRendersRunsWithStateWarningsTriggerLinkAndRounds() throws Exception {
        stubPage(List.of(reviewRun()), true);

        mockMvc.perform(get("/workflow-runs").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("workflow-runs/list"))
                .andExpect(content().string(containsString("#42")))
                .andExpect(content().string(containsString("Succeeded")))
                .andExpect(content().string(containsString("Round limit reached")))
                .andExpect(content().string(containsString("Output truncated")))
                .andExpect(content().string(containsString("Agentic PR Review")))
                .andExpect(content().string(containsString("href=\"https://git/owner/repo/pulls/7\"")))
                .andExpect(content().string(containsString("PR #7")))
                .andExpect(content().string(containsString("attempt 2")))
                .andExpect(content().string(containsString("529 overloaded")))
                .andExpect(content().string(containsString("/workflow-runs/interactions/101")))
                .andExpect(content().string(containsString("id=\"interactionModal\"")))
                .andExpect(content().string(containsString("Only runs with failed AI calls")));
    }

    @Test
    void rawPayloadSectionIsOnlyRenderedWhenCaptureIsEnabled() throws Exception {
        when(queryService.interactionDetails(101L)).thenReturn(Optional.of(details()));

        when(queryService.rawPayloadsEnabled()).thenReturn(false);
        mockMvc.perform(get("/workflow-runs/interactions/101").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"interactionRaw\""))));

        when(queryService.rawPayloadsEnabled()).thenReturn(true);
        mockMvc.perform(get("/workflow-runs/interactions/101").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"interactionRaw\"")))
                .andExpect(content().string(containsString("/workflow-runs/interactions/101/raw")));
    }

    @Test
    void failedInteractionShowsItsErrorAndNoTokenUsage() throws Exception {
        org.remus.giteabot.aiusage.InteractionRow row = new org.remus.giteabot.aiusage.InteractionRow(102L, 42L, 2, 1,
                "claude", RunStatus.ERROR, STARTED, 120_000L, null, 0, 0, 0, 0, "Timeout after 120 s");
        when(queryService.interactionDetails(102L)).thenReturn(Optional.of(
                new InteractionDetails(row, "java.net.http.HttpTimeoutException", details().run(), 2, null, List.of())));

        mockMvc.perform(get("/workflow-runs/interactions/102").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Timeout after 120 s")))
                .andExpect(content().string(containsString("java.net.http.HttpTimeoutException")))
                .andExpect(content().string(containsString("Failed after")))
                .andExpect(content().string(containsString("No response received")))
                .andExpect(content().string(containsString("Not reported")));
    }

    /** Still waiting for the provider: the time so far, no end time and no model/tools split. */
    @Test
    void runningInteractionShowsTheTimeSoFar() throws Exception {
        java.time.Instant started = java.time.Instant.now().minusSeconds(90);
        org.remus.giteabot.aiusage.InteractionRow row = new org.remus.giteabot.aiusage.InteractionRow(103L, 42L, 3, 1,
                "claude", RunStatus.RUNNING, started, null, null, 0, 0, 0, 0, null);
        when(queryService.interactionDetails(103L)).thenReturn(Optional.of(
                new InteractionDetails(row, null, details().run(), 3, null, List.of())));

        String html = mockMvc.perform(get("/workflow-runs/interactions/103").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher duration = java.util.regex.Pattern.compile("data-duration-ms=\"(\\d+)\"").matcher(html);
        assertThat(duration.find()).isTrue();
        assertThat(Long.parseLong(duration.group(1))).isBetween(90_000L, 120_000L);
        assertThat(html).contains("so far").doesNotContain("ended").doesNotContain("split-model");
    }

    /** Links are form-encoded: a plain "+" in the query would reach the server as a space. */
    @Test
    void listLinksKeepAPlusInTheTimeZone() throws Exception {
        stubPage(List.of(reviewRun()), false);

        mockMvc.perform(get("/workflow-runs").with(user("admin").roles("ADMIN")).param("tz", "Etc/GMT+1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tz=Etc%2FGMT%2B1")))
                .andExpect(content().string(not(containsString("tz=Etc/GMT+1"))));
    }

    @Test
    void emptyListShowsAHint() throws Exception {
        stubPage(List.of(), false);

        mockMvc.perform(get("/workflow-runs").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No workflow runs recorded yet.")));
    }

    @Test
    void queryParametersBecomeTheFilterAndDatesAreReadInTheBrowsersTimeZone() throws Exception {
        stubPage(List.of(), false);

        mockMvc.perform(get("/workflow-runs").with(user("admin").roles("ADMIN"))
                        .param("runId", "5").param("project", "owner/repo").param("number", "7")
                        .param("type", "agentic-review").param("bot", "3").param("state", "error")
                        .param("warnings", "true").param("failedCalls", "true")
                        .param("from", "2026-11-20T08:30").param("to", "2026-11-21T00:00")
                        .param("tz", "Europe/Berlin").param("page", "2").param("size", "50").param("dir", "asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<WorkflowRunFilter> filter = ArgumentCaptor.forClass(WorkflowRunFilter.class);
        verify(queryService).findRuns(filter.capture(), eq(1), eq(50), eq(true));
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        assertThat(filter.getValue()).isEqualTo(new WorkflowRunFilter(5L, "owner/repo", 7L, "agentic-review", 3L,
                RunStatus.ERROR, true, true, LocalDateTime.parse("2026-11-20T08:30").atZone(berlin).toInstant(),
                LocalDateTime.parse("2026-11-21T00:00").atZone(berlin).toInstant()));
    }

    @Test
    void unknownStateAndTimeZoneAreIgnored() throws Exception {
        stubPage(List.of(), false);

        mockMvc.perform(get("/workflow-runs").with(user("admin").roles("ADMIN"))
                        .param("state", "bogus").param("tz", "Not/AZone").param("from", "2026-11-20T08:30"))
                .andExpect(status().isOk());

        ArgumentCaptor<WorkflowRunFilter> filter = ArgumentCaptor.forClass(WorkflowRunFilter.class);
        verify(queryService).findRuns(filter.capture(), eq(0), eq(20), eq(false));
        assertThat(filter.getValue().state()).isNull();
        assertThat(filter.getValue().startedFrom())
                .isEqualTo(LocalDateTime.parse("2026-11-20T08:30").atZone(ZoneId.systemDefault()).toInstant());
    }

    private static InteractionDetails details() {
        WorkflowRun run = new WorkflowRun();
        run.setId(42L);
        run.setWorkflowType("agentic-review");
        run.setRepoOwner("owner");
        run.setRepoName("repo");
        run.setTriggerKind(TriggerKind.PULL_REQUEST);
        run.setTriggerNumber(7L);
        org.remus.giteabot.aiusage.AiToolCall call = new org.remus.giteabot.aiusage.AiToolCall();
        call.setSequence(1);
        call.setSource("github");
        call.setToolName("get_file_contents");
        call.setDurationMs(300);
        call.setErrorMessage("<script>404</script>");
        org.remus.giteabot.aiusage.InteractionRow row = new org.remus.giteabot.aiusage.InteractionRow(101L, 42L, 1, 2,
                "claude", RunStatus.SUCCEEDED, STARTED, 2_000L, "TOOL_USE", 100, 10, 50, 0, null);
        return new InteractionDetails(row, null, run, 2, "https://git/owner/repo/pulls/7", List.of(call));
    }

    @Test
    void interactionDetailsAreServedAsAnEscapedHtmlFragment() throws Exception {
        when(queryService.interactionDetails(101L)).thenReturn(Optional.of(details()));

        mockMvc.perform(get("/workflow-runs/interactions/101").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("get_file_contents")))
                .andExpect(content().string(containsString("https://git/owner/repo/pulls/7")))
                .andExpect(content().string(containsString("&lt;script&gt;404&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<script>404"))));
    }

    @Test
    void missingInteractionAndDisabledRawPayloadsAre404() throws Exception {
        when(queryService.interactionDetails(9L)).thenReturn(Optional.empty());
        when(queryService.rawPayload(9L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/workflow-runs/interactions/9").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/workflow-runs/interactions/9/raw").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rawPayloadsAreServedWhenAvailable() throws Exception {
        when(queryService.rawPayload(101L)).thenReturn(Optional.of(new AiUsageRawPayload("{\"q\":1}", null)));

        mockMvc.perform(get("/workflow-runs/interactions/101/raw").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawRequest").value("{\"q\":1}"));
    }

    @Test
    void exportStreamsTheFilteredRunsAsADownload() throws Exception {
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("[{\"id\":42}]".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(queryService).export(any(), any());

        mockMvc.perform(get("/workflow-runs/export").with(user("admin").roles("ADMIN")).param("state", "ERROR"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("workflow-runs.json")))
                .andExpect(content().string("[{\"id\":42}]"));

        ArgumentCaptor<WorkflowRunFilter> filter = ArgumentCaptor.forClass(WorkflowRunFilter.class);
        verify(queryService).export(filter.capture(), any());
        assertThat(filter.getValue().state()).isEqualTo(RunStatus.ERROR);
    }

    @Test
    void clearRemovesFinishedRunsAndRedirects() throws Exception {
        mockMvc.perform(post("/workflow-runs/clear").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(redirectedUrl("/workflow-runs"));

        verify(aiUsageService).clearUsage();
    }

    @Test
    void theOldUsagePageIsGone() throws Exception {
        mockMvc.perform(get("/usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    private void stubPage(List<RunView> runs, boolean rawPayloads) {
        Page<RunView> page = new PageImpl<>(runs, PageRequest.of(0, 20), runs.size());
        when(queryService.findRuns(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(page);
        Map<RunStatus, Long> counts = new EnumMap<>(RunStatus.class);
        counts.put(RunStatus.SUCCEEDED, (long) runs.size());
        counts.put(RunStatus.ERROR, 0L);
        counts.put(RunStatus.RUNNING, 0L);
        when(queryService.countByState(any())).thenReturn(counts);
        when(queryService.filterOptions()).thenReturn(new FilterOptions(List.of("owner/repo"),
                List.of("agentic-review"), List.of(new BotOption(1L, "Default Bot"))));
        when(queryService.rawPayloadsEnabled()).thenReturn(rawPayloads);
    }

    private static RunView reviewRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(42L);
        run.setWorkflowType("agentic-review");
        run.setBotId(1L);
        run.setBotName("Default Bot");
        run.setAiIntegrationName("claude");
        run.setGitIntegrationName("gitea");
        run.setRepoOwner("owner");
        run.setRepoName("repo");
        run.setTriggerKind(TriggerKind.PULL_REQUEST);
        run.setTriggerNumber(7L);
        run.setState(RunStatus.SUCCEEDED);
        run.setStartedAt(STARTED);
        run.setEndedAt(STARTED.plusSeconds(30));
        run.setRoundLimitReached(true);
        InteractionRow failed = new InteractionRow(100L, 42L, 1, 1, "claude", RunStatus.ERROR, STARTED, 500L, null,
                0, 0, 0, 0, "529 overloaded");
        InteractionRow retried = new InteractionRow(101L, 42L, 1, 2, "claude", RunStatus.SUCCEEDED,
                STARTED.plusSeconds(1), 2_000L, "MAX_TOKENS", 100, 10, 50, 0, null);
        return RunView.of(run, List.of(
                new AttemptView(failed, ToolTotals.none(100L), false),
                new AttemptView(retried, new ToolTotals(101L, 2, 1, 400), true)),
                "https://git/owner/repo/pulls/7", STARTED.plusSeconds(60));
    }
}
