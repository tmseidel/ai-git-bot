package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.agentreview.ReviewDraft;
import org.remus.giteabot.prworkflow.agentreview.ReviewRevision;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEventRepository;
import org.remus.giteabot.audit.PrAuditEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class ReviewDraftPersistenceTest {
    @Autowired private PrWorkflowRunRepository runs;
    @Autowired private PrWorkflowRunService service;
    @Autowired private PrAuditEventRepository audits;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;
    @MockitoSpyBean private PrAuditEventService auditService;

    @Test
    void snapshotsRoundTripAndRejectStaleRevisionsVersionsAndWritesAfterSealing() {
        Long id = newRun();
        var revision = new ReviewRevision("a".repeat(40), "b".repeat(40), "sha256:" + "c".repeat(64));
        service.captureReviewRevision(id, revision);
        var draft = new ReviewDraft(1, 1, ReviewDraft.Status.DRAFT, 2, false, "", List.of(),
                List.of(new ReviewDraft.Finding("F1", "blocker", "security", "Missing check", "Concrete evidence", "A.java", 7)), null);
        service.saveReviewDraft(id, revision, 0, draft);
        assertThat(service.loadReviewDraft(id, revision)).isEqualTo(draft);
        assertThatIllegalStateException().isThrownBy(() -> service.saveReviewDraft(id, revision, 0, draft));
        var other = new ReviewRevision(revision.headSha(), "d".repeat(40), revision.diffHash());
        var sealed = new ReviewDraft(1, 2, ReviewDraft.Status.COMPLETE, 2, true, "Review complete", List.of(), draft.findings(), null);
        assertThatIllegalStateException().isThrownBy(() -> service.sealReviewShadow(id, other, 1, sealed));
        service.sealReviewShadow(id, revision, 1, sealed);
        assertThat(service.loadReviewDraft(id, revision)).isEqualTo(sealed);
        assertThat(service.getById(id).getStatus()).isEqualTo(PrWorkflowRunStatus.SHADOW);
        assertThat(service.getById(id).getFinishedAt()).isNotNull();
        assertThat(audits.findByRunIdOrderByIdAsc(id)).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(AuditEventType.PR_WORKFLOW_SHADOW_RECORDED);
            assertThat(event.getHash()).hasSize(64);
            assertThat(event.getEventTimestamp()).isEqualTo(service.getById(id).getFinishedAt());
            assertThat(event.getEventPayloadJson()).contains("\"draft_version\":2", "\"run_status\":\"SHADOW\"");
        });
        int changed = transactions.execute(status -> runs.finishIfActive(
                id, PrWorkflowRunStatus.CANCELLED, Instant.now(), "racing cancellation"));
        assertThat(changed).isZero();
        assertThat(service.loadReviewDraft(id, revision)).isEqualTo(sealed);
        service.complete(id, PrWorkflowRunStatus.SUCCESS, "must not publish");
        assertThat(service.getById(id).getStatus()).isEqualTo(PrWorkflowRunStatus.SHADOW);
        var third = new ReviewDraft(1, 3, ReviewDraft.Status.DRAFT, 2, false, "", List.of(), draft.findings(), null);
        assertThatIllegalStateException().isThrownBy(() -> service.saveReviewDraft(id, revision, 2, third));
        assertThat(audits.findByRunIdOrderByIdAsc(id)).hasSize(1);
    }

    @Test
    void requiredAuditFailureRollsBackTheSnapshotStatusAndVersion() {
        Long id = newRun();
        var revision = new ReviewRevision("a".repeat(40), "b".repeat(40), "sha256:" + "c".repeat(64));
        service.captureReviewRevision(id, revision);
        var sealed = new ReviewDraft(1, 1, ReviewDraft.Status.COMPLETE, 1, true, "Checked change", List.of(), List.of(), null);
        doThrow(new IllegalStateException("Audit unavailable")).doCallRealMethod().when(auditService).recordRequired(any());
        assertThatIllegalStateException().isThrownBy(() -> service.sealReviewShadow(id, revision, 0, sealed));
        PrWorkflowRun after = service.getById(id);
        assertThat(after.getStatus()).isEqualTo(PrWorkflowRunStatus.RUNNING);
        assertThat(after.getReviewDraftVersion()).isZero();
        assertThat(after.getReviewDraftJson()).isNull();
        assertThat(after.getFinishedAt()).isNull();
        assertThat(audits.findByRunIdOrderByIdAsc(id)).isEmpty();
        service.sealReviewShadow(id, revision, 0, sealed);
        assertThat(service.loadReviewDraft(id, revision)).isEqualTo(sealed);
        assertThat(audits.findByRunIdOrderByIdAsc(id)).hasSize(1);
    }

    @Test
    void insufficientContextAndItsLocalFailureAuditCommitTogether() {
        Long id = newRun();
        var revision = new ReviewRevision("a".repeat(40), "b".repeat(40), "sha256:" + "c".repeat(64));
        service.captureReviewRevision(id, revision);
        var insufficient = new ReviewDraft(1, 1, ReviewDraft.Status.INSUFFICIENT_CONTEXT,
                1, false, "", List.of(), List.of(), "Missing source");
        service.sealReviewShadow(id, revision, 0, insufficient);
        assertThat(service.getById(id).getStatus()).isEqualTo(PrWorkflowRunStatus.FAILED);
        assertThat(service.loadReviewDraft(id, revision)).isEqualTo(insufficient);
        assertThat(audits.findByRunIdOrderByIdAsc(id)).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(AuditEventType.PR_WORKFLOW_RUN_COMPLETED);
            assertThat(event.getEventPayloadJson()).contains("\"run_status\":\"FAILED\"", "INSUFFICIENT_CONTEXT");
        });
    }

    private Long newRun() {
        var run = new PrWorkflowRun();
        run.setBotId(1L);
        run.setRepoOwner("contract-owner");
        run.setRepoName("draft-contract");
        run.setPrNumber(1L);
        run.setWorkflowKey("agentic-review");
        run.setStartedAt(Instant.now());
        return runs.saveAndFlush(run).getId();
    }
}
