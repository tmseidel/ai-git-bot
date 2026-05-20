package org.remus.giteabot.prworkflow.deployment;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GitIntegrationService;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.WorkflowRunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CiActionPollerTest {

    private final PrWorkflowRunRepository runRepository = mock(PrWorkflowRunRepository.class);
    private final DeploymentTargetService targetService = mock(DeploymentTargetService.class);
    private final DeploymentCallbackNotifier notifier = mock(DeploymentCallbackNotifier.class);
    private final GitIntegrationService integrationService = mock(GitIntegrationService.class);
    private final GiteaClientFactory clientFactory = mock(GiteaClientFactory.class);
    private final RepositoryApiClient apiClient = mock(RepositoryApiClient.class);

    private final CiActionPoller poller = new CiActionPoller(
            runRepository, targetService, notifier, integrationService, clientFactory);

    private PrWorkflowRun ciRun(long runId, String handleJson) {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(runId);
        run.setBotId(1L);
        run.setRepoOwner("acme");
        run.setRepoName("web");
        run.setPrNumber(7L);
        run.setStartedAt(Instant.now());
        run.setStatus(PrWorkflowRunStatus.WAITING_DEPLOY);
        run.setDeploymentHandleJson(handleJson);
        return run;
    }

    private String handleFor(String runId) {
        return "{\"strategy\":\"CI_ACTION\",\"provider\":\"GITHUB\",\"integrationId\":99,"
                + "\"owner\":\"acme\",\"repo\":\"web\",\"workflowRef\":\"preview.yml\","
                + "\"runId\":\"" + runId + "\",\"previewUrlOutput\":\"preview_url\","
                + "\"pollIntervalSeconds\":5}";
    }

    private void stubIntegration() {
        GitIntegration integration = new GitIntegration();
        integration.setId(99L);
        integration.setProviderType(RepositoryType.GITHUB);
        integration.setUpdatedAt(Instant.now());
        when(integrationService.findById(99L)).thenReturn(Optional.of(integration));
        when(clientFactory.getApiClient(any())).thenReturn(apiClient);
    }

    @Test
    void noWaitingRunsIsNoop() {
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of());
        poller.pollAll();
        verify(notifier, never()).notifyResult(any(), any());
    }

    @Test
    void skipsNonCiActionHandles() {
        PrWorkflowRun other = ciRun(11L, "{\"strategy\":\"WEBHOOK\"}");
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of(other));
        poller.pollAll();
        verify(notifier, never()).notifyResult(any(), any());
        verify(apiClient, never()).getWorkflowRun(any(), any(), any());
    }

    @Test
    void inProgressDoesNotNotify() {
        stubIntegration();
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of(ciRun(42L, handleFor("987"))));
        when(apiClient.getWorkflowRun("acme", "web", "987"))
                .thenReturn(WorkflowRunStatus.IN_PROGRESS);

        poller.pollAll();
        verify(notifier, never()).notifyResult(any(), any());
    }

    @Test
    void successPublishesReadyWithOutputUrl() {
        stubIntegration();
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of(ciRun(42L, handleFor("987"))));
        when(runRepository.findById(42L)).thenReturn(Optional.of(ciRun(42L, handleFor("987"))));
        when(apiClient.getWorkflowRun("acme", "web", "987"))
                .thenReturn(WorkflowRunStatus.COMPLETED_SUCCESS);
        when(apiClient.getWorkflowRunOutputs("acme", "web", "987"))
                .thenReturn(Map.of("preview_url", "https://pr-7.preview.example.com"));

        poller.pollAll();

        ArgumentCaptor<DeploymentCallbackNotifier.CallbackResult> captor =
                ArgumentCaptor.forClass(DeploymentCallbackNotifier.CallbackResult.class);
        verify(notifier).notifyResult(eq(42L), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(DeploymentStatus.READY);
        assertThat(captor.getValue().previewUrl()).isEqualTo("https://pr-7.preview.example.com");
    }

    @Test
    void successWithoutOutputFallsBackToTemplate() {
        stubIntegration();
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of(ciRun(42L, handleFor("987"))));
        when(runRepository.findById(42L)).thenReturn(Optional.of(ciRun(42L, handleFor("987"))));
        when(apiClient.getWorkflowRun("acme", "web", "987"))
                .thenReturn(WorkflowRunStatus.COMPLETED_SUCCESS);
        when(apiClient.getWorkflowRunOutputs("acme", "web", "987")).thenReturn(Map.of());

        DeploymentTarget target = new DeploymentTarget();
        target.setStrategyType(DeploymentStrategyType.CI_ACTION);
        target.setPreviewUrlTemplate("https://pr-{prNumber}.preview.example.com");
        when(targetService.findAll()).thenReturn(List.of(target));

        poller.pollAll();

        ArgumentCaptor<DeploymentCallbackNotifier.CallbackResult> captor =
                ArgumentCaptor.forClass(DeploymentCallbackNotifier.CallbackResult.class);
        verify(notifier).notifyResult(eq(42L), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(DeploymentStatus.READY);
        assertThat(captor.getValue().previewUrl()).isEqualTo("https://pr-7.preview.example.com");
    }

    @Test
    void failurePublishesFailed() {
        stubIntegration();
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of(ciRun(42L, handleFor("987"))));
        when(runRepository.findById(42L)).thenReturn(Optional.of(ciRun(42L, handleFor("987"))));
        when(apiClient.getWorkflowRun("acme", "web", "987"))
                .thenReturn(WorkflowRunStatus.COMPLETED_FAILURE);

        poller.pollAll();

        ArgumentCaptor<DeploymentCallbackNotifier.CallbackResult> captor =
                ArgumentCaptor.forClass(DeploymentCallbackNotifier.CallbackResult.class);
        verify(notifier).notifyResult(eq(42L), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(captor.getValue().errorMessage()).contains("COMPLETED_FAILURE");
    }

    @Test
    void missingIntegrationFailsTheRun() {
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of(ciRun(42L, handleFor("987"))));
        when(runRepository.findById(42L)).thenReturn(Optional.of(ciRun(42L, handleFor("987"))));
        when(integrationService.findById(99L)).thenReturn(Optional.empty());

        poller.pollAll();

        ArgumentCaptor<DeploymentCallbackNotifier.CallbackResult> captor =
                ArgumentCaptor.forClass(DeploymentCallbackNotifier.CallbackResult.class);
        verify(notifier).notifyResult(eq(42L), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(captor.getValue().errorMessage()).contains("integration");
    }

    @Test
    void respectsPerRunPollInterval() {
        stubIntegration();
        // First tick succeeds, second tick should be throttled by the
        // per-run pollIntervalSeconds (=5) and not re-call the client.
        when(runRepository.findByStatusOrderByStartedAtAsc(PrWorkflowRunStatus.WAITING_DEPLOY))
                .thenReturn(List.of(ciRun(42L, handleFor("987"))));
        when(apiClient.getWorkflowRun("acme", "web", "987"))
                .thenReturn(WorkflowRunStatus.IN_PROGRESS);

        poller.pollAll();
        poller.pollAll();

        verify(apiClient, times(1)).getWorkflowRun("acme", "web", "987");
    }
}

