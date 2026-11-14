package org.remus.giteabot.learning.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.RepositoryPlatformFactory;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.CommentId;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The handler is the only place that turns a comment into a stored assessment. These tests pin the
 * three properties that matter operationally: nothing is forwarded to an AI path, nothing is
 * recorded when the bot or actor is not allowed, and a failed acknowledgement never loses feedback.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackCommandHandlerTest {

    @Mock private FeedbackService feedbackService;
    @Mock private FeedbackAuthorizationService authorizationService;
    @Mock private AgentReviewLearningSettingsResolver learningSettingsResolver;
    @Mock private RepositoryPlatformFactory repositoryPlatformFactory;
    @Mock private RepositoryApiClient repositoryClient;

    private FeedbackCommandHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        handler = new FeedbackCommandHandler(feedbackService, authorizationService,
                learningSettingsResolver, repositoryPlatformFactory);
        bot = new Bot();
        bot.setId(2L);
        bot.setName("review-bot");
        GitIntegration integration = new GitIntegration();
        integration.setId(1L);
        integration.setUsername("ai_bot");
        bot.setGitIntegration(integration);
        lenient().when(repositoryPlatformFactory.getApiClient(any())).thenReturn(repositoryClient);
        lenient().when(learningSettingsResolver.resolve(any(Bot.class)))
                .thenReturn(new AgentReviewLearningSettings(true));
        lenient().when(authorizationService.canContribute(any(), anyString())).thenReturn(true);
    }

    @Test
    void aCommentWithoutAFeedbackCommandKeepsItsOrdinaryRouting() {
        assertThat(handler.tryHandle(bot, payload("@ai_bot please look at this again"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT)).isFalse();

        verifyNoInteractions(feedbackService);
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void recordsTheCommandAndAcknowledgesWithItsReference() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234-F2",
                        FeedbackTargetType.FINDING, FeedbackVerdict.INCORRECT, "alice", false));

        assertThat(handler.tryHandle(bot,
                payload("@ai_bot feedback O1234-F2 incorrect Authorization is in the filter"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT)).isTrue();

        ArgumentCaptor<FeedbackSource> source = ArgumentCaptor.forClass(FeedbackSource.class);
        verify(feedbackService).submit(eq(new FeedbackScope(1L, 2L, "acme", "web", 9L)),
                source.capture(), eq(FeedbackTarget.finding("O1234", 2)),
                eq(FeedbackVerdict.INCORRECT), eq("Authorization is in the filter"), eq("alice"));
        assertThat(source.getValue().commentKind()).isEqualTo("PR_COMMENT");
        assertThat(source.getValue().commentId()).isEqualTo("777");
        assertThat(source.getValue().providerRepositoryId()).isEqualTo("42");
        assertThat(source.getValue().isIdentifiable()).isTrue();

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L), reply.capture());
        assertThat(reply.getValue())
                .contains("Recorded **FB7**")
                .contains("O1234-F2")
                .contains("incorrect")
                .contains("feedback retract");
    }

    @Test
    void disabledCollectionIsExplainedAndNothingIsRecorded() {
        when(learningSettingsResolver.resolve(any(Bot.class)))
                .thenReturn(AgentReviewLearningSettings.DISABLED);

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT)).isTrue();

        verifyNoInteractions(feedbackService);
        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L),
                org.mockito.ArgumentMatchers.contains("Feedback is not enabled for this bot"));
    }

    @Test
    void aCommenterWithoutPermissionGetsAnAnswerInsteadOfSilence() {
        when(authorizationService.canContribute(bot, "alice")).thenReturn(false);

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT)).isTrue();

        verifyNoInteractions(feedbackService);
        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L),
                org.mockito.ArgumentMatchers.contains("not permitted to give feedback"));
    }

    @Test
    void aMalformedCommandIsConsumedWithHelp() {
        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 nonsense"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT)).isTrue();

        verifyNoInteractions(feedbackService);
        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L),
                org.mockito.ArgumentMatchers.contains("is not a verdict"));
    }

    @Test
    void aRejectedCommandExplainsWhy() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenThrow(new FeedbackRejectedException("unknown-target",
                        "No review O9999 is available here."));

        handler.tryHandle(bot, payload("@ai_bot feedback O9999 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT);

        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L),
                org.mockito.ArgumentMatchers.contains("No review O9999 is available here."));
    }

    @Test
    void aRedeliveredCommentDoesNotConfirmTwice() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", true));

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT)).isTrue();

        // One comment, one confirmation: a redelivered webhook resolves to the stored record and
        // posts nothing, so a retry cannot litter the pull request.
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void aRepeatedRetractionDoesNotConfirmTwice() {
        when(feedbackService.retract(any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", true));

        handler.tryHandle(bot, payload("@ai_bot feedback retract FB7"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT);

        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void aFailedReplyDoesNotFailTheCommand() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", false));
        doThrow(new IllegalStateException("network down")).when(repositoryClient)
                .postPullRequestComment(anyString(), anyString(), anyLong(), anyString());

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT)).isTrue();
    }

    @Test
    void listAndShowReadWithoutWriting() {
        when(feedbackService.list(any(), eq(false), any(), isNull())).thenReturn(List.of());
        when(feedbackService.show(any(), eq("FB31"))).thenReturn(Optional.of(
                new FeedbackService.Summary(1L, "FB31", "O1234-F2", "O1234-F2",
                        FeedbackTargetType.FINDING, FeedbackVerdict.INCORRECT,
                        FeedbackState.EFFECTIVE, "alice", "because", 9L, "acme", "web", null,
                        java.time.Instant.parse("2026-11-14T10:00:00Z"))));

        handler.tryHandle(bot, payload("@ai_bot feedback list"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT);
        handler.tryHandle(bot, payload("@ai_bot feedback show FB31"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT);

        verify(feedbackService, never()).submit(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<String> replies = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient, org.mockito.Mockito.times(2))
                .postPullRequestComment(eq("acme"), eq("web"), eq(9L), replies.capture());
        assertThat(replies.getAllValues().get(0)).contains("No feedback on this pull request yet.");
        assertThat(replies.getAllValues().get(1)).contains("FB31").contains("incorrect");
    }

    // ------------------------------------------------------------------ fixtures

    private static WebhookPayload payload(String body) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setId(42L);
        repository.setName("web");
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("acme");
        repository.setOwner(owner);
        payload.setRepository(repository);

        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(9L);
        payload.setPullRequest(pullRequest);

        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(CommentId.NumericCommentId.of(777L));
        comment.setBody(body);
        WebhookPayload.Owner author = new WebhookPayload.Owner();
        author.setLogin("alice");
        comment.setUser(author);
        payload.setComment(comment);
        return payload;
    }
}
