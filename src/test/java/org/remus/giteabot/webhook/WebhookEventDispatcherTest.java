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
import org.remus.giteabot.repository.PullRequestCommentKind;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests the trigger rules of {@link WebhookEventDispatcher}: which {@link BotWebhookService}
 * handler each {@link WebhookEventKind} reaches, and under which bot settings. How a provider's
 * webhook becomes a {@link WebhookEvent} is covered by the {@code *WebhookTranslatorTest}s.
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
    void ignoredEvent_reachesNoHandler() {
        ResponseEntity<String> response = dispatcher.dispatch(bot, WebhookEvent.ignored("not relevant"));

        assertEquals("ignored", response.getBody());
        verifyNoInteractions(botWebhookService);
    }

    // ---- Pull request lifecycle ----

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"PR_OPENED", "PR_REOPENED"})
    void pullRequestCreation_withoutRunOnPrCreationOrBotReviewer_isIgnored(WebhookEventKind kind) {
        ResponseEntity<String> response = dispatcher.dispatch(bot, event(kind));

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"PR_OPENED", "PR_REOPENED"})
    void pullRequestCreation_withRunOnPrCreation_triggersTheReview(WebhookEventKind kind) {
        bot.setRunOnPrCreation(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot, event(kind));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(bot, payload, kind);
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"PR_OPENED", "PR_REOPENED"})
    void pullRequestCreation_withTheBotAsReviewer_triggersTheReview(WebhookEventKind kind) {
        ResponseEntity<String> response = dispatcher.dispatch(bot, eventBuilder(kind).botIsReviewer(true).build());

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(bot, payload, kind);
    }

    @Test
    void push_withoutRunOnPrUpdate_isIgnoredEvenWhenTheBotIsReviewer() {
        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_SYNCHRONIZED).botIsReviewer(true).build());

        assertEquals("ignored", response.getBody());
        verify(botWebhookService, never()).reviewPullRequest(any(), any(), any());
    }

    @Test
    void push_withRunOnPrUpdate_triggersTheReview() {
        bot.setRunOnPrUpdate(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot, event(WebhookEventKind.PR_SYNCHRONIZED));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(bot, payload, WebhookEventKind.PR_SYNCHRONIZED);
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"PR_REVIEWER_ADDED", "PR_ASSIGNED"})
    void botAddedToAPullRequest_triggersTheReviewWhateverTheSettings(WebhookEventKind kind) {
        ResponseEntity<String> response = dispatcher.dispatch(bot, event(kind));

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService).reviewPullRequest(bot, payload, kind);
    }

    @Test
    void closedPullRequest_reachesTheCloseHandler() {
        ResponseEntity<String> response = dispatcher.dispatch(bot, event(WebhookEventKind.PR_CLOSED));

        assertEquals("session closed", response.getBody());
        verify(botWebhookService).handlePrClosed(bot, payload);
    }

    @Test
    void submittedReview_reachesTheReviewHandler() {
        ResponseEntity<String> response = dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEW_SUBMITTED));

        assertEquals("review comments processing triggered", response.getBody());
        verify(botWebhookService).handleReviewSubmitted(bot, payload);
    }

    // ---- Issues ----

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"ISSUE_OPENED", "ISSUE_REOPENED"})
    void issueCreation_withoutRunOnIssueCreation_isIgnored(WebhookEventKind kind) {
        ResponseEntity<String> response = dispatcher.dispatch(bot, event(kind));

        assertEquals("ignored", response.getBody());
        verifyNoInteractions(botWebhookService);
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"ISSUE_OPENED", "ISSUE_REOPENED"})
    void issueCreation_withRunOnIssueCreation_startsTheAgent(WebhookEventKind kind) {
        bot.setRunOnIssueCreation(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot, event(kind));

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueCreated(bot, payload);
    }

    @Test
    void issueAssignedToTheBot_startsTheAgentWhateverTheSettings() {
        ResponseEntity<String> response = dispatcher.dispatch(bot, event(WebhookEventKind.ISSUE_ASSIGNED));

        assertEquals("agent triggered", response.getBody());
        verify(botWebhookService).handleIssueAssigned(bot, payload);
    }

    // ---- Comments ----

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"PR_COMMENT", "PR_INLINE_COMMENT", "ISSUE_COMMENT"})
    void commentThatDoesNotMentionTheBot_isIgnored(WebhookEventKind kind) {
        comment("looks good to me");

        ResponseEntity<String> response = dispatcher.dispatch(bot, event(kind));

        assertEquals("ignored", response.getBody());
        verifyNoInteractions(botWebhookService);
    }

    @Test
    void issueCommentThatMentionsTheBot_reachesTheIssueCommentHandler() {
        comment(BOT_ALIAS + " please take a look");

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.ISSUE_COMMENT).botMentioned(true).build());

        assertEquals("issue comment received", response.getBody());
        verify(botWebhookService).handleIssueComment(bot, payload);
    }

    @Test
    void inlineCommentThatMentionsTheBot_reachesTheInlineCommentHandler() {
        comment(BOT_ALIAS + " why this?");

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_INLINE_COMMENT).botMentioned(true).build());

        assertEquals("inline comment response triggered", response.getBody());
        verify(botWebhookService).handleInlineComment(bot, payload);
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
                PullRequestCommentKind.PR_COMMENT, BOT_ALIAS)).thenReturn(true);

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
                PullRequestCommentKind.INLINE_COMMENT, BOT_ALIAS)).thenReturn(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_INLINE_COMMENT).botMentioned(true).build());

        assertEquals("feedback recorded", response.getBody());
        verify(botWebhookService, never()).handleInlineComment(any(), any());
    }

    @Test
    void feedbackInASubmittedReview_isConsumedBeforeTheClarification() {
        WebhookPayload.Review review = new WebhookPayload.Review();
        review.setId(7L);
        review.setContent(BOT_ALIAS + " feedback O12 useful");
        payload.setReview(review);
        payload.setSender(sender("maintainer"));
        when(botWebhookService.tryHandleLearningCommand(bot, payload,
                PullRequestCommentKind.REVIEW, BOT_ALIAS)).thenReturn(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                event(WebhookEventKind.PR_REVIEW_SUBMITTED));

        assertEquals("feedback recorded", response.getBody());
        verify(botWebhookService, never()).handleReviewSubmitted(any(), any());
    }

    @Test
    void submittedReviewWithoutFeedback_keepsItsRouting() {
        WebhookPayload.Review review = new WebhookPayload.Review();
        review.setContent(BOT_ALIAS + " why is this a blocker?");
        payload.setReview(review);
        when(botWebhookService.tryHandleLearningCommand(bot, payload,
                PullRequestCommentKind.REVIEW, BOT_ALIAS)).thenReturn(false);

        dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEW_SUBMITTED));

        verify(botWebhookService).handleReviewSubmitted(bot, payload);
    }

    @Test
    void feedbackOnAClosedPullRequest_isStillConsumed() {
        payload.getPullRequest().setState("closed");
        payload.getPullRequest().setMerged(true);
        comment(BOT_ALIAS + " feedback O12-F1 useful");
        when(botWebhookService.tryHandleLearningCommand(bot, payload,
                PullRequestCommentKind.PR_COMMENT, BOT_ALIAS)).thenReturn(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("feedback recorded", response.getBody());
        verify(botWebhookService, never()).handlePrComment(any(), any());
    }

    @Test
    void pullRequestComment_reachesTheHandlerWithThePullRequestAdded() {
        // Gitea and GitHub send only the pull request's issue.
        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(42L);
        issue.setTitle("Some PR");
        issue.setUser(sender("author"));
        payload.setPullRequest(null);
        payload.setIssue(issue);
        comment(BOT_ALIAS + " please explain this change");

        dispatcher.dispatch(bot, eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handlePrComment(eq(bot), captor.capture());
        WebhookPayload.PullRequest derived = captor.getValue().getPullRequest();
        assertEquals(42L, derived.getNumber());
        assertEquals("Some PR", derived.getTitle());
        assertEquals("author", derived.getUser().getLogin());
        assertNotNull(captor.getValue().getIssue().getPullRequest());
    }
}
