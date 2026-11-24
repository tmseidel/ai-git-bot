package org.remus.giteabot.learning.knowledge;

import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackService;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.feedback.FeedbackTarget;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.review.structured.AssessmentDraft;
import org.remus.giteabot.review.structured.FindingDraft;
import org.remus.giteabot.review.structured.LocationDraft;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewDraftRequest;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewSide;
import org.remus.giteabot.review.structured.StructuredReviewService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real reviews and feedback for lesson tests: a lesson can only be written from stored feedback on
 * a finalized agentic review, so the fixtures create exactly that through the production services.
 */
final class LessonFixtures {

    static final long INTEGRATION = 1L;
    static final long BOT = 2L;
    static final String OWNER = "acme";
    static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";

    private static final AtomicLong COMMENTS = new AtomicLong(1);

    private final StructuredReviewService reviewService;
    private final FeedbackService feedbackService;

    LessonFixtures(StructuredReviewService reviewService, FeedbackService feedbackService) {
        this.reviewService = reviewService;
        this.feedbackService = feedbackService;
    }

    static String newRepository() {
        return "web-" + UUID.randomUUID();
    }

    static FeedbackScope scope(String repo) {
        return new FeedbackScope(INTEGRATION, BOT, OWNER, repo, 1L);
    }

    /** A fresh, never-seen comment — one command. */
    static FeedbackSource comment(String repo) {
        return comment(repo, "c" + COMMENTS.getAndIncrement());
    }

    static FeedbackSource comment(String repo, String commentId) {
        return new FeedbackSource(INTEGRATION, BOT, OWNER, repo, "repo-1",
                FeedbackSource.SubjectKind.PR, 1L, "PR_COMMENT", commentId);
    }

    /** {@code O…} of a finalized review with one finding on {@code src/Foo.java}. */
    String finalizedReview(String repo) {
        var handle = reviewService.createDraft(new ReviewDraftRequest(INTEGRATION, BOT, OWNER, repo,
                null, 1L, null, UUID.randomUUID().toString(), null, HEAD_SHA));
        reviewService.upsertFinding(handle.reviewId(), null, new FindingDraft("auth",
                ReviewSeverity.MEDIUM, "authorization", "Missing permission check", "Explanation.",
                "Impact.", "Recommendation.", List.of(new LocationDraft("src/Foo.java",
                ReviewSide.HEAD, 1, 2, "Evidence."))));
        reviewService.setAssessment(handle.reviewId(), 1, new AssessmentDraft("Summary.",
                ReviewConclusion.FINDINGS_PRESENT, "Correctness.", null));
        reviewService.finalizeReview(handle.reviewId(), 2, 1);
        return reviewService.findDraft(handle.reviewId()).orElseThrow().outputReference();
    }

    /** {@code FB…} of an effective "incorrect" assessment of finding 1. */
    String incorrectFinding(String repo, String outputReference, String actor) {
        return feedbackService.submit(scope(repo), comment(repo), FeedbackTarget.finding(
                        outputReference, 1), FeedbackVerdict.INCORRECT,
                "The permission check is in the security filter.", actor).reference();
    }
}
