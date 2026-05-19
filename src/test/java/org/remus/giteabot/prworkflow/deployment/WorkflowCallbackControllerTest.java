package org.remus.giteabot.prworkflow.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunService;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowCallbackControllerTest {

    private PrWorkflowRunService runService;
    private DeploymentCallbackNotifier notifier;
    private WorkflowCallbackController controller;

    @BeforeEach
    void setUp() {
        runService = mock(PrWorkflowRunService.class);
        notifier = mock(DeploymentCallbackNotifier.class);
        controller = new WorkflowCallbackController(runService, notifier);
    }

    private PrWorkflowRun runWith(String secret, PrWorkflowRunStatus status) {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(42L);
        run.setCallbackSecret(secret);
        run.setStatus(status);
        return run;
    }

    @Test
    void unknownRunReturns401() {
        when(runService.getById(999L)).thenThrow(new IllegalArgumentException("nope"));
        ResponseEntity<String> r = controller.callback(999L, "any", null,
                "{\"status\":\"READY\",\"previewUrl\":\"https://x\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongSecretReturns401() {
        when(runService.getById(42L)).thenReturn(runWith("expected", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.callback(42L, "bad", null,
                "{\"status\":\"READY\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void terminalRunReturns409() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.SUCCESS));
        ResponseEntity<String> r = controller.callback(42L, "s", null,
                "{\"status\":\"READY\"}");
        assertThat(r.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void readyCallbackPersistsPreviewUrlAndNotifies() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        when(notifier.notifyResult(eq(42L), any())).thenReturn(true);

        ResponseEntity<String> r = controller.callback(42L, "s", null,
                "{\"status\":\"READY\",\"previewUrl\":\"https://pr-42.preview.io\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The controller must persist the WAITING_DEPLOY → RUNNING transition
        // (incl. the preview URL) BEFORE notifying the in-process queue, so
        // the DB stays consistent even when no orchestrator thread is around.
        verify(runService).resumeFromDeploy(42L, "https://pr-42.preview.io");
        ArgumentCaptor<DeploymentCallbackNotifier.CallbackResult> cap =
                ArgumentCaptor.forClass(DeploymentCallbackNotifier.CallbackResult.class);
        verify(notifier).notifyResult(eq(42L), cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DeploymentStatus.READY);
        assertThat(cap.getValue().previewUrl()).isEqualTo("https://pr-42.preview.io");
    }

    @Test
    void readyCallbackPersistsTransitionEvenWithoutWaitingThread() {
        // Reproduces the multi-instance / post-timeout case described in
        // doc/PR_WORKFLOWS.md: the notifier has no consumer, but the DB
        // must still leave WAITING_DEPLOY behind.
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        when(notifier.notifyResult(eq(42L), any())).thenReturn(false);

        ResponseEntity<String> r = controller.callback(42L, "s", null,
                "{\"status\":\"READY\",\"previewUrl\":\"https://pr-42.preview.io\"}");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(runService).resumeFromDeploy(42L, "https://pr-42.preview.io");
    }

    @Test
    void readyCallbackWithoutPreviewUrlStillResumes() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.callback(42L, "s", null,
                "{\"status\":\"READY\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(runService).resumeFromDeploy(42L, null);
    }

    @Test
    void failedCallbackMarksRunFailed() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.callback(42L, "s", null,
                "{\"status\":\"FAILED\",\"errorMessage\":\"build broke\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(runService).complete(eq(42L), eq(PrWorkflowRunStatus.FAILED),
                org.mockito.ArgumentMatchers.contains("build broke"));
        verify(runService, never()).resumeFromDeploy(any(), any());
    }

    @Test
    void missingStatusReturns400() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.callback(42L, "s", null, "{}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownStatusReturns400() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.callback(42L, "s", null,
                "{\"status\":\"WAT\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidJsonReturns400() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.callback(42L, "s", null, "not-json");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidSignatureReturns401() {
        when(runService.getById(42L)).thenReturn(runWith("topsecret", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.callback(42L, "topsecret",
                "sha256=deadbeef", "{\"status\":\"READY\"}");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validSignatureAccepted() throws Exception {
        when(runService.getById(42L)).thenReturn(runWith("topsecret", PrWorkflowRunStatus.WAITING_DEPLOY));
        String body = "{\"status\":\"READY\",\"previewUrl\":\"https://x\"}";
        String sig = "sha256=" + WebhookTriggerStrategy.hmacSha256Hex("topsecret", body);
        ResponseEntity<String> r = controller.callback(42L, "topsecret", sig, body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logEndpointTruncatesLargeChunks() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        String huge = "x".repeat(10_000);
        ResponseEntity<String> r = controller.log(42L, "s", huge);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<String> chunkCap = ArgumentCaptor.forClass(String.class);
        verify(runService).appendStep(eq(42L), eq("deployment-log"), eq("INFO"), chunkCap.capture());
        assertThat(chunkCap.getValue().length()).isLessThanOrEqualTo(WorkflowCallbackController.MAX_LOG_CHUNK_CHARS);
        assertThat(chunkCap.getValue()).endsWith("...");
    }

    @Test
    void logEndpointUnauthorizedForWrongSecret() {
        when(runService.getById(42L)).thenReturn(runWith("s", PrWorkflowRunStatus.WAITING_DEPLOY));
        ResponseEntity<String> r = controller.log(42L, "bad", "hi");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

