package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrWorkflowOrchestratorTest {

    @Mock private PrWorkflowRunService runService;
    @Mock private PrWorkflowMetrics metrics;

    private PrWorkflowOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // Built explicitly in each test once the workflow set is known.
    }

    private PrWorkflowOrchestrator newOrchestrator(PrWorkflow... workflows) {
        PrWorkflowRegistry registry = new PrWorkflowRegistry(List.of(workflows));
        registry.index();
        return new PrWorkflowOrchestrator(registry, runService, metrics);
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

    private static Bot bot(long id, String name) {
        Bot b = new Bot();
        b.setId(id);
        b.setName(name);
        b.setUsername(name);
        return b;
    }

    private static PrWorkflowRun stubRun(long id, PrWorkflowRunStatus status) {
        PrWorkflowRun run = new PrWorkflowRun();
        run.setId(id);
        run.setStatus(status);
        return run;
    }

    @Test
    void runDelegatesToWorkflowAndPersistsSuccess() {
        AtomicReference<PrWorkflowContext> captured = new AtomicReference<>();
        PrWorkflow w = stubWorkflow("review", ctx -> {
            captured.set(ctx);
            return WorkflowResult.success("Reviewed");
        });
        orchestrator = newOrchestrator(w);
        when(runService.start(eq(42L), eq("acme"), eq("web"), eq(7L), eq("review")))
                .thenReturn(stubRun(101L, PrWorkflowRunStatus.RUNNING));
        when(runService.complete(eq(101L), eq(PrWorkflowRunStatus.SUCCESS), eq("Reviewed")))
                .thenReturn(stubRun(101L, PrWorkflowRunStatus.SUCCESS));

        PrWorkflowRun completed = orchestrator.run(bot(42L, "ai_bot"),
                payloadFor("acme", "web", 7L), "review");

        assertEquals(PrWorkflowRunStatus.SUCCESS, completed.getStatus());
        assertNotNull(captured.get());
        assertEquals(101L, captured.get().runId());
        verify(metrics).recordRun(eq("review"), eq(PrWorkflowRunStatus.SUCCESS), any());
    }

    @Test
    void runMapsSkippedToSuccessStatus() {
        PrWorkflow w = stubWorkflow("review",
                ctx -> WorkflowResult.skipped("nothing to do"));
        orchestrator = newOrchestrator(w);
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(stubRun(1L, PrWorkflowRunStatus.RUNNING));
        when(runService.complete(eq(1L), eq(PrWorkflowRunStatus.SUCCESS), eq("nothing to do")))
                .thenReturn(stubRun(1L, PrWorkflowRunStatus.SUCCESS));

        PrWorkflowRun result = orchestrator.run(bot(1L, "b"), payloadFor("o", "r", 1L), "review");

        assertEquals(PrWorkflowRunStatus.SUCCESS, result.getStatus());
    }

    @Test
    void runCapturesExceptionAsFailedAndRethrows() {
        PrWorkflow w = stubWorkflow("review", ctx -> {
            throw new IllegalStateException("boom");
        });
        orchestrator = newOrchestrator(w);
        when(runService.start(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(stubRun(7L, PrWorkflowRunStatus.RUNNING));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.run(bot(1L, "b"), payloadFor("o", "r", 1L), "review"));
        assertEquals("boom", ex.getMessage());

        verify(runService).appendStep(eq(7L), eq("exception"), eq("ERROR"),
                ArgumentCaptor.forClass(String.class).capture());
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(runService).complete(eq(7L), eq(PrWorkflowRunStatus.FAILED), summary.capture());
        assertTrue(summary.getValue().contains("boom"));
        verify(metrics).recordRun(eq("review"), eq(PrWorkflowRunStatus.FAILED), any());
    }

    @Test
    void runThrowsOnUnknownWorkflow() {
        orchestrator = newOrchestrator(stubWorkflow("review",
                ctx -> WorkflowResult.success("ok")));

        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.run(bot(1L, "b"), payloadFor("o", "r", 1L), "does-not-exist"));
    }

    @Test
    void runThrowsWhenPayloadMissesRepositoryIdentity() {
        orchestrator = newOrchestrator(stubWorkflow("review",
                ctx -> WorkflowResult.success("ok")));

        WebhookPayload payload = new WebhookPayload(); // empty
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.run(bot(1L, "b"), payload, "review"));
    }

    private static PrWorkflow stubWorkflow(String key,
                                           java.util.function.Function<PrWorkflowContext, WorkflowResult> body) {
        return new PrWorkflow() {
            @Override public String key() { return key; }
            @Override public String displayName() { return key; }
            @Override public PrWorkflowCategory category() { return PrWorkflowCategory.REVIEW; }
            @Override public WorkflowResult run(PrWorkflowContext context) { return body.apply(context); }
        };
    }
}


