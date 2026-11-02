package org.remus.giteabot.prworkflow;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.agentreview.ReviewDraft;
import org.remus.giteabot.prworkflow.agentreview.ReviewRevision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReviewDraftPersistenceTest {
    @Autowired private PrWorkflowRunRepository runs;
    @Autowired private PrWorkflowRunService service;

    @Test
    void snapshotsRoundTripAndRejectStaleRevisionsVersionsAndWritesAfterSealing() {
        var run = new PrWorkflowRun();
        run.setBotId(1L);
        run.setRepoOwner("contract-owner");
        run.setRepoName("draft-contract");
        run.setPrNumber(1L);
        run.setWorkflowKey("agentic-review");
        run.setStartedAt(Instant.now());
        Long id = runs.saveAndFlush(run).getId();
        var revision = new ReviewRevision("a".repeat(40), "b".repeat(40), "sha256:" + "c".repeat(64));
        service.captureReviewRevision(id, revision);
        var draft = new ReviewDraft(1, 1, ReviewDraft.Status.DRAFT, 2, false, "", List.of(),
                List.of(new ReviewDraft.Finding("F1", "blocker", "security", "Missing check", "Concrete evidence", "A.java", 7)), null);
        service.saveReviewDraft(id, revision, 0, draft);
        assertThat(service.loadReviewDraft(id, revision)).isEqualTo(draft);
        assertThatIllegalStateException().isThrownBy(() -> service.saveReviewDraft(id, revision, 0, draft));
        var other = new ReviewRevision(revision.headSha(), "d".repeat(40), revision.diffHash());
        var sealed = new ReviewDraft(1, 2, ReviewDraft.Status.COMPLETE, 2, true, "Review complete", List.of(), draft.findings(), null);
        assertThatIllegalStateException().isThrownBy(() -> service.saveReviewDraft(id, other, 1, sealed));
        service.saveReviewDraft(id, revision, 1, sealed);
        assertThat(service.loadReviewDraft(id, revision)).isEqualTo(sealed);
        var third = new ReviewDraft(1, 3, ReviewDraft.Status.DRAFT, 2, false, "", List.of(), draft.findings(), null);
        assertThatIllegalStateException().isThrownBy(() -> service.saveReviewDraft(id, revision, 2, third));
    }
}
