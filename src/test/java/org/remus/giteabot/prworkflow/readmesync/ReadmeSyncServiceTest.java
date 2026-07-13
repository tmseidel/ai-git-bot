package org.remus.giteabot.prworkflow.readmesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;

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

/**
 * Focuses on head-branch resolution safety: this workflow writes and pushes to
 * the resolved branch, so it must never fall back to the repository default
 * branch when the PR head ref is missing from the webhook payload (notably for
 * {@code issue_comment}-style events behind the {@code @bot regenerate-readme}
 * slash command).
 */
class ReadmeSyncServiceTest {

    private RepositoryApiClient repoClient;
    private WorkspaceService workspaceService;
    private ReadmeSyncAgent agent;
    private ReadmeSyncService service;

    @BeforeEach
    void setUp() {
        repoClient = mock(RepositoryApiClient.class);
        workspaceService = mock(WorkspaceService.class);
        agent = mock(ReadmeSyncAgent.class);
        AiClient aiClient = mock(AiClient.class);
        SystemPrompt systemPrompt = new SystemPrompt();
        service = new ReadmeSyncService(repoClient, aiClient, systemPrompt, workspaceService, agent);

        when(repoClient.getPullRequestDiff(anyString(), anyString(), anyLong()))
                .thenReturn("diff --git a/x b/x\n+change");
    }

    private ReadmeSyncService.Request request(WebhookPayload payload, SuiteLifecycleMode mode) {
        PrWorkflowContext ctx = new PrWorkflowContext(new org.remus.giteabot.admin.Bot(),
                payload, 1L, (n, l) -> { }, () -> false);
        return new ReadmeSyncService.Request(ctx, List.of("README.md"), 12, mode, null);
    }

    /** issue_comment-style payload: repo + PR number present, but no head ref. */
    private WebhookPayload payloadWithoutHeadRef() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        repo.setName("my-repo");
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("acme");
        repo.setOwner(owner);
        payload.setRepository(repo);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(42L);
        payload.setPullRequest(pr); // no head block
        return payload;
    }

    @Test
    void missingHeadRef_andApiCannotResolve_skipsWithoutCommitting() {
        when(repoClient.getPullRequestDetails("acme", "my-repo", 42L)).thenReturn(Map.of());

        ReadmeSyncService.Result result = service.run(
                request(payloadWithoutHeadRef(), SuiteLifecycleMode.COMMIT_TO_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.SKIPPED);
        // The critical guarantee: no clone and no push to any (default) branch.
        verify(workspaceService, never()).prepareWorkspace(
                anyString(), anyString(), anyString(), any(), any(), anyLong());
        verify(workspaceService, never()).commitAndPush(
                any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        // And it must never substitute the default branch.
        verify(repoClient, never()).getDefaultBranch(anyString(), anyString());
    }

    @Test
    void missingHeadRef_butApiResolvesIt_clonesThatBranch() {
        when(repoClient.getPullRequestDetails("acme", "my-repo", 42L))
                .thenReturn(Map.of("head", Map.of("ref", "feature/login")));
        // Fail the workspace prep so the run stops right after resolution — we only
        // assert which branch it tried to clone.
        when(workspaceService.prepareWorkspace(anyString(), anyString(), anyString(), any(), any(), anyLong()))
                .thenReturn(WorkspaceResult.failure("stop here"));

        service.run(request(payloadWithoutHeadRef(), SuiteLifecycleMode.COMMIT_TO_PR));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(workspaceService).prepareWorkspace(
                eq("acme"), eq("my-repo"), branch.capture(), any(), any(), eq(42L));
        assertThat(branch.getValue()).isEqualTo("feature/login");
        verify(repoClient, never()).getDefaultBranch(anyString(), anyString());
    }

    @Test
    void headRefInPayload_isUsedWithoutApiCall() {
        WebhookPayload payload = payloadWithoutHeadRef();
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/from-payload");
        payload.getPullRequest().setHead(head);
        when(workspaceService.prepareWorkspace(anyString(), anyString(), anyString(), any(), any(), anyLong()))
                .thenReturn(WorkspaceResult.failure("stop here"));

        service.run(request(payload, SuiteLifecycleMode.COMMIT_TO_PR));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(workspaceService).prepareWorkspace(
                eq("acme"), eq("my-repo"), branch.capture(), any(), any(), eq(42L));
        assertThat(branch.getValue()).isEqualTo("feature/from-payload");
        verify(repoClient, never()).getPullRequestDetails(anyString(), anyString(), anyLong());
    }
}
