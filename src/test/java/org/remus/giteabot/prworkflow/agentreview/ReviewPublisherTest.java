package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.structured.FinalizedReview;
import org.remus.giteabot.review.structured.ReviewCommentRenderer;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewLinkStyle;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewSide;
import org.remus.giteabot.review.structured.StructuredReviewService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Publication is a pure function of the stored review: the comment is the renderer's output, the
 * formal action comes from persisted severity totals, and a review that is not in the database is
 * never posted.
 */
@ExtendWith(MockitoExtension.class)
class ReviewPublisherTest {

    @Mock private StructuredReviewService reviewService;
    @Mock private RepositoryApiClient repositoryClient;
    @Mock private EventHookPublisher eventHooks;

    private ReviewPublisher publisher() {
        return new ReviewPublisher(reviewService, new ReviewCommentRenderer(), eventHooks);
    }

    @Test
    void decisionFollowsTheConfiguredThresholdsOnPersistedCounts() {
        FinalizedReview oneBlocker = review(ReviewConclusion.FINDINGS_PRESENT, finding("O1234-F1",
                ReviewSeverity.BLOCKER));

        assertThat(ReviewPublisher.decision(oneBlocker, null)).isEqualTo(PostReviewAction.NONE);
        assertThat(ReviewPublisher.decision(oneBlocker,
                new AgentReviewService.SeverityThresholds(0, null, null)))
                .isEqualTo(PostReviewAction.REQUEST_CHANGES);
        assertThat(ReviewPublisher.decision(oneBlocker,
                new AgentReviewService.SeverityThresholds(1, null, null)))
                .isEqualTo(PostReviewAction.APPROVE);
        assertThat(ReviewPublisher.decision(oneBlocker,
                new AgentReviewService.SeverityThresholds(null, null, null)))
                .isEqualTo(PostReviewAction.NONE);
    }

    @Test
    void anInconclusiveReviewNeverApproves() {
        FinalizedReview inconclusive = new FinalizedReview("O1234", "O1234-A",
                ReviewConclusion.INCONCLUSIVE, "Coverage was incomplete.", null,
                "deployment filters not inspected", null,
                new FinalizedReview.SeverityCounts(0, 0, 0), List.of(), "hash");

        assertThat(ReviewPublisher.decision(inconclusive,
                new AgentReviewService.SeverityThresholds(0, 0, 0))).isEqualTo(PostReviewAction.NONE);
    }

    @Test
    void postsTheRenderedStoredReviewWithTheComputedAction() {
        FinalizedReview stored = review(ReviewConclusion.FINDINGS_PRESENT,
                finding("O1234-F1", ReviewSeverity.BLOCKER));
        when(reviewService.findFinalizedByReference("O1234")).thenReturn(Optional.of(stored));

        assertThat(publisher().publish(repositoryClient, new Bot(), "acme", "web", 9L, "O1234",
                true, new AgentReviewService.SeverityThresholds(0, null, null), ReviewLinkStyle.NONE))
                .isTrue();

        verify(repositoryClient).postReview(eq("acme"), eq("web"), eq(9L),
                argThat(body -> body.contains("AI Review · O1234")
                        && body.contains("BLOCKER · O1234-F1")),
                eq(PostReviewAction.REQUEST_CHANGES));
    }

    @Test
    void aMissingStoredReviewIsNeverPosted() {
        when(reviewService.findFinalizedByReference("O9999")).thenReturn(Optional.empty());

        assertThat(publisher().publish(repositoryClient, new Bot(), "acme", "web", 9L, "O9999",
                false, null, ReviewLinkStyle.NONE)).isFalse();

        verifyNoInteractions(repositoryClient, eventHooks);
    }

    @Test
    void aFailedFormalSubmissionFallsBackToAPlainComment() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER))));
        doThrow(new IllegalStateException("formal submission rejected"))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());

        assertThat(publisher().publish(repositoryClient, new Bot(), "acme", "web", 9L, "O1234",
                true, new AgentReviewService.SeverityThresholds(0, null, null), ReviewLinkStyle.NONE))
                .isTrue();

        verify(repositoryClient).postReviewComment(eq("acme"), eq("web"), eq(9L),
                argThat(body -> body.contains("AI Review · O1234")));
    }

    @Test
    void anUnpostableReviewPropagatesSoTheCallerCanReportIt() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));
        doThrow(new IllegalStateException("provider down"))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());

        assertThrows(IllegalStateException.class, () -> publisher().publish(repositoryClient, new Bot(),
                "acme", "web", 9L, "O1234", false, null, ReviewLinkStyle.NONE));
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(eventHooks);
    }

    @Test
    void emitsOneEventPerFindingWithItsReference() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER),
                        finding("O1234-F2", ReviewSeverity.LOW))));

        publisher().publish(repositoryClient, new Bot(), "acme", "web", 9L, "O1234", false, null,
                ReviewLinkStyle.NONE);

        verify(eventHooks).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED), any(),
                eq("acme"), eq("web"), eq(9L), isNull(),
                argThat(data -> "O1234-F1".equals(reference(data))
                        && "blocker".equals(((Map<?, ?>) data.get("finding")).get("severity"))
                        && "src/Foo.java".equals(((Map<?, ?>) data.get("finding")).get("file"))));
        verify(eventHooks).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED), any(),
                eq("acme"), eq("web"), eq(9L), isNull(),
                argThat(data -> "O1234-F2".equals(reference(data))));
    }

    private static String reference(Map<String, Object> data) {
        return String.valueOf(((Map<?, ?>) data.get("finding")).get("reference"));
    }

    private static FinalizedReview review(ReviewConclusion conclusion,
                                          FinalizedReview.Finding... findings) {
        return new FinalizedReview("O1234", "O1234-A", conclusion, "Summary.", "reviewed",
                conclusion == ReviewConclusion.INCONCLUSIVE ? "not verified" : null, null,
                FinalizedReview.SeverityCounts.of(List.of(findings)), List.of(findings), "hash");
    }

    private static FinalizedReview.Finding finding(String reference, ReviewSeverity severity) {
        return new FinalizedReview.Finding(reference, severity, "correctness", "Title " + reference,
                "Explanation.", "Impact.", "Recommendation.",
                List.of(new FinalizedReview.Location("src/Foo.java", ReviewSide.HEAD, 10, 20,
                        "Evidence.")));
    }
}
