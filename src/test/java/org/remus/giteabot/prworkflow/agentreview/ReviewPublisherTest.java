package org.remus.giteabot.prworkflow.agentreview;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.UnknownHttpStatusCodeException;
import org.junit.jupiter.api.BeforeEach;
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
import org.remus.giteabot.review.structured.ReviewPublication;
import org.remus.giteabot.review.structured.ReviewPublicationService;
import org.remus.giteabot.review.structured.ReviewPublicationState;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewSide;
import org.remus.giteabot.review.structured.StructuredReviewService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Publication is a pure function of the stored review: the comment is the renderer's output, the
 * formal action comes from persisted severity totals, and a review that is not in the database is
 * never posted. Delivery state is tracked separately from the review's own lifecycle.
 */
@ExtendWith(MockitoExtension.class)
class ReviewPublisherTest {

    @Mock private StructuredReviewService reviewService;
    @Mock private ReviewPublicationService publicationService;
    @Mock private RepositoryApiClient repositoryClient;
    @Mock private EventHookPublisher eventHooks;

    private ReviewPublisher publisher;
    private Bot bot;

    @BeforeEach
    void setUp() {
        publisher = new ReviewPublisher(reviewService, publicationService,
                new ReviewCommentRenderer(new org.remus.giteabot.config.ReviewCommentProperties()), eventHooks);
        bot = new Bot();
        bot.setId(3L);
        lenient().when(publicationService.start(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    ReviewPublication publication = new ReviewPublication();
                    publication.setId(1L);
                    publication.setRenderRevision(1);
                    return new ReviewPublicationService.Start(publication, null);
                });
    }

    @Test
    void decisionFollowsTheConfiguredThresholdsOnPersistedCounts() {
        FinalizedReview oneBlocker = review(ReviewConclusion.FINDINGS_PRESENT,
                finding("O1234-F1", ReviewSeverity.BLOCKER));

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
    void recordsTheIntentThenPublishesTheRenderedStoredReview() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER))));

        assertThat(publisher.publish(formalRequest()).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.PUBLISHED);

        verify(publicationService).start(eq("O1234"), eq("GITEA"), eq("pull_request_review"),
                argThat(body -> body.contains("AI Review · O1234")
                        && body.contains("🔴 BLOCKER · `O1234-F1`")), eq(false));
        verify(repositoryClient).postReview(eq("acme"), eq("web"), eq(9L), anyString(),
                eq(PostReviewAction.REQUEST_CHANGES));
        verify(publicationService).finish(1L, ReviewPublicationState.PUBLISHED, null);
    }

    @Test
    void aMissingStoredReviewIsNeverPosted() {
        when(reviewService.findFinalizedByReference("O1234")).thenReturn(Optional.empty());

        assertThat(publisher.publish(plainRequest("head", "head", () -> false)).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.SUPPRESSED);

        verifyNoInteractions(repositoryClient, eventHooks, publicationService);
    }

    @Test
    void aCancelledRunSuppressesThePublicationAndKeepsTheReview() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));

        var published = publisher.publish(plainRequest("head", "head", () -> true));

        assertThat(published.outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.SUPPRESSED);
        assertThat(published.reason()).contains("cancelled");
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(publicationService).finish(1L, ReviewPublicationState.SUPPRESSED, null);
        verifyNoInteractions(eventHooks);
    }

    @Test
    void aMovedHeadSuppressesThePublication() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));

        var published = publisher.publish(plainRequest("head-a", "head-b", () -> false));

        assertThat(published.outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.SUPPRESSED);
        assertThat(published.reason()).contains("head");
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(publicationService).finish(1L, ReviewPublicationState.SUPPRESSED, null);
    }

    @Test
    void anUnreadableHeadDoesNotSuppressTheReview() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));

        assertThat(publisher.publish(plainRequest("head-a", null, () -> false)).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.PUBLISHED);
        verify(publicationService).finish(1L, ReviewPublicationState.PUBLISHED, null);
    }

    @Test
    void aFailedFormalSubmissionFallsBackToAPlainComment() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER))));
        doThrow(new IllegalStateException("formal submission rejected"))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());

        assertThat(publisher.publish(formalRequest()).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.PUBLISHED);

        verify(repositoryClient).postReviewComment(eq("acme"), eq("web"), eq(9L),
                argThat(body -> body.contains("AI Review · O1234")));
        verify(publicationService).finish(1L, ReviewPublicationState.PUBLISHED, null);
    }

    @Test
    void anUncertainFormalSubmissionIsNotRetriedAsAPlainComment() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER))));
        // A timeout after the provider may already have accepted the review: the fallback comment
        // would put the same findings on the pull request twice.
        doThrow(new UncheckedIOException(new IOException("Read timed out")))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());

        assertThrows(UncheckedIOException.class, () -> publisher.publish(formalRequest()));

        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(publicationService).finish(1L, ReviewPublicationState.UNCERTAIN, null);
    }

    @Test
    void aDefiniteRefusalOfTheFormalActionStillFallsBack() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER))));
        // 403 self-approval refused / 422 invalid review: the provider definitely did not take it.
        doThrow(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
                HttpHeaders.EMPTY, new byte[0], null))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());

        assertThat(publisher.publish(formalRequest()).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.PUBLISHED);

        verify(repositoryClient).postReviewComment(eq("acme"), eq("web"), eq(9L),
                argThat(body -> body.contains("AI Review · O1234")));
        verify(publicationService).finish(1L, ReviewPublicationState.PUBLISHED, null);
    }

    @Test
    void aDefiniteRefusalIsFailedAndAnUnknownOutcomeIsUncertain() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));

        doThrow(new IllegalStateException("400 Bad Request"))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        assertThrows(IllegalStateException.class,
                () -> publisher.publish(plainRequest("head", "head", () -> false)));
        verify(publicationService).finish(1L, ReviewPublicationState.FAILED, null);

        doThrow(new UncheckedIOException(new IOException("Read timed out")))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        assertThrows(UncheckedIOException.class,
                () -> publisher.publish(plainRequest("head", "head", () -> false)));
        verify(publicationService, atLeastOnce()).finish(1L, ReviewPublicationState.UNCERTAIN, null);
    }

    @Test
    void aGatewayErrorIsUncertainSoTheFormalSubmissionIsNotRetriedAsAComment() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER))));
        // The proxy gave up waiting while the provider may still have accepted the review; the
        // message says nothing about a timeout.
        doThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                HttpHeaders.EMPTY, new byte[0], null))
                .when(repositoryClient).postReview(anyString(), anyString(), anyLong(), anyString(), any());

        assertThrows(HttpServerErrorException.class, () -> publisher.publish(formalRequest()));

        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(publicationService).finish(1L, ReviewPublicationState.UNCERTAIN, null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {500, 502, 503, 504})
    void aServerErrorIsUncertain(int status) {
        assertThat(ReviewPublisher.classify(HttpServerErrorException.create(HttpStatus.valueOf(status),
                "error", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo(ReviewPublicationState.UNCERTAIN);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 403, 404, 409, 422, 429})
    void aClientErrorIsADefiniteRefusal(int status) {
        // Even when the provider's message mentions a timeout, a 4xx means it did not take the review.
        assertThat(ReviewPublisher.classify(HttpClientErrorException.create(HttpStatus.valueOf(status),
                "upstream timeout", HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo(ReviewPublicationState.FAILED);
    }

    @Test
    void noAnswerAtAllIsUncertainAndAnErrorBeforeTheRequestIsFailed() {
        assertThat(ReviewPublisher.classify(new ResourceAccessException("I/O error",
                new java.net.SocketTimeoutException("Read timed out"))))
                .isEqualTo(ReviewPublicationState.UNCERTAIN);
        assertThat(ReviewPublisher.classify(new IllegalStateException("wrapped",
                new java.util.concurrent.TimeoutException())))
                .isEqualTo(ReviewPublicationState.UNCERTAIN);
        assertThat(ReviewPublisher.classify(new IllegalArgumentException("connection reset in the message only")))
                .isEqualTo(ReviewPublicationState.FAILED);
        assertThat(ReviewPublisher.classify(new UnknownHttpStatusCodeException(599, "", HttpHeaders.EMPTY,
                new byte[0], null)))
                .isEqualTo(ReviewPublicationState.UNCERTAIN);
    }

    @Test
    void republishRefusesToDuplicateAnAlreadyPublishedReview() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));
        blockedBy(ReviewPublicationState.PUBLISHED);

        assertThat(publisher.republish(plainRequest("head", "head", () -> false)).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.ALREADY_PUBLISHED);
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void republishRetriesAFailedDeliveryWithoutAModelCall() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));

        assertThat(publisher.republish(plainRequest("head", "head", () -> false)).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.PUBLISHED);

        verify(publicationService).start(eq("O1234"), anyString(), anyString(), anyString(), eq(false));
        verify(repositoryClient).postReview(eq("acme"), eq("web"), eq(9L), anyString(),
                eq(PostReviewAction.NONE));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ReviewPublicationState.class,
            names = {"PENDING", "UNCERTAIN"})
    void anUnresolvedEarlierDeliveryStopsTheFirstPublishAndARepublish(ReviewPublicationState state) {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));
        // A crash after the Git call left PENDING, or a timeout left UNCERTAIN: the review may be
        // on the pull request already. A re-dispatched job must not post it again.
        blockedBy(state);

        assertThat(publisher.publish(plainRequest("head", "head", () -> false)).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.UNRESOLVED);
        assertThat(publisher.republish(plainRequest("head", "head", () -> false)).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.UNRESOLVED);

        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(publicationService, never()).finish(anyLong(), any(), any());
    }

    @Test
    void theFirstPublishRefusesAReviewThatIsAlreadyPublished() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));
        blockedBy(ReviewPublicationState.PUBLISHED);

        assertThat(publisher.publish(plainRequest("head", "head", () -> false)).outcome())
                .isEqualTo(ReviewPublisher.PublicationOutcome.ALREADY_PUBLISHED);
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void onlyAConfirmedRepublishPassesTheConfirmationOn() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));

        assertThat(publisher.republishConfirmedNotDelivered(plainRequest("head", "head", () -> false))
                .outcome()).isEqualTo(ReviewPublisher.PublicationOutcome.PUBLISHED);

        verify(publicationService).start(eq("O1234"), anyString(), anyString(), anyString(), eq(true));
    }

    private void blockedBy(ReviewPublicationState state) {
        when(publicationService.start(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ReviewPublicationService.Start(null, state));
    }

    @Test
    void emitsOneEventPerFindingWithItsReference() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.FINDINGS_PRESENT,
                        finding("O1234-F1", ReviewSeverity.BLOCKER),
                        finding("O1234-F2", ReviewSeverity.LOW))));

        publisher.publish(plainRequest("head", "head", () -> false));

        verify(eventHooks).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED), any(),
                eq("acme"), eq("web"), eq(9L), isNull(),
                argThat(data -> "O1234-F1".equals(reference(data))
                        && "blocker".equals(((Map<?, ?>) data.get("finding")).get("severity"))
                        && "src/Foo.java".equals(((Map<?, ?>) data.get("finding")).get("file"))));
        verify(eventHooks).publish(eq(EventHookEventType.AGENT_REVIEW_FINDING_DETECTED), any(),
                eq("acme"), eq("web"), eq(9L), isNull(),
                argThat(data -> "O1234-F2".equals(reference(data))));
    }

    @Test
    void appendsTheFeedbackHintOnlyWhenTheBotCollectsFeedback() {
        when(reviewService.findFinalizedByReference("O1234"))
                .thenReturn(Optional.of(review(ReviewConclusion.NO_FINDINGS)));

        org.mockito.ArgumentCaptor<String> bodies = org.mockito.ArgumentCaptor.forClass(String.class);

        publisher.publish(requestWithHint("HINT-TEXT"));
        publisher.publish(requestWithHint(null));

        verify(repositoryClient, org.mockito.Mockito.times(2)).postReview(eq("acme"), eq("web"), eq(9L),
                bodies.capture(), eq(PostReviewAction.NONE));
        assertThat(bodies.getAllValues().get(0)).contains("HINT-TEXT");
        assertThat(bodies.getAllValues().get(1)).doesNotContain("HINT-TEXT");
    }

    // ------------------------------------------------------------------ helpers

    private ReviewPublisher.PublicationRequest plainRequest(String expectedHead, String currentHead,
                                                            BooleanSupplier cancelled) {
        return new ReviewPublisher.PublicationRequest(repositoryClient, bot, "acme", "web", 9L, "O1234",
                false, null, ReviewLinkStyle.NONE, "GITEA", "pull_request_review", expectedHead,
                currentHead == null ? null : () -> currentHead, cancelled, null);
    }

    private ReviewPublisher.PublicationRequest requestWithHint(String hint) {
        return new ReviewPublisher.PublicationRequest(repositoryClient, bot, "acme", "web", 9L, "O1234",
                false, null, ReviewLinkStyle.NONE, "GITEA", "pull_request_review", "head",
                () -> "head", () -> false, hint);
    }

    private ReviewPublisher.PublicationRequest formalRequest() {
        return new ReviewPublisher.PublicationRequest(repositoryClient, bot, "acme", "web", 9L, "O1234",
                true, new AgentReviewService.SeverityThresholds(0, null, null), ReviewLinkStyle.NONE,
                "GITEA", "pull_request_review", "head", () -> "head", () -> false, null);
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
