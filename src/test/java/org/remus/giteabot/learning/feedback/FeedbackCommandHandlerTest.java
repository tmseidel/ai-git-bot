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
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.RepositoryUser;
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
    @Mock private RepositoryPlatform platform;

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
        bot.setGitIntegration(integration);
        lenient().when(repositoryPlatformFactory.getApiClient(any())).thenReturn(repositoryClient);
        lenient().when(repositoryPlatformFactory.getPlatform(any())).thenReturn(platform);
        lenient().when(platform.getUser(any())).thenReturn(new RepositoryUser("7", "ai_bot", null, null));
        lenient().when(learningSettingsResolver.resolve(any(Bot.class)))
                .thenReturn(new AgentReviewLearningSettings(true));
        lenient().when(authorizationService.canContribute(any(), anyString())).thenReturn(true);
    }

    @Test
    void aCommentWithoutAFeedbackCommandKeepsItsOrdinaryRouting() {
        assertThat(handler.tryHandle(bot, payload("@ai_bot please look at this again"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isFalse();

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
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

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
    void disabledCollectionConsumesATargetSilently() {
        when(learningSettingsResolver.resolve(any(Bot.class)))
                .thenReturn(AgentReviewLearningSettings.DISABLED);

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

        // Consumed, so a target is never forwarded to an AI conversation — but no comment: an
        // explanation per attempt is free amplification for anyone who reads the help text.
        verifyNoInteractions(feedbackService);
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void proseOnABotThatCollectsNoFeedbackKeepsItsOrdinaryRouting() {
        when(learningSettingsResolver.resolve(any(Bot.class)))
                .thenReturn(AgentReviewLearningSettings.DISABLED);

        // Not a target and not a subcommand: a bot without feedback must not swallow it.
        assertThat(handler.tryHandle(bot,
                payload("@ai_bot feedback please, is this approach OK?"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isFalse();

        verifyNoInteractions(feedbackService);
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void aSubcommandOnABotThatCollectsNoFeedbackIsStillConsumed() {
        when(learningSettingsResolver.resolve(any(Bot.class)))
                .thenReturn(AgentReviewLearningSettings.DISABLED);

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback list"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void aCommenterWithoutPermissionIsRefusedWithoutAPublicReply() {
        when(authorizationService.canContribute(bot, "alice")).thenReturn(false);

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

        // "not permitted" in public tells an excluded commenter exactly how to make the bot talk.
        verifyNoInteractions(feedbackService);
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void aMalformedCommandIsConsumedWithHelp() {
        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 nonsense"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

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
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L),
                org.mockito.ArgumentMatchers.contains("No review O9999 is available here."));
    }

    @Test
    void aRedeliveredCommentDoesNotConfirmTwice() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", true));

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

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
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

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
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();
    }

    @Test
    void theProvidersReadableAliasIsAcceptedEvenWhenItIsNotTheUsername() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB8", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", false));

        // Bitbucket leaves its readable mention in the body after rewriting @{account_id}; the
        // account's username is unrelated (a Bitbucket account is addressed by id, and the display
        // name is what users actually type).
        when(platform.getUser(any())).thenReturn(new RepositoryUser("{bb-uuid}", "bitbucket-agent", null, null));

        assertThat(handler.tryHandle(bot, payload("@AI_Bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, "@AI_Bot")).isTrue();

        verify(feedbackService).submit(any(), any(), eq(FeedbackTarget.output("O1234")),
                eq(FeedbackVerdict.USEFUL), any(), eq("alice"));
    }

    @Test
    void theConfiguredAliasStillWorksWhenAProviderDidNotRewriteTheMention() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB9", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", false));

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, "@AI_Bot")).isTrue();
    }

    @Test
    void anInternalFailureIsNotEchoedIntoThePublicComment() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("could not execute statement; SQL: insert into "
                        + "workflow_feedback (reference, verdict) values ($1, $2)"));

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L), reply.capture());
        assertThat(reply.getValue())
                .contains("could not be completed")
                .doesNotContain("workflow_feedback")
                .doesNotContain("SQL")
                .doesNotContain("values");
    }

    @Test
    void aRejectionShowsTheMessageWrittenForTheCommenter() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenThrow(new FeedbackRejectedException("not-finalized",
                        "`O1234` is not a finalized review, so there is nothing to judge."));

        assertThat(handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null)).isTrue();

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L), reply.capture());
        assertThat(reply.getValue()).contains("not a finalized review");
    }

    @Test
    void listAndShowReadWithoutWriting() {
        when(feedbackService.list(any(), eq(false), any(), isNull()))
                .thenReturn(new FeedbackService.ListPage(List.of(), null));
        when(feedbackService.show(any(), eq("FB31"))).thenReturn(Optional.of(
                new FeedbackService.Detail(summary("FB31", 9L), List.of())));

        handler.tryHandle(bot, payload("@ai_bot feedback list"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);
        handler.tryHandle(bot, payload("@ai_bot feedback show FB31"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

        verify(feedbackService, never()).submit(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<String> replies = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient, org.mockito.Mockito.times(2))
                .postPullRequestComment(eq("acme"), eq("web"), eq(9L), replies.capture());
        assertThat(replies.getAllValues().get(0))
                .contains("Feedback for **review-bot** on this pull request: none yet.");
        assertThat(replies.getAllValues().get(1)).contains("FB31").contains("incorrect");
    }

    @Test
    void aFeedbackCommandInASubmittedReviewIsRecordedFromTheReview() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "bob", false, 50L, FeedbackAcknowledgement.PENDING));
        WebhookPayload payload = payload("unused");
        payload.setComment(null);
        WebhookPayload.Review review = new WebhookPayload.Review();
        review.setId(4242L);
        review.setContent("@ai_bot feedback O1234 useful Please review again later, but this was good.");
        payload.setReview(review);
        WebhookPayload.Owner reviewer = new WebhookPayload.Owner();
        reviewer.setLogin("bob");
        payload.setSender(reviewer);

        assertThat(handler.tryHandle(bot, payload, FeedbackCommandHandler.COMMENT_KIND_REVIEW, null))
                .isTrue();

        ArgumentCaptor<FeedbackSource> source = ArgumentCaptor.forClass(FeedbackSource.class);
        verify(feedbackService).submit(any(), source.capture(), eq(FeedbackTarget.output("O1234")),
                eq(FeedbackVerdict.USEFUL), eq("Please review again later, but this was good."),
                eq("bob"));
        assertThat(source.getValue().commentKind()).isEqualTo("REVIEW");
        assertThat(source.getValue().commentId()).isEqualTo("4242");
    }

    @Test
    void aReviewWithoutAFeedbackCommandKeepsItsRouting() {
        WebhookPayload payload = payload("unused");
        payload.setComment(null);
        WebhookPayload.Review review = new WebhookPayload.Review();
        review.setId(4242L);
        review.setContent("@ai_bot why is this a blocker?");
        payload.setReview(review);

        assertThat(handler.tryHandle(bot, payload, FeedbackCommandHandler.COMMENT_KIND_REVIEW, null))
                .isFalse();
        verifyNoInteractions(feedbackService);
    }

    @Test
    void aPostedConfirmationIsRecordedAsPosted() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", false, 50L, FeedbackAcknowledgement.PENDING));

        handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

        verify(feedbackService).markAcknowledgement(50L, FeedbackAcknowledgement.POSTED);
    }

    @Test
    void aConfirmationThatTimedOutIsUncertainAndARefusedOneFailed() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", false, 50L, FeedbackAcknowledgement.PENDING));
        doThrow(new org.springframework.web.client.ResourceAccessException("Read timed out",
                new java.net.SocketTimeoutException()))
                .doThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden",
                        org.springframework.http.HttpHeaders.EMPTY, new byte[0], null))
                .when(repositoryClient)
                .postPullRequestComment(anyString(), anyString(), anyLong(), anyString());

        handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);
        handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

        verify(feedbackService).markAcknowledgement(50L, FeedbackAcknowledgement.UNCERTAIN);
        verify(feedbackService).markAcknowledgement(50L, FeedbackAcknowledgement.FAILED);
    }

    @Test
    void aRedeliveryRepostsARefusedConfirmationOnce() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", true, 50L, FeedbackAcknowledgement.FAILED));
        when(feedbackService.claimAcknowledgementRetry(50L)).thenReturn(true, false);

        handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);
        handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

        verify(repositoryClient, org.mockito.Mockito.times(1)).postPullRequestComment(eq("acme"),
                eq("web"), eq(9L), org.mockito.ArgumentMatchers.contains("Recorded **FB7**"));
        verify(feedbackService).markAcknowledgement(50L, FeedbackAcknowledgement.POSTED);
    }

    @Test
    void aRedeliveryNeverRepostsAnUncertainConfirmation() {
        when(feedbackService.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FeedbackService.Recorded("FB7", "O1234", FeedbackTargetType.OUTPUT,
                        FeedbackVerdict.USEFUL, "alice", true, 50L, FeedbackAcknowledgement.UNCERTAIN));

        handler.tryHandle(bot, payload("@ai_bot feedback O1234 useful"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

        verify(feedbackService, never()).claimAcknowledgementRetry(any());
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    @Test
    void overviewsNameTheBotLinkTheSourceAndCarryTheCursor() {
        when(platform.getCloneUrl()).thenReturn("https://github.com");
        bot.getGitIntegration().setProviderType(org.remus.giteabot.repository.RepositoryType.GITHUB);
        when(feedbackService.list(any(), eq(true), any(), eq("CURSOR1")))
                .thenReturn(new FeedbackService.ListPage(List.of(summary("FB31", 12L)), "CURSOR2"));

        handler.tryHandle(bot, payload("@ai_bot feedback list repository page: CURSOR1"),
                FeedbackCommandHandler.COMMENT_KIND_PR_COMMENT, null);

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient).postPullRequestComment(eq("acme"), eq("web"), eq(9L), reply.capture());
        assertThat(reply.getValue())
                .contains("Feedback for **review-bot** in this repository")
                .contains("[PR #12](https://github.com/acme/web/pull/12)")
                .contains("https://github.com/acme/web/pull/12#issuecomment-777")
                .contains("@ai_bot feedback list repository page: CURSOR2");
    }

    // ------------------------------------------------------------------ fixtures

    private static FeedbackService.Summary summary(String reference, Long prNumber) {
        return new FeedbackService.Summary(1L, reference, "O1234", "O1234-F2",
                FeedbackTargetType.FINDING, FeedbackVerdict.INCORRECT, FeedbackState.EFFECTIVE,
                "alice", "because", prNumber, "acme", "web", null,
                java.time.Instant.parse("2026-11-14T10:00:00Z"), "PR_COMMENT", "777");
    }

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
