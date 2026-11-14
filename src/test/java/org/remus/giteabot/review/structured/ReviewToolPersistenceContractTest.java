package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persistence contract of review-tool receipts against the real (H2) database of the test
 * profile: a receipt is unique per review and operation, the mutation and its receipt share one
 * transaction, and two concurrent deliveries of the same operation apply the mutation once.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewToolPersistenceContractTest {

    @Autowired private StructuredReviewService reviewService;
    @Autowired private ReviewToolOperationService operationService;
    @Autowired private ReviewToolOperationRepository operationRepository;

    @Test
    void aReceiptIsUniquePerReviewAndOperation() {
        long reviewId = createDraft();

        operationRepository.saveAndFlush(receipt(reviewId, "op-1"));
        ReviewToolOperation duplicate = receipt(reviewId, "op-1");

        assertThrows(DataIntegrityViolationException.class,
                () -> operationRepository.saveAndFlush(duplicate));
    }

    @Test
    void theMutationAndItsReceiptShareOneTransaction() {
        long reviewId = createDraft();

        assertThrows(IllegalStateException.class, () -> operationService.run(reviewId, "op-rollback",
                ReviewSubmissionToolExecutor.UPSERT_FINDING, "hash-1", () -> {
                    reviewService.upsertFinding(reviewId, null, finding("roll-back"));
                    throw new IllegalStateException("the receipt write would have failed here");
                }));

        assertTrue(operationRepository.findByReviewIdAndOperationId(reviewId, "op-rollback").isEmpty(),
                "a rolled-back operation must leave no receipt");
        assertEquals(0, reviewService.findDraft(reviewId).orElseThrow().findings().size(),
                "a rolled-back operation must leave no finding");
    }

    @Test
    void aStoredReceiptIsReplayedInsteadOfReapplyingTheMutation() {
        long reviewId = createDraft();
        AtomicInteger applications = new AtomicInteger();

        String first = operationService.run(reviewId, "op-replay",
                ReviewSubmissionToolExecutor.UPSERT_FINDING, "hash-1", () -> {
                    applications.incrementAndGet();
                    reviewService.upsertFinding(reviewId, null, finding("replay"));
                    return "{\"ok\":true,\"findingId\":1}";
                });
        String replay = operationService.run(reviewId, "op-replay",
                ReviewSubmissionToolExecutor.UPSERT_FINDING, "hash-1", () -> {
                    applications.incrementAndGet();
                    return "{\"ok\":true,\"findingId\":99}";
                });

        assertEquals(first, replay);
        assertEquals(1, applications.get());
        assertEquals(1, reviewService.findDraft(reviewId).orElseThrow().findings().size());
    }

    @Test
    void concurrentDuplicatesApplyTheMutationOnce() throws Exception {
        long reviewId = createDraft();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return operationService.run(reviewId, "op-concurrent",
                            ReviewSubmissionToolExecutor.UPSERT_FINDING, "hash-1", () -> {
                                reviewService.upsertFinding(reviewId, null, finding("concurrent"));
                                return "{\"ok\":true}";
                            });
                }));
            }
            start.countDown();
            int completed = 0;
            for (Future<String> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    completed++;
                } catch (ExecutionException e) {
                    // Losing the unique-constraint race is the expected other outcome.
                }
            }
            assertTrue(completed >= 1, "at least one delivery must be applied");
        } finally {
            pool.shutdownNow();
        }

        // Applied once: the finding was never corrected, so its revision is still the first one.
        DraftSnapshot draft = reviewService.findDraft(reviewId).orElseThrow();
        assertEquals(1, draft.findings().size());
        assertEquals(1, draft.findings().get(0).revision());
        assertEquals(1, operationRepository.findAll().stream()
                .filter(operation -> operation.getReviewId() == reviewId).count());
    }

    private long createDraft() {
        return reviewService.createDraft(new ReviewDraftRequest(1L, 2L, "acme",
                "web-" + UUID.randomUUID(), null, 1L, null, UUID.randomUUID().toString(),
                null, null)).reviewId();
    }

    private static ReviewToolOperation receipt(long reviewId, String operationId) {
        ReviewToolOperation receipt = new ReviewToolOperation();
        receipt.setReviewId(reviewId);
        receipt.setOperationId(operationId);
        receipt.setTool(ReviewSubmissionToolExecutor.UPSERT_FINDING);
        receipt.setArgumentHash("hash-1");
        receipt.setResultJson("{\"ok\":true}");
        return receipt;
    }

    private static FindingDraft finding(String clientKey) {
        return new FindingDraft(clientKey, ReviewSeverity.MEDIUM, "authorization", "Title",
                "Explanation.", "Impact.", "Recommendation.",
                List.of(new LocationDraft("src/Foo.java", ReviewSide.HEAD, 1, 2, "Evidence.")));
    }
}
