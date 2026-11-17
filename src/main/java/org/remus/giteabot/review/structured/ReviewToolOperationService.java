package org.remus.giteabot.review.structured;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs one review-tool operation exactly once per {@code operationId} and stores its receipt in
 * the same transaction.
 *
 * <p>The receipt row is the idempotency record: a retried call replays the stored result instead
 * of applying the mutation again, and a reused id with different arguments is refused rather
 * than silently applied. Because the row shares the mutation's transaction, a failed
 * mutation leaves neither a finding nor a receipt claiming one — and a concurrent duplicate
 * loses the race on the unique constraint, rolling its own mutation back.</p>
 */
@Service
public class ReviewToolOperationService {

    private final ReviewToolOperationRepository repository;

    public ReviewToolOperationService(ReviewToolOperationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public String run(long reviewId, String operationId, String tool, String argumentHash,
                      Supplier<String> operation) {
        Optional<ReviewToolOperation> existing =
                repository.findByReviewIdAndOperationId(reviewId, operationId);
        if (existing.isPresent()) {
            ReviewToolOperation recorded = existing.get();
            if (!recorded.getTool().equals(tool)
                    || !recorded.getArgumentHash().equals(argumentHash)) {
                throw new ReviewToolException(ReviewToolException.Code.OPERATION_CONFLICT,
                        "Operation '" + operationId + "' was already used with different arguments; "
                                + "use a new operationId for a new call");
            }
            return recorded.getResultJson();
        }
        String result = operation.get();
        ReviewToolOperation receipt = new ReviewToolOperation();
        receipt.setReviewId(reviewId);
        receipt.setOperationId(operationId);
        receipt.setTool(tool);
        receipt.setArgumentHash(argumentHash);
        receipt.setResultJson(result);
        repository.save(receipt);
        return result;
    }
}
