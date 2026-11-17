package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lookup is how a follow-up resolves what a user cites. These tests pin the three answers it
 * gives — the index, one target, and a text search — and the boundaries: only finalized reviews, only
 * active findings, only this pull request.
 */
@SpringBootTest
@ActiveProfiles("test")
class PublishedReviewLookupContractTest {

    @Autowired private PublishedReviewLookup lookup;
    @Autowired private StructuredReviewService reviewService;

    @Test
    void listsThePublishedReviewsWithTheirFindingReferences() {
        String repo = "web-" + UUID.randomUUID();
        String reference = finalizedReview(repo, "Stray title edit", "README.md");

        String listed = lookup.list(1L, 2L, "acme", repo, 1L);

        assertTrue(listed.contains("Review " + reference));
        assertTrue(listed.contains("- " + reference + "-F1 · MEDIUM · Stray title edit"));
        assertTrue(listed.contains("0 blocker, 1 medium, 0 low"));
        assertTrue(listed.contains("O<number>"));
    }

    @Test
    void readsOneTargetInFull() {
        String repo = "web-" + UUID.randomUUID();
        String reference = finalizedReview(repo, "Stray title edit", "README.md");

        String finding = lookup.byReference(1L, 2L, "acme", repo, 1L, reference + "-F1");
        assertTrue(finding.contains(reference + "-F1 · MEDIUM · Stray title edit"));
        assertTrue(finding.contains("Why: The title line gains scratch text."));
        assertTrue(finding.contains("Impact: The README ships broken."));
        assertTrue(finding.contains("Recommendation: Restore the title."));
        assertTrue(finding.contains("At: README.md:1 (HEAD)"));

        String review = lookup.byReference(1L, 2L, "acme", repo, 1L, reference);
        assertTrue(review.contains("Review " + reference));
        assertTrue(review.contains("Assessment " + reference + "-A · FINDINGS_PRESENT"));

        String assessment = lookup.byReference(1L, 2L, "acme", repo, 1L, reference + "-A");
        assertTrue(assessment.contains("Assessment " + reference + "-A · FINDINGS_PRESENT"));
        assertTrue(assessment.contains("Small change with one stray edit."));
    }

    @Test
    void explainsWhatItCannotResolve() {
        String repo = "web-" + UUID.randomUUID();
        String reference = finalizedReview(repo, "Stray title edit", "README.md");

        assertTrue(lookup.byReference(1L, 2L, "acme", repo, 1L, "O999999")
                .contains("No review O999999 is available on this pull request"));
        assertTrue(lookup.byReference(1L, 2L, "acme", repo, 1L, reference + "-F9")
                .contains("is not part of review " + reference));
        assertTrue(lookup.byReference(1L, 2L, "acme", repo, 1L, "nonsense")
                .contains("is not a review reference"));
        assertTrue(lookup.byReference(1L, 2L, "acme", repo, 1L, reference)
                .contains(reference));
    }

    @Test
    void searchesPublishedFindingsByTextAndPath() {
        String repo = "web-" + UUID.randomUUID();
        String reference = finalizedReview(repo, "Stray title edit", "README.md");

        assertTrue(lookup.search(1L, 2L, "acme", repo, 1L, "title edit")
                .contains(reference + "-F1"));
        assertTrue(lookup.search(1L, 2L, "acme", repo, 1L, "readme.md")
                .contains(reference + "-F1"));
        assertTrue(lookup.search(1L, 2L, "acme", repo, 1L, "nothing-matches-this")
                .contains("No published finding matches"));
        assertTrue(lookup.search(1L, 2L, "acme", repo, 1L, "   ")
                .contains("Pass `query`"));
    }

    @Test
    void aWithdrawnFindingIsNotDescribedAsPartOfTheReview() {
        String repo = "web-" + UUID.randomUUID();
        long reviewId = createDraft(repo);
        FindingHandle withdrawn = reviewService.upsertFinding(reviewId, null,
                finding("f-1", "Retracted concern", "README.md"));
        reviewService.withdrawFinding(reviewId, withdrawn.ordinal(), withdrawn.revision(), "unfounded");
        reviewService.upsertFinding(reviewId, null, finding("f-2", "Kept concern", "style.css"));
        String reference = finalize(reviewId);

        assertFalse(lookup.list(1L, 2L, "acme", repo, 1L).contains("Retracted concern"));
        assertTrue(lookup.list(1L, 2L, "acme", repo, 1L).contains("Kept concern"));
        assertTrue(lookup.byReference(1L, 2L, "acme", repo, 1L, reference + "-F1")
                .contains("may have been withdrawn before publication"));
        assertTrue(lookup.byReference(1L, 2L, "acme", repo, 1L, reference + "-F2")
                .contains("Kept concern"));
    }

    @Test
    void draftsAndOtherScopesAreInvisible() {
        String repo = "web-" + UUID.randomUUID();
        long draft = createDraft(repo);
        reviewService.upsertFinding(draft, null, finding("f-1", "Draft concern", "README.md"));

        assertTrue(lookup.list(1L, 2L, "acme", repo, 1L)
                .contains("No agentic review has been published on this pull request yet"));
        assertTrue(lookup.list(1L, 2L, "acme", repo, 2L).contains("No agentic review"));
        assertTrue(lookup.list(1L, 2L, "other-org", repo, 1L).contains("No agentic review"));
        assertTrue(lookup.list(1L, 99L, "acme", repo, 1L).contains("No agentic review"));
    }

    // ------------------------------------------------------------------ fixtures

    private long createDraft(String repo) {
        return reviewService.createDraft(new ReviewDraftRequest(1L, 2L, "acme", repo, null, 1L, null,
                UUID.randomUUID().toString(), null, null)).reviewId();
    }

    private String finalizedReview(String repo, String title, String path) {
        long reviewId = createDraft(repo);
        reviewService.upsertFinding(reviewId, null, finding("f-1", title, path));
        return finalize(reviewId);
    }

    private String finalize(long reviewId) {
        int revision = reviewService.findDraft(reviewId).orElseThrow().draftRevision();
        reviewService.setAssessment(reviewId, revision, new AssessmentDraft(
                "Small change with one stray edit.", ReviewConclusion.FINDINGS_PRESENT,
                "README and the theme switcher.", null));
        int assessed = reviewService.findDraft(reviewId).orElseThrow().draftRevision();
        reviewService.finalizeReview(reviewId, assessed, 1);
        return reviewService.findDraft(reviewId).orElseThrow().outputReference();
    }

    private static FindingDraft finding(String clientKey, String title, String path) {
        return new FindingDraft(clientKey, ReviewSeverity.MEDIUM, "correctness", title,
                "The title line gains scratch text.", "The README ships broken.", "Restore the title.",
                List.of(new LocationDraft(path, ReviewSide.HEAD, 1, 1, "Evidence.")));
    }
}
