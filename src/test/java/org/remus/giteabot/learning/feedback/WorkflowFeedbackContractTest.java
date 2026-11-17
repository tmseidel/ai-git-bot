package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persistence contract of feedback against the real (H2) database of the test profile: one
 * comment is one record, a correction supersedes instead of overwriting, and a guessed reference
 * outside the caller's scope never resolves.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowFeedbackContractTest {

    @Autowired private FeedbackService feedbackService;
    @Autowired private WorkflowFeedbackRepository feedbackRepository;
    @Autowired private org.remus.giteabot.review.structured.StructuredReviewService reviewService;

    @Test
    void oneCommentProducesOneRecordAndADuplicateDeliveryReplaysIt() {
        String repo = "web-" + UUID.randomUUID();
        long reviewId = finalizedReview(repo);
        FeedbackScope scope = scope(repo);
        FeedbackSource source = source(repo, "777");

        FeedbackService.Recorded first = feedbackService.submit(scope, source,
                FeedbackTarget.output(referenceOf(reviewId)), FeedbackVerdict.USEFUL, "Looks right.",
                "alice");
        FeedbackService.Recorded replay = feedbackService.submit(scope, source,
                FeedbackTarget.output(referenceOf(reviewId)), FeedbackVerdict.USEFUL, "Looks right.",
                "alice");

        assertTrue(replay.duplicate(), "the same comment must not create a second record");
        assertEquals(first.reference(), replay.reference());
        assertEquals(1, feedbackRepository.findAll().stream()
                .filter(record -> record.getReference().equals(first.reference())).count());
    }

    @Test
    void aLaterCommentSupersedesTheActorsEarlierAssessment() {
        String repo = "web-" + UUID.randomUUID();
        long reviewId = finalizedReview(repo);
        FeedbackScope scope = scope(repo);

        FeedbackService.Recorded first = feedbackService.submit(scope, source(repo, "1"),
                FeedbackTarget.finding(referenceOf(reviewId), 1), FeedbackVerdict.INCORRECT,
                "Wrong.", "alice");
        FeedbackService.Recorded second = feedbackService.submit(scope, source(repo, "2"),
                FeedbackTarget.finding(referenceOf(reviewId), 1), FeedbackVerdict.USEFUL,
                "Actually correct.", "alice");

        WorkflowFeedback previous = feedbackRepository.findByReference(first.reference())
                .orElseThrow();
        assertEquals(FeedbackState.SUPERSEDED, previous.getState());
        assertEquals(second.reference(), previous.getSupersededBy());
        assertEquals(FeedbackState.EFFECTIVE,
                feedbackRepository.findByReference(second.reference()).orElseThrow().getState());
    }

    @Test
    void aReferenceOutsideTheCallersScopeIsUnknown() {
        String repo = "web-" + UUID.randomUUID();
        long reviewId = finalizedReview(repo);
        FeedbackScope foreign = new FeedbackScope(1L, 2L, "other-org", repo, 1L);

        assertThrows(FeedbackRejectedException.class, () -> feedbackService.submit(foreign,
                source(repo, "9"), FeedbackTarget.output(referenceOf(reviewId)),
                FeedbackVerdict.USEFUL, "", "alice"));
    }

    @Test
    void onlyTheAuthorMayRetract() {
        String repo = "web-" + UUID.randomUUID();
        long reviewId = finalizedReview(repo);
        FeedbackScope scope = scope(repo);
        FeedbackService.Recorded recorded = feedbackService.submit(scope, source(repo, "3"),
                FeedbackTarget.output(referenceOf(reviewId)), FeedbackVerdict.USEFUL, "", "alice");

        assertThrows(FeedbackRejectedException.class,
                () -> feedbackService.retract(scope, recorded.reference(), "mallory"));
        feedbackService.retract(scope, recorded.reference(), "alice");
        assertEquals(FeedbackState.RETRACTED,
                feedbackRepository.findByReference(recorded.reference()).orElseThrow().getState());
    }

    @Test
    void listingStaysInsideTheRequestedScope() {
        String repo = "web-" + UUID.randomUUID();
        long reviewId = finalizedReview(repo);
        feedbackService.submit(scope(repo), source(repo, "4"),
                FeedbackTarget.output(referenceOf(reviewId)), FeedbackVerdict.IRRELEVANT, "", "alice");

        assertEquals(1, feedbackService.list(scope(repo), true, 20, null).size());
        assertEquals(1, feedbackService.list(scope(repo), false, 20, null).size());
        assertTrue(feedbackService.list(new FeedbackScope(1L, 2L, "other-org", repo, 1L), true, 20,
                null).isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private long finalizedReview(String repo) {
        var handle = reviewService.createDraft(
                new org.remus.giteabot.review.structured.ReviewDraftRequest(1L, 2L, "acme", repo, null,
                        1L, null, UUID.randomUUID().toString(), null, null));
        reviewService.upsertFinding(handle.reviewId(), null,
                new org.remus.giteabot.review.structured.FindingDraft("auth", 
                        org.remus.giteabot.review.structured.ReviewSeverity.MEDIUM, "authorization",
                        "Title", "Explanation.", "Impact.", "Recommendation.",
                        List.of(new org.remus.giteabot.review.structured.LocationDraft(
                                "src/Foo.java", org.remus.giteabot.review.structured.ReviewSide.HEAD,
                                1, 2, "Evidence."))));
        reviewService.setAssessment(handle.reviewId(), 1,
                new org.remus.giteabot.review.structured.AssessmentDraft("Summary.",
                        org.remus.giteabot.review.structured.ReviewConclusion.FINDINGS_PRESENT,
                        "Correctness.", null));
        reviewService.finalizeReview(handle.reviewId(), 2, 1);
        return handle.reviewId();
    }

    private String referenceOf(long reviewId) {
        return reviewService.findDraft(reviewId).orElseThrow().outputReference();
    }

    private static FeedbackScope scope(String repo) {
        return new FeedbackScope(1L, 2L, "acme", repo, 1L);
    }

    private static FeedbackSource source(String repo, String commentId) {
        return new FeedbackSource(1L, 2L, "acme", repo, "repo-1",
                FeedbackSource.SubjectKind.PR, 1L, "PR_COMMENT", commentId);
    }
}
