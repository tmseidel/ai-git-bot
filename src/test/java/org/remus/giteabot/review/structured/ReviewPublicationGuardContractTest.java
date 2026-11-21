package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery guard of {@link ReviewPublicationService#start} against the real (H2) database of the
 * test profile: an earlier delivery that may have reached the pull request blocks a new one until a
 * person confirms it did not, and a published one always blocks.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewPublicationGuardContractTest {

    @Autowired private ReviewPublicationService publicationService;
    @Autowired private ReviewPublicationRepository publicationRepository;
    @Autowired private StructuredReviewService reviewService;

    @Test
    void aFailedOrSuppressedDeliveryDoesNotBlockARetry() {
        String reference = finalizedReview();
        ReviewPublicationService.Start first = start(reference, false);
        publicationService.finish(first.publication().getId(), ReviewPublicationState.FAILED, null);
        ReviewPublicationService.Start second = start(reference, false);
        publicationService.finish(second.publication().getId(), ReviewPublicationState.SUPPRESSED, null);

        ReviewPublicationService.Start third = start(reference, false);

        assertTrue(third.isStarted());
        assertEquals(3, third.publication().getRenderRevision());
    }

    @Test
    void aPendingDeliveryBlocksUntilConfirmedNotDelivered() {
        String reference = finalizedReview();
        // The process died after the Git call: the intent never left PENDING.
        ReviewPublicationService.Start orphaned = start(reference, false);

        ReviewPublicationService.Start retry = start(reference, false);
        assertFalse(retry.isStarted());
        assertEquals(ReviewPublicationState.PENDING, retry.blockedBy());

        ReviewPublicationService.Start confirmed = start(reference, true);
        assertTrue(confirmed.isStarted());
        assertEquals(ReviewPublicationState.FAILED,
                publicationRepository.findById(orphaned.publication().getId()).orElseThrow().getState(),
                "the confirmation is recorded on the delivery it released");
    }

    @Test
    void anUncertainDeliveryBlocksUntilConfirmedNotDelivered() {
        String reference = finalizedReview();
        ReviewPublicationService.Start timedOut = start(reference, false);
        publicationService.finish(timedOut.publication().getId(), ReviewPublicationState.UNCERTAIN, null);

        ReviewPublicationService.Start retry = start(reference, false);
        assertFalse(retry.isStarted());
        assertEquals(ReviewPublicationState.UNCERTAIN, retry.blockedBy());

        assertTrue(start(reference, true).isStarted());
    }

    @Test
    void aPublishedDeliveryBlocksEvenAConfirmedRetry() {
        String reference = finalizedReview();
        ReviewPublicationService.Start delivered = start(reference, false);
        publicationService.finish(delivered.publication().getId(), ReviewPublicationState.PUBLISHED, null);

        ReviewPublicationService.Start retry = start(reference, true);

        assertFalse(retry.isStarted());
        assertEquals(ReviewPublicationState.PUBLISHED, retry.blockedBy());
    }

    @Test
    void twoConcurrentDeliveriesOfOneReviewCannotBothStart() throws Exception {
        String reference = finalizedReview();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReviewPublicationService.Start>> starts = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                starts.add(pool.submit(() -> {
                    go.await();
                    return start(reference, false);
                }));
            }
            go.countDown();
            int started = 0;
            for (Future<ReviewPublicationService.Start> future : starts) {
                if (future.get(30, TimeUnit.SECONDS).isStarted()) {
                    started++;
                }
            }
            assertEquals(1, started, "the second delivery must see the first one's PENDING intent");
        } finally {
            pool.shutdownNow();
        }
    }

    private ReviewPublicationService.Start start(String reference, boolean confirmedNotDelivered) {
        return publicationService.start(reference, "GITEA", "pull_request_review", "body",
                confirmedNotDelivered);
    }

    private String finalizedReview() {
        var handle = reviewService.createDraft(new ReviewDraftRequest(1L, 2L, "acme",
                "web-" + UUID.randomUUID(), null, 1L, null, UUID.randomUUID().toString(), null, null));
        reviewService.upsertFinding(handle.reviewId(), null, new FindingDraft("auth",
                ReviewSeverity.MEDIUM, "authorization", "Title", "Explanation.", "Impact.",
                "Recommendation.", List.of(new LocationDraft("src/Foo.java", ReviewSide.HEAD, 1, 2,
                "Evidence."))));
        reviewService.setAssessment(handle.reviewId(), 1, new AssessmentDraft("Summary.",
                ReviewConclusion.FINDINGS_PRESENT, "Correctness.", null));
        reviewService.finalizeReview(handle.reviewId(), 2, 1);
        return reviewService.findDraft(handle.reviewId()).orElseThrow().outputReference();
    }
}
