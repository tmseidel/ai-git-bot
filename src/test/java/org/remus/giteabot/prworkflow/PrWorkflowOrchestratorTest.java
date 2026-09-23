package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.ai.AiRetryContext;
import org.remus.giteabot.audit.PrAuditEventService;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.notification.WorkflowRetryNotices;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrWorkflowOrchestratorTest {

    @Mock private PrWorkflowRunService runService;
    @Mock private PrWorkflowMetrics metrics;
    @Mock private PrAuditEventService auditService;
    @Mock private EventHookPublisher eventHookPublisher;
    @Mock private WorkflowRetryNotices retryNotices;

    private final PrWorkflowRunLockManager lockManager = new PrWorkflowRunLockManager();

    @BeforeEach
    void setUp() {
        lenient().when(runService.isActive(anyLong())).thenReturn(true);
    }

    @AfterEach
    void clearRetryContext() {
        AiRetryContext.clear();
    }

    private PrWorkflowOrchestrator newOrchestrator(PrWorkflow... workflows) {
        PrWorkflowRegistry registry = new PrWorkflowRegistry(List.of(workflows));
        return new PrWorkflowOrchestrator(registry, runService, metrics, lockManager,
                org.mockito.Mockito.mock(WorkflowSelectionService.class), auditService,
                eventHookPublisher, retryNotices);
    }

    /** Makes the mocked notice component install a real notice, as production does. */
    private void stubNoticeInstallation() {
        doAnswer(invocation -> {
            AiRetryContext.install(new AiRetryContext.Notice("test-wf", body -> { }));
            return null;
        }).when(retryNotices).installForPullRequest(any(), any(), any(), any(), any());
    }

    private static WebhookPayload payloadFor(String owner, String repo, long prNumber) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        WebhookPayload.Owner user = new WebhookPayload.Owner();
        user.setLogin(owner);
        repository.setOwner(user);
        repository.setName(repo);
        payload.setRepository(repository);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(prNumber);
        payload.setPullRequest(pr);
        return payload;
    }

    private PrWorkflow successWorkflow() {
        return workflowWithResult(new WorkflowResult(WorkflowResultStatus.SUCCESS, "done"));
    }

    private PrWorkflow skippedWorkflow() {
        return workflowWithResult(new WorkflowResult(WorkflowResultStatus.SKIPPED, "nothing to do"));
    }

    private PrWorkflow throwingWorkflow(RuntimeException ex) {
        return new PrWorkflow() {
            @Override
            public String key() {
                return "test-wf";
            }

            @Override
            public String displayName() {
                return "Test";
            }

            @Override
            public PrWorkflowCategory category() {
                return PrWorkflowCategory.REVIEW;
            }

            @Override
            public WorkflowResult run(PrWorkflowContext ctx) {
                throw ex;
            }
        };
    }

    private PrWorkflow workflowWithResult(WorkflowResult result) {
        return new PrWorkflow() {
            @Override public String key() { return "test-wf"; }
            @Override public String displayName() { return "Test"; }
            @Override public PrWorkflowCategory category() { return PrWorkflowCategory.REVIEW; }
            @Override public WorkflowResult run(PrWorkflowContext ctx) { return result; }
        };
    }

    @Test
    void runDelegatesToWorkflowAndPersistsSuccess() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(1L));
        when(runService.complete(anyLong(), any(), any()))
                .thenReturn(runWithIdAndStatus(1L, PrWorkflowRunStatus.SUCCESS));

        PrWorkflowOrchestrator orchestrator = newOrchestrator(successWorkflow());
        Bot bot = new Bot();
        bot.setId(1L);

        PrWorkflowRun result = orchestrator.run(bot, payloadFor("o", "r", 1), "test-wf");

        assertEquals(PrWorkflowRunStatus.SUCCESS, result.getStatus());
        verify(runService).complete(eq(1L), eq(PrWorkflowRunStatus.SUCCESS), eq("done"));
    }

    @Test
    void runMapsSkippedToSuccessStatus() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(2L));
        when(runService.complete(anyLong(), any(), any()))
                .thenReturn(runWithIdAndStatus(2L, PrWorkflowRunStatus.SUCCESS));

        PrWorkflowOrchestrator orchestrator = newOrchestrator(skippedWorkflow());
        Bot bot = new Bot();
        bot.setId(1L);

        PrWorkflowRun result = orchestrator.run(bot, payloadFor("o", "r", 2), "test-wf");

        assertEquals(PrWorkflowRunStatus.SUCCESS, result.getStatus());
    }

    @Test
    void runCapturesExceptionAsFailedAndRethrows() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(3L));
        stubNoticeInstallation();

        PrWorkflowOrchestrator orchestrator = newOrchestrator(
                throwingWorkflow(new IllegalStateException("boom")));
        Bot bot = new Bot();
        bot.setId(1L);

        assertThrows(IllegalStateException.class, () ->
                orchestrator.run(bot, payloadFor("o", "r", 3), "test-wf"));

        assertNull(AiRetryContext.notice(), "retry-notice context must not leak into the next task");
    }

    @Test
    void runPointsRetryNoticesAtThePullRequestAndClearsThemAfterwards() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(7L));
        when(runService.complete(anyLong(), any(), any()))
                .thenReturn(runWithIdAndStatus(7L, PrWorkflowRunStatus.SUCCESS));
        stubNoticeInstallation();

        AtomicReference<AiRetryContext.Notice> duringRun = new AtomicReference<>();
        PrWorkflow capturing = new PrWorkflow() {
            @Override public String key() { return "test-wf"; }
            @Override public String displayName() { return "Test"; }
            @Override public PrWorkflowCategory category() { return PrWorkflowCategory.REVIEW; }
            @Override public WorkflowResult run(PrWorkflowContext ctx) {
                duringRun.set(AiRetryContext.notice());
                return new WorkflowResult(WorkflowResultStatus.SUCCESS, "done");
            }
        };
        PrWorkflowOrchestrator orchestrator = newOrchestrator(capturing);
        Bot bot = new Bot();
        bot.setId(1L);

        orchestrator.run(bot, payloadFor("o", "r", 7), "test-wf");

        verify(retryNotices).installForPullRequest(bot, "test-wf", "o", "r", 7L);
        assertNotNull(duringRun.get(), "the notice must be installed before the workflow runs");
        assertNull(AiRetryContext.notice());
    }

    @Test
    void runNoticeInstallationFailureStillClearsTheRetryContext() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(11L));
        doAnswer(invocation -> {
            AiRetryContext.install(new AiRetryContext.Notice("test-wf", body -> { }));
            throw new IllegalStateException("notice target blew up");
        }).when(retryNotices).installForPullRequest(any(), any(), any(), any(), any());

        PrWorkflowOrchestrator orchestrator = newOrchestrator(successWorkflow());
        Bot bot = new Bot();
        bot.setId(1L);

        assertThrows(IllegalStateException.class, () ->
                orchestrator.run(bot, payloadFor("o", "r", 11), "test-wf"));

        assertNull(AiRetryContext.notice(), "retry-notice context must not leak into the next task");
    }

    @Test
    void runThrowsOnUnknownWorkflow() {
        PrWorkflowOrchestrator orchestrator = newOrchestrator();
        Bot bot = new Bot();
        bot.setId(1L);
        assertThrows(IllegalArgumentException.class, () ->
                orchestrator.run(bot, payloadFor("o", "r", 1), "nonexistent"));
    }

    @Test
    void runThrowsWhenPayloadMissesRepositoryIdentity() {
        PrWorkflowOrchestrator orchestrator = newOrchestrator(successWorkflow());
        Bot bot = new Bot();
        bot.setId(1L);
        assertThrows(IllegalArgumentException.class, () ->
                orchestrator.run(bot, new WebhookPayload(), "test-wf"));
    }

    @Test
    void runHoldsLockAcrossStartSoConcurrentDeliveriesAreSerialised() throws Exception {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(4L));
        when(runService.complete(anyLong(), any(), any()))
                .thenReturn(runWithIdAndStatus(4L, PrWorkflowRunStatus.SUCCESS));

        AtomicReference<Long> firstRunId = new AtomicReference<>();
        PrWorkflow slowWf = new PrWorkflow() {
            @Override public String key() { return "test-wf"; }
            @Override public String displayName() { return "Test"; }
            @Override public PrWorkflowCategory category() { return PrWorkflowCategory.REVIEW; }
            @Override public WorkflowResult run(PrWorkflowContext ctx) {
                firstRunId.set(ctx.runId());
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new WorkflowResult(WorkflowResultStatus.SUCCESS, "slow");
            }
        };
        PrWorkflowOrchestrator orchestrator = newOrchestrator(slowWf);
        Bot bot = new Bot();
        bot.setId(1L);
        Thread t1 = new Thread(() -> orchestrator.run(bot, payloadFor("o", "r", 5), "test-wf"));
        t1.start();
        Thread.sleep(20);
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(5L));
        when(runService.complete(anyLong(), any(), any()))
                .thenReturn(runWithIdAndStatus(5L, PrWorkflowRunStatus.SUCCESS));
        PrWorkflowRun result2 = orchestrator.run(bot, payloadFor("o", "r", 5), "test-wf");
        t1.join();
        assertNotNull(firstRunId.get());
        assertEquals(PrWorkflowRunStatus.SUCCESS, result2.getStatus());
    }

    @Test
    void runHandlesWorkflowCancelledExceptionAsCancelledNotFailed() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(6L));
        lenient().when(runService.isActive(anyLong())).thenReturn(false);
        when(runService.getById(6L)).thenReturn(runWithIdAndStatus(6L, PrWorkflowRunStatus.CANCELLED));

        PrWorkflowOrchestrator orchestrator = newOrchestrator(
                throwingWorkflow(new WorkflowCancelledException("superseded")));
        Bot bot = new Bot();
        bot.setId(1L);
        PrWorkflowRun result = orchestrator.run(bot, payloadFor("o", "r", 6), "test-wf");

        assertEquals(PrWorkflowRunStatus.CANCELLED, result.getStatus());
    }

    @Test
    void runReportsCancelledMetricWhenSupersededDuringExecution() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(7L));
        when(runService.getById(7L)).thenReturn(runWithIdAndStatus(7L, PrWorkflowRunStatus.CANCELLED));

        PrWorkflowOrchestrator orchestrator = newOrchestrator(
                throwingWorkflow(new WorkflowCancelledException("superseded")));
        Bot bot = new Bot();
        bot.setId(1L);
        PrWorkflowRun result = orchestrator.run(bot, payloadFor("o", "r", 7), "test-wf");

        assertEquals(PrWorkflowRunStatus.CANCELLED, result.getStatus());
    }

    @Test
    void runEmitsReviewCompletedAndFindingPostedAuditEventsForReviewWorkflow() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(8L));
        when(runService.complete(anyLong(), any(), any()))
                .thenReturn(runWithIdAndStatus(8L, PrWorkflowRunStatus.SUCCESS));

        PrWorkflowOrchestrator orchestrator = newOrchestrator(successWorkflow());
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("test-bot");

        orchestrator.run(bot, payloadFor("o", "r", 8), "test-wf");

        ArgumentCaptor<org.remus.giteabot.audit.PrAuditEvent> captor =
                ArgumentCaptor.forClass(org.remus.giteabot.audit.PrAuditEvent.class);
        verify(auditService, org.mockito.Mockito.atLeast(2)).record(captor.capture());
        var emitted = captor.getAllValues().stream()
                .filter(e -> e.getEventType() == org.remus.giteabot.audit.AuditEventType.REVIEW_COMPLETED
                        || e.getEventType() == org.remus.giteabot.audit.AuditEventType.FINDING_POSTED)
                .toList();
        assertEquals(2, emitted.size());
        assertEquals(org.remus.giteabot.audit.AuditEventType.REVIEW_COMPLETED, emitted.get(0).getEventType());
        assertEquals(org.remus.giteabot.audit.AuditEventType.FINDING_POSTED, emitted.get(1).getEventType());
    }

    @Test
    void runPublishesStartedAndCompletedEventHookEvents() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(9L));
        when(runService.complete(anyLong(), any(), any()))
                .thenReturn(runWithIdAndStatus(9L, PrWorkflowRunStatus.SUCCESS));

        PrWorkflowOrchestrator orchestrator = newOrchestrator(successWorkflow());
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("test-bot");

        orchestrator.run(bot, payloadFor("o", "r", 9), "test-wf");

        verify(eventHookPublisher).publish(eq(EventHookEventType.PR_WORKFLOW_STARTED), eq(bot),
                eq("o"), eq("r"), eq(9L), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(data ->
                        "test-wf".equals(data.get("workflowKey"))
                                && Long.valueOf(9L).equals(data.get("runId"))
                                && "webhook".equals(data.get("trigger"))));
        verify(eventHookPublisher).publish(eq(EventHookEventType.PR_WORKFLOW_COMPLETED), eq(bot),
                eq("o"), eq("r"), eq(9L), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(data ->
                        "SUCCESS".equals(data.get("status"))
                                && Long.valueOf(9L).equals(data.get("runId"))
                                && data.get("durationMs") instanceof Long));
    }

    @Test
    void runPublishesFailedEventHookEventOnException() {
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(runWithId(10L));

        PrWorkflowOrchestrator orchestrator = newOrchestrator(throwingWorkflow(new RuntimeException("boom")));
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("test-bot");

        assertThrows(RuntimeException.class,
                () -> orchestrator.run(bot, payloadFor("o", "r", 10), "test-wf"));

        verify(eventHookPublisher).publish(eq(EventHookEventType.PR_WORKFLOW_FAILED), eq(bot),
                eq("o"), eq("r"), eq(10L), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.argThat(data ->
                        "test-wf".equals(data.get("workflowKey"))
                                && Long.valueOf(10L).equals(data.get("runId"))
                                && String.valueOf(data.get("error")).contains("boom")));
    }

    private static PrWorkflowRun runWithId(long id) {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(id);
        run.setBotId(1L);
        run.setRepoOwner("o");
        run.setRepoName("r");
        run.setPrNumber(1L);
        run.setWorkflowKey("test-wf");
        run.setStatus(PrWorkflowRunStatus.RUNNING);
        run.setStartedAt(java.time.Instant.now());
        return run;
    }

    private static PrWorkflowRun runWithIdAndStatus(long id, PrWorkflowRunStatus status) {
        PrWorkflowRun run = runWithId(id);
        run.setStatus(status);
        return run;
    }
}
