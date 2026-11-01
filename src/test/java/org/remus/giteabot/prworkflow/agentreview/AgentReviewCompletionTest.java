package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.PullRequestWorkspaceMode;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.model.CommentId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentReviewCompletionTest {

    private static final Path WORKSPACE = Path.of("/tmp/agent-review-test");
    private static final String DIFF = """
            diff --git a/Example.java b/Example.java
            --- a/Example.java
            +++ b/Example.java
            @@ -1 +1 @@
            -old
            +new
            """;

    @Mock private AiClient aiClient;
    @Mock private RepositoryApiClient repositoryClient;
    @Mock private WorkspaceService workspaceService;
    @Mock private EventHookPublisher eventHooks;

    private AgentReviewService service;
    private WebhookPayload payload;
    private AgentConfigProperties config;

    @BeforeEach
    void setUp() {
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("owner");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("repo");
        repository.setOwner(owner);
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature");
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(1L);
        pullRequest.setTitle("Update example");
        pullRequest.setHead(head);
        payload = new WebhookPayload();
        payload.setRepository(repository);
        payload.setPullRequest(pullRequest);
        config = new AgentConfigProperties();
        config.getBudget().setMaxRounds(3);
        ToolCatalog catalog = new ToolCatalog(config);
        service = new AgentReviewService(
                new AgentReviewContext(repositoryClient, aiClient, "Review the change.",
                        null, null, Set.of("pr-diff"), 200_000),
                new AgentSessionService(mock(AgentSessionRepository.class)),
                new ToolExecutionService(config, catalog, workspaceService), catalog, workspaceService,
                config, null, new Bot(), eventHooks, null);
        lenient().when(aiClient.supportsNativeTools()).thenReturn(true);
        when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn(DIFF);
        lenient().when(workspaceService.preparePullRequestWorkspace(repositoryClient, "owner", "repo",
                        "feature", 1L, PullRequestWorkspaceMode.READ_ONLY))
                .thenReturn(WorkspaceResult.success(WORKSPACE));
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void emptyTurnDoesNotPublishAnEarlierExplorationMessage(StopReason reason) {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), new ChatTurn("", List.of(), reason, 78_886, 16_384));

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertNothingPublished();
    }

    @Test
    void updatesOneProgressCommentAndPublishesTheFinalReviewSeparately() {
        when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L), contains("Agentic Review Progress")))
                .thenReturn(CommentId.NumericCommentId.of(42L));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), ChatTurn.text("Review complete.\n{\"blocker\":2,\"medium\":0,\"low\":0}"));

        assertThat(service.reviewPullRequest(payload, 1, true, null,
                new AgentReviewService.SeverityThresholds(0, null, null), 1L, null, true))
                .isEqualTo(AgentReviewService.ReviewResult.POSTED);

        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("Preparing repository")));
        verify(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(CommentId.NumericCommentId.of(42L)),
                argThat(body -> body.contains("- Latest activity: Completed")
                        && body.contains("Rounds: 2") && body.contains("Inspection tool calls completed: 1")));
        verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("Review complete.")), eq(PostReviewAction.REQUEST_CHANGES));
    }

    @Test
    void failedReviewClosesItsProgressCommentWithoutPublishingAPartialReview() {
        when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L), contains("Agentic Review Progress")))
                .thenReturn(CommentId.NumericCommentId.of(42L));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("Partial finding", List.of(), StopReason.MAX_TOKENS, 10, 5));

        assertThat(service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, true))
                .isEqualTo(AgentReviewService.ReviewResult.FAILED);

        verify(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(CommentId.NumericCommentId.of(42L)),
                argThat(body -> body.contains("- Latest activity: Failed")));
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
    }

    @ParameterizedTest
    @CsvSource({"create,false", "activity,false", "final,false",
            "create,true", "activity,true", "final,true"})
    void progressFailuresDoNotChangeSuccessfulOutcome(String failedWrite, boolean clarification) {
        if (failedWrite.equals("create")) {
            when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L), contains("Agentic Review Progress")))
                    .thenThrow(new IllegalStateException("Comment creation denied"));
        } else {
            when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L), contains("Agentic Review Progress")))
                    .thenReturn(CommentId.NumericCommentId.of(42L));
            doAnswer(invocation -> {
                boolean terminal = invocation.<String>getArgument(4).contains("- Latest activity: Completed");
                if (terminal == failedWrite.equals("final")) {
                    throw new IllegalStateException("Comment update denied");
                }
                return null;
            }).when(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(CommentId.NumericCommentId.of(42L)), anyString());
        }
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("Review complete."));

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, true)
                : service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, true);
        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        if (clarification) {
            verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    argThat(body -> body.contains("Review complete.")));
            verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        } else {
            verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L), anyString(), eq(PostReviewAction.NONE));
            verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(), contains("## 🤖 Follow-up"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalProgressFailureDoesNotHideTheOriginalFailure(boolean clarification) {
        when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L), contains("Agentic Review Progress")))
                .thenReturn(CommentId.NumericCommentId.of(42L));
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("Model request failed"));
        doThrow(new IllegalStateException("Comment update denied")).when(repositoryClient)
                .updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(CommentId.NumericCommentId.of(42L)),
                        argThat(body -> body.contains("- Latest activity: Failed")));

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, true)
                : service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, true);
        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(CommentId.NumericCommentId.of(42L)),
                argThat(body -> body.contains("- Latest activity: Failed")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clarificationUsesTheSameOptionalProgressSetting(boolean enabled) {
        Bot bot = new Bot();
        GitIntegration git = new GitIntegration();
        git.setProviderType(RepositoryType.GITEA);
        bot.setGitIntegration(git);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(7L);
        bot.setWorkflowConfiguration(configuration);
        WorkflowSelectionService selections = mock(WorkflowSelectionService.class);
        when(selections.resolveParams(7L, "agentic-review"))
                .thenReturn(Map.of("enableProgressComments", enabled));
        AgentReviewServiceFactory factory = mock(AgentReviewServiceFactory.class);
        when(factory.create(bot)).thenReturn(service);
        if (enabled) {
            when(repositoryClient.postPullRequestComment(eq("owner"), eq("repo"), eq(1L), contains("Agentic Review Progress")))
                    .thenReturn(CommentId.NumericCommentId.of(42L));
        }
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), ChatTurn.text("Clarification answer."));
        PrWorkflowContext context = new PrWorkflowContext(bot, payload, 1L,
                (name, log) -> { }, () -> false,
                Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, "Why was this changed?"), null);

        assertThat(new AgentReviewWorkflow(factory, selections).run(context).status())
                .isEqualTo(WorkflowResultStatus.SUCCESS);
        verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("Clarification answer.")));
        if (enabled) {
            verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L), contains("Agentic Review Progress"));
            verify(repositoryClient).updatePullRequestComment(eq("owner"), eq("repo"), eq(1L), eq(CommentId.NumericCommentId.of(42L)),
                    argThat(body -> body.contains("- Latest activity: Completed") && body.contains("Rounds: 2")));
        } else {
            verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(), contains("Agentic Review Progress"));
            verify(repositoryClient, never()).updatePullRequestComment(anyString(), anyString(), anyLong(), any(CommentId.class), anyString());
        }
    }

    @Test
    void exhaustedBudgetDoesNotPublishExplorationText() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), exploration(2), exploration(3));

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertNothingPublished();
    }

    private void assertNothingPublished() {
        verify(repositoryClient, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repositoryClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(eventHooks);
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyIncompleteTurnCannotPublishReviewOrClarification(boolean clarification) {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        // A String projection loses the truncation signal; using it would publish this text.
        lenient().when(aiClient.chat(anyList(), any(), anyString(), isNull(), anyInt()))
                .thenReturn("A partial review");
        when(aiClient.chatWithTools(anyList(), any(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("A partial review", List.of(), StopReason.MAX_TOKENS, 100, 32));

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, false)
                : service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertNothingPublished();
        verify(aiClient, never()).chat(anyList(), any(), anyString(), isNull(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = StopReason.class, names = {"MAX_TOKENS", "OTHER"})
    void failedTurnCannotExecuteToolsOrPublishPartialText(StopReason reason) {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("A partial finding", exploration(1).toolCalls(), reason, 100, 32));
        List<AgentRunContext.ToolCallRecord> toolCalls = new ArrayList<>();

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, true, "Review criteria",
                new AgentReviewService.SeverityThresholds(0, 0, 0), 1L, toolCalls::add, false);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(toolCalls).isEmpty();
        assertNothingPublished();
    }

    @Test
    void failedClarificationDoesNotPublishItsDiagnosticPayload() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), new ChatTurn("", List.of(), StopReason.MAX_TOKENS, 100, 32));

        assertThat(service.answerClarification(payload, "Why was this changed?", 1, false))
                .isEqualTo(AgentReviewService.ReviewResult.FAILED);

        assertNothingPublished();
    }

    @Test
    void completedTurnPublishesOnlyTheFinalReview() {
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(exploration(1), ChatTurn.text("No correctness issues found."));

        AgentReviewService.ReviewResult reviewed = service.reviewPullRequest(payload, 1, false, null,
                new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false);

        assertThat(reviewed).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L),
                argThat(body -> body.contains("No correctness issues found.") && !body.contains("Let me check")),
                eq(PostReviewAction.NONE));
        verify(workspaceService).cleanupWorkspace(WORKSPACE);
        verify(repositoryClient, never()).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
        verify(repositoryClient, never()).updatePullRequestComment(anyString(), anyString(), anyLong(), any(CommentId.class), anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "20,true,false", "20,true,true", "60,true,false", "60,true,true",
            "20,false,false", "20,false,true", "60,false,false", "60,false,true"
    })
    void honorsSixtyExplorationRoundsAndRemindsOnRound58(int loopLimit,
            boolean nativeMode, boolean clarification) {
        config.getBudget().setMaxRounds(loopLimit);
        config.getBudget().setMaxContextRounds(60);
        when(aiClient.supportsNativeTools()).thenReturn(nativeMode);
        var reminderCounts = new ArrayList<Long>();
        when(aiClient.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenAnswer(invocation -> {
                    List<AiMessage> history = invocation.getArgument(0);
                    reminderCounts.add(history.stream().filter(message -> message.getContent() != null
                            && message.getContent().contains("Conclude your review now")).count());
                    int round = reminderCounts.size();
                    if (round == 60) {
                        return ChatTurn.text("Completed review");
                    }
                    return nativeMode ? exploration(round)
                            : ChatTurn.text("{\"requestTools\":[{\"tool\":\"pr-diff\",\"args\":[]}]}");
                });

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 60, false)
                : service.reviewPullRequest(payload, 60, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.POSTED);
        assertThat(reminderCounts).hasSize(60);
        assertThat(reminderCounts.subList(0, 57)).containsOnly(0L);
        assertThat(reminderCounts.subList(57, 60)).containsOnly(1L);
        if (clarification) {
            verify(repositoryClient).postPullRequestComment(eq("owner"), eq("repo"), eq(1L),
                    argThat(body -> body.contains("Completed review")));
        } else {
            verify(repositoryClient).postReview(eq("owner"), eq("repo"), eq(1L),
                    argThat(body -> body.contains("Completed review")), eq(PostReviewAction.NONE));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingDiffIsANoOpForReviewsAndClarifications(boolean clarification) {
        when(repositoryClient.getPullRequestDiff("owner", "repo", 1L)).thenReturn("");

        AgentReviewService.ReviewResult result = clarification
                ? service.answerClarification(payload, "Why was this changed?", 1, false)
                : service.reviewPullRequest(payload, 1, false, null,
                        new AgentReviewService.SeverityThresholds(null, null, null), 1L, null, false);

        assertThat(result).isEqualTo(AgentReviewService.ReviewResult.NO_DIFF);
        verifyNoInteractions(aiClient, workspaceService, eventHooks);
    }

    private ChatTurn exploration(int round) {
        return new ChatTurn("Let me check the remaining files...",
                List.of(new ToolCall("read-diff-" + round, "pr-diff",
                        AgentJackson.mapper().createObjectNode().put("path", "Example.java"))),
                StopReason.TOOL_USE, 100, 20);
    }
}
