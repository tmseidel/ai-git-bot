package org.remus.giteabot.prworkflow.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunService;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentOrchestratorTest {

    private DeploymentStrategyRegistry registry;
    private DeploymentCallbackNotifier notifier;
    private PrWorkflowRunService runService;
    private DeploymentTargetService targetService;
    private DeploymentStrategy strategy;
    private DeploymentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        registry = mock(DeploymentStrategyRegistry.class);
        notifier = mock(DeploymentCallbackNotifier.class);
        runService = mock(PrWorkflowRunService.class);
        targetService = mock(DeploymentTargetService.class);
        strategy = mock(DeploymentStrategy.class);
        orchestrator = new DeploymentOrchestrator(
                registry, notifier, runService, targetService, "https://bot.acme.io");
    }

    private PrWorkflowContext ctx(Bot bot) {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(99L);
        run.setRepoOwner("acme");
        run.setRepoName("web");
        run.setPrNumber(7L);
        run.setCallbackSecret("cb");
        when(runService.getById(99L)).thenReturn(run);

        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setSha("abc123");
        head.setRef("feature/x");
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(7L);
        pr.setHead(head);
        WebhookPayload payload = new WebhookPayload();
        payload.setPullRequest(pr);
        return new PrWorkflowContext(bot, payload, 99L,
                (n, s) -> { /* stepAppender no-op */ },
                () -> false);
    }

    private Bot botWithTarget() {
        Bot bot = new Bot();
        bot.setName("review-bot");
        DeploymentTarget assigned = new DeploymentTarget();
        assigned.setId(5L);
        assigned.setStrategyType(DeploymentStrategyType.WEBHOOK);
        bot.setDeploymentTarget(assigned);

        DeploymentTarget decrypted = new DeploymentTarget();
        decrypted.setId(5L);
        decrypted.setStrategyType(DeploymentStrategyType.WEBHOOK);
        decrypted.setConfigJson("{\"webhookUrl\":\"https://ci\"}");
        decrypted.setTimeoutSeconds(30);
        when(targetService.findById(5L)).thenReturn(Optional.of(decrypted));
        when(registry.find(DeploymentStrategyType.WEBHOOK)).thenReturn(Optional.of(strategy));
        return bot;
    }

    @Test
    void rejectsBotWithoutTarget() {
        Bot bot = new Bot();
        bot.setName("x");
        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));
        assertThat(r.status()).isEqualTo(DeploymentStatus.REJECTED);
        verify(runService, never()).markWaitingDeploy(anyLong(), any());
    }

    @Test
    void rejectsWhenTargetWasDeletedAfterBotLoad() {
        Bot bot = botWithTarget();
        when(targetService.findById(5L)).thenReturn(Optional.empty());
        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));
        assertThat(r.status()).isEqualTo(DeploymentStatus.REJECTED);
        assertThat(r.errorMessage()).contains("no longer exists");
        verify(runService, never()).markWaitingDeploy(anyLong(), any());
    }

    @Test
    void readyResultDoesNotFlipThroughWaitingDeploy() {
        Bot bot = botWithTarget();
        when(strategy.trigger(any())).thenReturn(DeploymentResult.ready("https://prev", "{}"));
        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));
        assertThat(r.status()).isEqualTo(DeploymentStatus.READY);
        verify(runService, never()).markWaitingDeploy(anyLong(), any());
        verify(runService).setPreviewUrl(99L, "https://prev");
    }

    @Test
    void rejectedResultLeavesRunInRunning() {
        Bot bot = botWithTarget();
        when(strategy.trigger(any())).thenReturn(DeploymentResult.rejected("config invalid"));
        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));
        assertThat(r.status()).isEqualTo(DeploymentStatus.REJECTED);
        verify(runService, never()).markWaitingDeploy(anyLong(), any());
    }

    @Test
    void failedResultLeavesRunInRunning() {
        Bot bot = botWithTarget();
        when(strategy.trigger(any())).thenReturn(DeploymentResult.failed("boom", "{\"h\":1}"));
        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));
        assertThat(r.status()).isEqualTo(DeploymentStatus.FAILED);
        // Critical: even though the strategy returned a non-null handleJson,
        // the orchestrator must NOT persist WAITING_DEPLOY for a terminal
        // trigger outcome.
        verify(runService, never()).markWaitingDeploy(anyLong(), any());
    }

    @Test
    void pendingResultPersistsWaitingDeployAndAwaitsCallback() throws Exception {
        Bot bot = botWithTarget();
        when(strategy.trigger(any())).thenReturn(DeploymentResult.pending("{\"h\":1}"));
        when(strategy.awaitsCallback()).thenReturn(true);
        when(notifier.awaitResult(eq(99L), anyLong())).thenReturn(
                new DeploymentCallbackNotifier.CallbackResult(
                        DeploymentStatus.READY, "https://prev", null));

        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));

        assertThat(r.status()).isEqualTo(DeploymentStatus.READY);
        ArgumentCaptor<String> handleCap = ArgumentCaptor.forClass(String.class);
        verify(runService).markWaitingDeploy(eq(99L), handleCap.capture());
        assertThat(handleCap.getValue()).isEqualTo("{\"h\":1}");
        verify(runService).resumeFromDeploy(99L, "https://prev");
    }

    @Test
    void pendingResultTimesOutBecomesFailed() throws Exception {
        Bot bot = botWithTarget();
        when(strategy.trigger(any())).thenReturn(DeploymentResult.pending("{\"h\":1}"));
        when(strategy.awaitsCallback()).thenReturn(true);
        when(notifier.awaitResult(eq(99L), anyLong())).thenReturn(null);

        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));

        assertThat(r.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(r.errorMessage()).contains("Timed out");
        verify(runService).markWaitingDeploy(eq(99L), any());
    }

    @Test
    void pendingWithoutAwaitsCallbackReturnsPending() {
        Bot bot = botWithTarget();
        when(strategy.trigger(any())).thenReturn(DeploymentResult.pending("{\"h\":1}"));
        when(strategy.awaitsCallback()).thenReturn(false);
        DeploymentResult r = orchestrator.requestDeployment(ctx(bot));
        assertThat(r.status()).isEqualTo(DeploymentStatus.PENDING);
        verify(runService).markWaitingDeploy(eq(99L), any());
    }
}

