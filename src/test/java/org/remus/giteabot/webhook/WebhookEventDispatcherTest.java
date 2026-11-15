package org.remus.giteabot.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.learning.feedback.FeedbackCommandHandler;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests the rules of {@link WebhookEventDispatcher} that no single provider exercises. The
 * trigger rules themselves (run-on-create, run-on-update, mention gating, review-again) are
 * covered per provider by the {@code *WebhookTranslatorTest}s, which run through the real
 * dispatcher.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventDispatcherTest {

    private static final String BOT_ALIAS = "@claude_bot";

    @Mock
    private BotWebhookService botWebhookService;

    private WebhookEventDispatcher dispatcher;
    private Bot bot;
    private WebhookPayload payload;

    @BeforeEach
    void setUp() {
        dispatcher = new WebhookEventDispatcher(botWebhookService, new ReviewTriggerDebouncer());
        lenient().when(botWebhookService.isCallerAllowed(any(), any())).thenReturn(true);
        // The real keyword match, so comments are routed by what they say.
        lenient().when(botWebhookService.isReviewAgainRequest(any(), any())).thenCallRealMethod();
        lenient().when(botWebhookService.reviewPullRequest(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        bot = new Bot();
        bot.setName("test-bot");
        payload = new WebhookPayload();
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(42L);
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setSha("abc123");
        pullRequest.setHead(head);
        payload.setPullRequest(pullRequest);
    }

    private static WebhookPayload.Owner sender(String login) {
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin(login);
        return sender;
    }

    private void comment(String body) {
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setBody(body);
        comment.setUser(sender("maintainer"));
        payload.setComment(comment);
    }

    private WebhookEvent event(WebhookEventKind kind) {
        return eventBuilder(kind).build();
    }

    private WebhookEvent.WebhookEventBuilder eventBuilder(WebhookEventKind kind) {
        return WebhookEvent.builder().kind(kind).payload(payload).botAlias(BOT_ALIAS);
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, mode = EnumSource.Mode.EXCLUDE, names = "IGNORED")
    void eventFromBot_isIgnoredForEveryKind(WebhookEventKind kind) {
        bot.setRunOnPrCreation(true);
        bot.setRunOnPrUpdate(true);
        bot.setRunOnIssueCreation(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(kind).fromBot(true).botMentioned(true).botIsReviewer(true).build());

        assertEquals("ignored", response.getBody());
        verifyNoInteractions(botWebhookService);
    }

    @Test
    void nonIgnoredEventWithoutPayload_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookEvent.builder().kind(WebhookEventKind.PR_OPENED).build());
    }

    @Test
    void automaticTriggersForTheSameCommit_reviewOnce() {
        dispatcher.dispatch(bot, eventBuilder(WebhookEventKind.PR_OPENED).botIsReviewer(true).build());
        ResponseEntity<String> reviewerAdded = dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));
        ResponseEntity<String> assigned = dispatcher.dispatch(bot, event(WebhookEventKind.PR_ASSIGNED));

        assertEquals("ignored", reviewerAdded.getBody());
        assertEquals("ignored", assigned.getBody());
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any(), any());
    }

    @Test
    void reviewThatWasSkippedOrFailed_doesNotSuppressAReRequest() {
        when(botWebhookService.reviewPullRequest(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(false))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("AI provider down")))
                .thenReturn(CompletableFuture.completedFuture(true));

        ResponseEntity<String> first = dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));
        ResponseEntity<String> second = dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));
        ResponseEntity<String> third = dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));
        ResponseEntity<String> fourth = dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));

        assertEquals("review triggered", first.getBody());
        assertEquals("review triggered", second.getBody());
        assertEquals("review triggered", third.getBody());
        // The third review ran, so its claim holds.
        assertEquals("ignored", fourth.getBody());
        verify(botWebhookService, times(3)).reviewPullRequest(eq(bot), any(), any());
    }

    @Test
    void pullRequestClosedByTheBot_endsTheDebounceWindow() {
        dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));
        dispatcher.dispatch(bot, eventBuilder(WebhookEventKind.PR_CLOSED).fromBot(true).build());

        ResponseEntity<String> reopened = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_REOPENED).botIsReviewer(true).build());

        assertEquals("review triggered", reopened.getBody());
        verify(botWebhookService, times(2)).reviewPullRequest(eq(bot), any(), any());
        verify(botWebhookService, never()).handlePrClosed(any(), any());
    }

    @Test
    void triggerFromACallerOutsideTheWhitelist_doesNotClaimTheCommit() {
        // A contributor who is not allowed opens the pull request; a maintainer who is
        // then adds the bot as reviewer within the debounce window.
        WebhookPayload fromContributor = payload;
        fromContributor.setSender(sender("contributor"));
        WebhookPayload fromMaintainer = new WebhookPayload();
        fromMaintainer.setPullRequest(payload.getPullRequest());
        fromMaintainer.setSender(sender("maintainer"));
        bot.setRunOnPrCreation(true);
        when(botWebhookService.isCallerAllowed(bot, fromContributor)).thenReturn(false);

        ResponseEntity<String> opened = dispatcher.dispatch(bot, event(WebhookEventKind.PR_OPENED));
        ResponseEntity<String> reviewerAdded = dispatcher.dispatch(bot, WebhookEvent.builder()
                .kind(WebhookEventKind.PR_REVIEWER_ADDED).payload(fromMaintainer).botAlias(BOT_ALIAS).build());

        assertEquals("ignored", opened.getBody());
        assertEquals("review triggered", reviewerAdded.getBody());
        verify(botWebhookService, never()).reviewPullRequest(eq(bot), eq(fromContributor), any());
        verify(botWebhookService).reviewPullRequest(eq(bot), eq(fromMaintainer), any());
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"PR_COMMENT", "PR_INLINE_COMMENT"})
    void pullRequestCommentWithoutPullRequestOrIssue_isIgnored(WebhookEventKind kind) {
        payload.setPullRequest(null);

        ResponseEntity<String> response = dispatcher.dispatch(bot, eventBuilder(kind).botMentioned(true).build());

        assertEquals("ignored", response.getBody());
        verifyNoInteractions(botWebhookService);
    }

    @Test
    void reviewAgainComment_isNotDebounced() {
        dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));
        comment(BOT_ALIAS + " please review again");
        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService, times(2)).reviewPullRequest(eq(bot), any(), any());
    }

    @Test
    void reviewAgainComment_onAPullRequestWithAnAgentSession_continuesTheSession() {
        // The pull request was created by the bot, so the comment is an instruction to the
        // agent ("review this again and fix the failing test"), not a request for a review.
        comment(BOT_ALIAS + " review this again and fix the failing test");
        when(botWebhookService.hasAgentSession(bot, payload)).thenReturn(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(bot, payload);
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void reviewAgainComment_fromSomeoneOtherThanTheAuthor_triggersTheReview() {
        payload.getPullRequest().setUser(sender("contributor"));
        comment(BOT_ALIAS + " please re-review");

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(bot, payload, WebhookEventKind.PR_COMMENT);
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " can you explain that review comment again?",
            " why did the review flag this again?",
            " please repeat that explanation of the review"})
    void questionThatMentionsReviewAndAgain_reachesTheCommentHandler(String text) {
        comment(BOT_ALIAS + text);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void reviewAgainComment_fromACallerOutsideTheWhitelist_isIgnored() {
        comment(BOT_ALIAS + " please review again");
        when(botWebhookService.isCallerAllowed(bot, payload)).thenReturn(false);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @Test
    void commentThatDoesNotAskForAReview_reachesTheCommentHandler() {
        comment(BOT_ALIAS + " please explain this change");

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("pr comment received", response.getBody());
        verify(botWebhookService).handlePrComment(bot, payload);
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void pullRequestComment_reachesTheHandlerWithTheIssueAdded() {
        // GitLab, Bitbucket and Azure DevOps send only the pull request.
        payload.getPullRequest().setMerged(true);

        dispatcher.dispatch(bot, eventBuilder(WebhookEventKind.PR_INLINE_COMMENT).botMentioned(true).build());

        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handleInlineComment(eq(bot), captor.capture());
        assertEquals(42L, captor.getValue().getIssue().getNumber());
        assertNotNull(captor.getValue().getIssue().getPullRequest());
        assertEquals(true, captor.getValue().getIssue().getPullRequest().getMerged());
    }

    @Test
    void feedbackComment_isConsumedBeforeTheReviewAgainCheck() {
        comment(BOT_ALIAS + " feedback O12-F1 incorrect, please review again");
        when(botWebhookService.tryHandleLearningCommand(bot, payload,
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, BOT_ALIAS)).thenReturn(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("feedback recorded", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @Test
    void inlineFeedbackComment_isConsumedWithTheInlineKind() {
        comment(BOT_ALIAS + " feedback O12-A needs-improvement");
        when(botWebhookService.tryHandleLearningCommand(bot, payload,
                FeedbackCommandHandler.COMMENT_KIND_INLINE, BOT_ALIAS)).thenReturn(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_INLINE_COMMENT).botMentioned(true).build());

        assertEquals("feedback recorded", response.getBody());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
    }
}
