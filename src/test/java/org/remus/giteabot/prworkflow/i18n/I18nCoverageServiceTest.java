package org.remus.giteabot.prworkflow.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class I18nCoverageServiceTest {

    private RepositoryApiClient repoClient;
    private WorkspaceService workspaceService;
    private I18nCoverageAgent agent;
    private I18nCoverageService service;

    @BeforeEach
    void setUp() {
        repoClient = mock(RepositoryApiClient.class);
        workspaceService = mock(WorkspaceService.class);
        agent = mock(I18nCoverageAgent.class);
        AiClient aiClient = mock(AiClient.class);
        SystemPrompt systemPrompt = new SystemPrompt();
        service = new I18nCoverageService(repoClient, aiClient, systemPrompt, workspaceService, agent);
    }

    private I18nCoverageService.Request request(WebhookPayload payload, SuiteLifecycleMode mode) {
        PrWorkflowContext ctx = new PrWorkflowContext(new org.remus.giteabot.admin.Bot(),
                payload, 1L, (n, l) -> { }, () -> false);
        return new I18nCoverageService.Request(ctx, List.of("i18n/*.properties", "i18n/*.json"),
                "en", 12, mode, null);
    }

    private WebhookPayload payloadWithHead(String ref) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        repo.setName("my-repo");
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("acme");
        repo.setOwner(owner);
        payload.setRepository(repo);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(42L);
        if (ref != null) {
            WebhookPayload.Head head = new WebhookPayload.Head();
            head.setRef(ref);
            pr.setHead(head);
        }
        payload.setPullRequest(pr);
        return payload;
    }

    @Test
    void missingHeadRef_andApiCannotResolve_skipsWithoutCloning() {
        when(repoClient.getPullRequestDetails("acme", "my-repo", 42L)).thenReturn(Map.of());

        I18nCoverageService.Result result = service.run(
                request(payloadWithHead(null), SuiteLifecycleMode.COMMIT_TO_PR));

        assertThat(result.status()).isEqualTo(I18nCoverageService.Result.Status.SKIPPED);
        verify(workspaceService, never()).prepareWorkspace(
                anyString(), anyString(), anyString(), any(), any(), anyLong());
        verify(repoClient, never()).getDefaultBranch(anyString(), anyString());
    }

    @Test
    void headRefInPayload_isUsedForClone() {
        when(workspaceService.prepareWorkspace(anyString(), anyString(), anyString(), any(), any(), anyLong()))
                .thenReturn(WorkspaceResult.failure("stop here"));

        service.run(request(payloadWithHead("feature/login"), SuiteLifecycleMode.COMMIT_TO_PR));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(workspaceService).prepareWorkspace(
                eq("acme"), eq("my-repo"), branch.capture(), any(), any(), eq(42L));
        assertThat(branch.getValue()).isEqualTo("feature/login");
    }

    @Test
    void noCoverageGaps_succeedsWithoutInvokingAgentOrCommitting(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("i18n"));
        Files.writeString(ws.resolve("i18n/messages_en.properties"), "a=1", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("i18n/messages_de.properties"), "a=1", StandardCharsets.UTF_8);
        when(workspaceService.prepareWorkspace(anyString(), anyString(), anyString(), any(), any(), anyLong()))
                .thenReturn(WorkspaceResult.success(ws));

        I18nCoverageService.Result result = service.run(
                request(payloadWithHead("feature/x"), SuiteLifecycleMode.COMMIT_TO_PR));

        assertThat(result.status()).isEqualTo(I18nCoverageService.Result.Status.SUCCESS);
        verify(agent, never()).generate(any(), any(), anyString(), any(), any(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(workspaceService, never()).commitAndPush(
                any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(workspaceService).cleanupWorkspace(ws);
    }

    @Test
    void emptyIncludePatterns_skips() {
        PrWorkflowContext ctx = new PrWorkflowContext(new org.remus.giteabot.admin.Bot(),
                payloadWithHead("feature/x"), 1L, (n, l) -> { }, () -> false);
        I18nCoverageService.Request req = new I18nCoverageService.Request(
                ctx, List.of(), "en", 12, SuiteLifecycleMode.COMMIT_TO_PR, null);

        I18nCoverageService.Result result = service.run(req);

        assertThat(result.status()).isEqualTo(I18nCoverageService.Result.Status.SKIPPED);
        verify(workspaceService, never()).prepareWorkspace(
                anyString(), anyString(), anyString(), any(), any(), anyLong());
    }

    /**
     * Regression: the kickoff message MUST embed the current on-disk content of
     * both the baseline file and each affected member file. Because
     * {@code i18n-write} overwrites the whole file, the agent needs to see the
     * already-translated keys to preserve them — otherwise it reconstructs the
     * file from the missing-key list alone and drops every existing translation,
     * which looks like it is deleting keys present in the baseline.
     */
    @Test
    void kickoffMessage_embedsBaselineAndMemberFileContent(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("i18n"));
        Files.writeString(ws.resolve("i18n/messages_en.properties"),
                "greeting=Hello\nfarewell=Bye\nnew.key=Added", StandardCharsets.UTF_8);
        // German file already has translations that must survive the overwrite,
        // and is only missing new.key.
        Files.writeString(ws.resolve("i18n/messages_de.properties"),
                "greeting=Hallo\nfarewell=Tschuess", StandardCharsets.UTF_8);

        I18nCoverageDetector.Report report =
                I18nCoverageDetector.detect(ws, List.of("i18n/*.properties"), "en");
        assertThat(report.hasGaps()).isTrue();

        String kickoff = service.buildKickoffMessage(ws,
                request(payloadWithHead("feature/x"), SuiteLifecycleMode.COMMIT_TO_PR),
                "PR title", null, "diff --git a/x b/x", report);

        // The existing German translations must be present in the prompt so the
        // agent keeps them when it rewrites the file.
        assertThat(kickoff).contains("greeting=Hallo");
        assertThat(kickoff).contains("farewell=Tschuess");
        // The baseline content (source of truth for the value to translate) too.
        assertThat(kickoff).contains("new.key=Added");
        // And the missing key is flagged.
        assertThat(kickoff).contains("new.key");
    }
}
