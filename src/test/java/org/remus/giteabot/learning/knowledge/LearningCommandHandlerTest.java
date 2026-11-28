package org.remus.giteabot.learning.knowledge;

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
import org.remus.giteabot.learning.feedback.FeedbackAcknowledgement;
import org.remus.giteabot.learning.feedback.FeedbackAuthorizationService;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.feedback.FeedbackState;
import org.remus.giteabot.learning.feedback.FeedbackTargetType;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningMode;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.repository.PullRequestCommentKind;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.repository.model.RepositoryUser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * The handler decides who may do what with lessons. These tests pin the policy: maintainers come
 * only from the bot's current configuration, an empty list grants nobody, curated learning must be
 * on to create or activate, disabling always works, and nothing reaches an AI path.
 */
@ExtendWith(MockitoExtension.class)
class LearningCommandHandlerTest {

    private static final String TEXT = "Before reporting a missing permission check, inspect the "
            + "enclosing security filter.";

    @Mock private KnowledgeLessonService lessonService;
    @Mock private FeedbackAuthorizationService authorizationService;
    @Mock private AgentReviewLearningSettingsResolver settingsResolver;
    @Mock private RepositoryPlatformFactory repositoryPlatformFactory;
    @Mock private RepositoryApiClient repositoryClient;
    @Mock private RepositoryPlatform platform;

    private LearningCommandHandler handler;
    private Bot bot;

    @BeforeEach
    void setUp() {
        handler = new LearningCommandHandler(lessonService, authorizationService, settingsResolver,
                repositoryPlatformFactory);
        bot = new Bot();
        bot.setId(2L);
        bot.setName("review-bot");
        GitIntegration integration = new GitIntegration();
        integration.setId(1L);
        bot.setGitIntegration(integration);
        lenient().when(repositoryPlatformFactory.getApiClient(any())).thenReturn(repositoryClient);
        lenient().when(repositoryPlatformFactory.getPlatform(any())).thenReturn(platform);
        lenient().when(platform.getUser(any())).thenReturn(new RepositoryUser("7", "ai_bot", null, null));
        lenient().when(authorizationService.canContribute(any(), anyString())).thenReturn(true);
        curated("alex");
    }

    @Test
    void aCommentWithoutALessonCommandKeepsItsOrdinaryRouting() {
        assertThat(handle("@ai_bot please look at this again", "alex")).isFalse();
        verifyNoInteractions(lessonService);
    }

    @Test
    void proseOnABotWithoutLessonManagementKeepsItsRouting() {
        when(settingsResolver.resolve(any(Bot.class))).thenReturn(AgentReviewLearningSettings.DISABLED);

        assertThat(handle("@ai_bot learning from this, could you explain it?", "alex")).isFalse();
        verifyNoInteractions(lessonService);
        assertNoReply();
    }

    @Test
    void readingWorksWithLearningOffForAnyPermittedCommenter() {
        when(settingsResolver.resolve(any(Bot.class))).thenReturn(AgentReviewLearningSettings.DISABLED);
        when(lessonService.list(any(), isNull(), any(), isNull()))
                .thenReturn(new KnowledgeLessonService.ListPage(List.of(detail(LessonState.ACTIVE,
                        1).summary()), null));

        assertThat(handle("@ai_bot learning list", "carol")).isTrue();

        assertThat(reply()).contains("Lessons for bot **review-bot** in acme/web")
                .contains("**Active**").contains("L17@1").contains("used in 0 reviews");
    }

    @Test
    void aCommenterTheBotDoesNotAcceptIsRefusedSilently() {
        when(authorizationService.canContribute(bot, "mallory")).thenReturn(false);

        assertThat(handle("@ai_bot learning list", "mallory")).isTrue();

        verifyNoInteractions(lessonService);
        assertNoReply();
    }

    @Test
    void aContributorWhoIsNoMaintainerCannotActivate() {
        assertThat(handle("@ai_bot learning activate L17@1", "carol")).isTrue();

        verify(lessonService, never()).activate(any(), any(), any(), anyInt(), any());
        assertThat(reply()).contains("Only learning maintainers can change lessons");
    }

    @Test
    void anEmptyMaintainerListGrantsNobody() {
        when(settingsResolver.resolve(any(Bot.class))).thenReturn(new AgentReviewLearningSettings(
                true, AgentReviewLearningMode.CURATED, Set.of()));

        handle("@ai_bot learning propose FB31 " + TEXT, "alex");
        handle("@ai_bot learning revise L17\npaths: **", "alex");

        verify(lessonService, never()).propose(any(), any(), any(), any(), any(), any());
        verify(lessonService, never()).revise(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aMaintainerNeedsCuratedLearningToCreateOrActivateButCanAlwaysDisable() {
        when(settingsResolver.resolve(any(Bot.class))).thenReturn(new AgentReviewLearningSettings(
                false, AgentReviewLearningMode.OFF, Set.of("alex")));
        when(lessonService.disable(any(), any(), eq("L17"), isNull(), eq("alex")))
                .thenReturn(recorded(LessonEventType.DISABLED, 1, false));
        when(lessonService.show(any(), eq("L17"))).thenReturn(Optional.of(detail(LessonState.DISABLED, 1)));

        handle("@ai_bot learning activate L17@1", "alex");
        verify(lessonService, never()).activate(any(), any(), any(), anyInt(), any());
        assertThat(reply()).contains("Curated learning is off").contains("OFF");

        handle("@ai_bot learning disable L17", "alex");
        verify(lessonService).disable(any(), any(), eq("L17"), isNull(), eq("alex"));
    }

    @Test
    void aProposalShowsTheExactCandidateItsRepositoryWideScopeAndHowToActivate() {
        when(lessonService.propose(any(), any(), eq("FB31"), isNull(), eq(TEXT), eq("alex")))
                .thenReturn(recorded(LessonEventType.PROPOSED, 1, false));
        when(lessonService.show(any(), eq("L17"))).thenReturn(Optional.of(detail(LessonState.CANDIDATE, 1)));

        assertThat(handle("@ai_bot learning propose FB31 " + TEXT, "alex")).isTrue();

        ArgumentCaptor<FeedbackScope> scope = ArgumentCaptor.forClass(FeedbackScope.class);
        ArgumentCaptor<FeedbackSource> source = ArgumentCaptor.forClass(FeedbackSource.class);
        verify(lessonService).propose(scope.capture(), source.capture(), eq("FB31"), isNull(),
                eq(TEXT), eq("alex"));
        assertThat(scope.getValue()).isEqualTo(new FeedbackScope(1L, 2L, "acme", "web", 9L));
        assertThat(source.getValue().commentId()).isEqualTo("777");
        assertThat(source.getValue().isIdentifiable()).isTrue();

        assertThat(reply())
                .contains("Candidate **L17@1** for bot **review-bot**")
                .contains("> " + TEXT)
                .contains("**every path of the repository**")
                .contains("Suggested narrower scope (from the finding's location): `paths: src/Foo.java`")
                .contains("FB31")
                .contains("Validation: no open issues")
                .contains("`@ai_bot learning activate L17@1`");
        verify(lessonService).markAcknowledgement(50L, FeedbackAcknowledgement.POSTED);
    }

    @Test
    void anActivationIsReportedAsMaintainerCuration() {
        when(lessonService.activate(any(), any(), eq("L17"), eq(1), eq("alex")))
                .thenReturn(recorded(LessonEventType.ACTIVATED, 1, false));
        when(lessonService.show(any(), eq("L17"))).thenReturn(Optional.of(detail(LessonState.ACTIVE, 1)));

        handle("@ai_bot learning activate L17@1", "alex");

        assertThat(reply()).contains("Lesson **L17@1** activated by alex as maintainer-curated guidance")
                .contains("not a measured improvement")
                .contains("`@ai_bot learning disable L17`");
    }

    @Test
    void aRedeliveredCommandIsNotConfirmedTwice() {
        when(lessonService.activate(any(), any(), any(), anyInt(), any()))
                .thenReturn(recorded(LessonEventType.ACTIVATED, 1, true));

        handle("@ai_bot learning activate L17@1", "alex");

        assertNoReply();
        verify(lessonService, never()).claimAcknowledgementRetry(any());
    }

    @Test
    void aRedeliveryRepostsARefusedConfirmationOnce() {
        when(lessonService.activate(any(), any(), any(), anyInt(), any()))
                .thenReturn(new KnowledgeLessonService.Recorded("L17", 1, LessonEventType.ACTIVATED,
                        true, 50L, FeedbackAcknowledgement.FAILED));
        when(lessonService.claimAcknowledgementRetry(50L)).thenReturn(true);
        when(lessonService.show(any(), eq("L17"))).thenReturn(Optional.of(detail(LessonState.ACTIVE, 1)));

        handle("@ai_bot learning activate L17@1", "alex");

        assertThat(reply()).contains("activated");
    }

    @Test
    void aRejectionIsExplainedToTheCommenter() {
        when(lessonService.activate(any(), any(), any(), anyInt(), any()))
                .thenThrow(new LessonRejectedException("obsolete-revision",
                        "L17@1 is obsolete: the current revision is L17@2."));

        handle("@ai_bot learning activate L17@1", "alex");

        assertThat(reply()).contains("L17@1 is obsolete: the current revision is L17@2.");
    }

    @Test
    void anInternalFailureIsNotEchoedIntoThePublicComment() {
        when(lessonService.activate(any(), any(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("SQL [update knowledge_lessons …] failed"));

        assertThat(handle("@ai_bot learning activate L17@1", "alex")).isTrue();

        assertThat(reply()).contains("could not be completed").doesNotContain("SQL");
    }

    @Test
    void aFailedReplyDoesNotFailTheCommandAndIsRecordedAsRefused() {
        when(lessonService.disable(any(), any(), any(), any(), any()))
                .thenReturn(recorded(LessonEventType.DISABLED, 1, false));
        when(lessonService.show(any(), eq("L17"))).thenReturn(Optional.of(detail(LessonState.DISABLED, 1)));
        doThrow(new org.springframework.web.client.HttpClientErrorException(
                org.springframework.http.HttpStatus.FORBIDDEN))
                .when(repositoryClient).postPullRequestComment(anyString(), anyString(), anyLong(),
                        anyString());

        assertThat(handle("@ai_bot learning disable L17", "alex")).isTrue();

        verify(lessonService).markAcknowledgement(50L, FeedbackAcknowledgement.FAILED);
    }

    @Test
    void aMalformedCommandIsConsumedWithHelp() {
        assertThat(handle("@ai_bot learning activate L17", "alex")).isTrue();

        verifyNoInteractions(lessonService);
        assertThat(reply()).contains("Name the exact revision");
    }

    @Test
    void anUnknownLessonIsReportedWithoutRevealingAnything() {
        when(lessonService.show(any(), eq("L99"))).thenReturn(Optional.empty());

        handle("@ai_bot learning show L99", "carol");

        assertThat(reply()).isEqualTo("No lesson L99 is available here.");
    }

    // ------------------------------------------------------------------ fixtures

    private void curated(String... maintainers) {
        lenient().when(settingsResolver.resolve(any(Bot.class))).thenReturn(
                new AgentReviewLearningSettings(true, AgentReviewLearningMode.CURATED,
                        Set.of(maintainers)));
    }

    private boolean handle(String body, String author) {
        return handler.tryHandle(bot, payload(body, author), PullRequestCommentKind.PR_COMMENT, null);
    }

    private String reply() {
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(repositoryClient, org.mockito.Mockito.atLeastOnce())
                .postPullRequestComment(eq("acme"), eq("web"), eq(9L), reply.capture());
        return reply.getValue();
    }

    private void assertNoReply() {
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(),
                anyString());
    }

    private static KnowledgeLessonService.Recorded recorded(LessonEventType type, int revision,
                                                            boolean duplicate) {
        return new KnowledgeLessonService.Recorded("L17", revision, type, duplicate, 50L,
                duplicate ? FeedbackAcknowledgement.POSTED : FeedbackAcknowledgement.PENDING);
    }

    private static KnowledgeLessonService.Detail detail(LessonState state, int revision) {
        Instant now = Instant.parse("2026-11-24T10:00:00Z");
        KnowledgeLessonService.EvidenceView evidence = new KnowledgeLessonService.EvidenceView(
                "FB31", "O1234-F2", FeedbackTargetType.FINDING, FeedbackVerdict.INCORRECT,
                FeedbackState.EFFECTIVE, null, "alex", 42L, "PR_COMMENT", "991");
        KnowledgeLessonService.RevisionView view = new KnowledgeLessonService.RevisionView(revision,
                state, TEXT, List.of(), "authorization", "0123456789abcdef", now.plusSeconds(86_400),
                "alex", now, state == LessonState.ACTIVE
                ? LessonActivationProvenance.MAINTAINER_CURATED : null,
                state == LessonState.ACTIVE ? "alex" : null, state == LessonState.ACTIVE ? now : null,
                null, null, List.of(evidence), 0);
        KnowledgeLessonService.Summary summary = new KnowledgeLessonService.Summary(5L, "L17", state,
                state == LessonState.ACTIVE ? revision : null, revision, view, null, false, null, 0,
                "acme", "web", new KnowledgeLessonService.Validation(List.of(), List.of()));
        return new KnowledgeLessonService.Detail(summary, List.of(view), List.of(),
                List.of("src/Foo.java"));
    }

    private static WebhookPayload payload(String body, String author) {
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
        WebhookPayload.Owner user = new WebhookPayload.Owner();
        user.setLogin(author);
        comment.setUser(user);
        payload.setComment(comment);
        return payload;
    }
}
