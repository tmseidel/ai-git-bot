package org.remus.giteabot.agent.writerimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Policy tests for {@link WriterAgentStrategy}'s round budget: {@code maxToolRounds}
 * repository-context rounds (R1), then exactly one wrap-up round that refuses tools
 * and hands the model the wrap-up instruction (R2), then the give-up comment (R3).
 *
 * <p>The wrap-up round is what makes a run that has used up its context budget
 * finish with an answer instead of discarding the model's work.</p>
 */
@ExtendWith(MockitoExtension.class)
class WriterAgentStrategyTest {

    private static final int MAX_TOOL_ROUNDS = 5;
    private static final Long ISSUE_NUMBER = 7L;

    @Mock private AgentSessionService sessionService;
    @Mock private RepositoryApiClient repositoryClient;
    @Mock private BranchSwitcher branchSwitcher;
    @Mock private AgentToolRouter toolRouter;
    @Mock private ToolCatalog catalog;
    @Mock private McpToolCatalog mcpToolCatalog;

    private AgentRunContext ctx;
    private WriterAgentStrategy strategy;

    @BeforeEach
    void setUp() {
        AgentSession session = new AgentSession("o", "r", ISSUE_NUMBER, "title");
        session.setId(1L);
        ctx = new AgentRunContext(session, "o", "r", ISSUE_NUMBER, Workspace.unmanaged(Path.of("/tmp/ws")), "main");
        ctx.setToolingMode(ToolingMode.NATIVE);
        strategy = new WriterAgentStrategy("sys", new WriterPromptBuilder(), new WriterResponseParser(),
                sessionService, repositoryClient, branchSwitcher, toolRouter, mcpToolCatalog,
                catalog, Set.of(), MAX_TOOL_ROUNDS);
    }

    @Test
    void contextRoundBelowTheBudget_executesTheCalls() {
        ToolCall call = new ToolCall("call_1", "cat", null);
        when(branchSwitcher.apply(any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> new BranchSwitcher.Result("main", "main", inv.getArgument(2)));
        when(toolRouter.execute(eq(AgentToolRouter.Mode.WRITER), any()))
                .thenReturn(new ToolResult(true, 0, "file body", ""));

        StepDecision decision = strategy.step(ctx, turn("reading the issue", call), MAX_TOOL_ROUNDS);

        StepDecision.ContinueWithToolResults continued = assertContinueWithResults(decision);
        assertThat(continued.results()).hasSize(1);
        assertThat(continued.results().getFirst().toolCallId()).isEqualTo("call_1");
        assertThat(continued.results().getFirst().resultText()).contains("file body");
        verify(toolRouter).execute(eq(AgentToolRouter.Mode.WRITER), any());
    }

    /**
     * The writer used to carry its own copy of the JSON-to-positional mapping, and that copy never
     * learned about {@code execute-code}'s {@code code} property: the program handed to the sandbox
     * was the argument object, which Python accepts as a dict literal — printed nothing, exited 0.
     * The mapping is shared now; this pins that the writer actually uses it.
     */
    @Test
    void aProgramReachesTheSandboxNotItsArgumentObject() {
        WriterAgentStrategy withRealCatalog = new WriterAgentStrategy("sys", new WriterPromptBuilder(),
                new WriterResponseParser(), sessionService, repositoryClient, branchSwitcher, toolRouter,
                mcpToolCatalog, new ToolCatalog(new AgentConfigProperties()), Set.of(), MAX_TOOL_ROUNDS);
        when(branchSwitcher.apply(any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> new BranchSwitcher.Result("main", "main", inv.getArgument(2)));
        when(toolRouter.execute(eq(AgentToolRouter.Mode.WRITER), any()))
                .thenReturn(new ToolResult(true, 0, "CHARS 11\nLINES 1\n", ""));

        withRealCatalog.step(ctx, turn("running a program", new ToolCall("call_1", "execute-code",
                AgentJackson.mapper().createObjectNode().put("code", "print('CHARS', 11)"))),
                MAX_TOOL_ROUNDS);

        ArgumentCaptor<ToolCallContext> executed = ArgumentCaptor.forClass(ToolCallContext.class);
        verify(toolRouter).execute(eq(AgentToolRouter.Mode.WRITER), executed.capture());
        assertThat(executed.getValue().args()).containsExactly("print('CHARS', 11)");
    }

    @Test
    void wrapUpRound_answersEveryCallWithoutExecutingIt() {
        ToolCall first = new ToolCall("call_1", "cat", null);
        ToolCall second = new ToolCall("call_2", "rg", null);

        StepDecision decision = strategy.step(ctx, turn("more context", first, second), MAX_TOOL_ROUNDS + 1);

        StepDecision.ContinueWithToolResults continued = assertContinueWithResults(decision);
        assertThat(continued.results()).extracting(StepDecision.ToolCallResult::toolCallId)
                .containsExactly("call_1", "call_2");
        assertThat(continued.results()).allSatisfy(result ->
                assertThat(result.resultText()).contains("not executed"));
        assertThat(continued.nextUserMessage())
                .contains("Context rounds exhausted")
                .contains("cannot call tools any more")
                .contains("revisedIssueDraft")
                .contains("clarifyingQuestions");
        verifyNoInteractions(toolRouter, branchSwitcher);
    }

    @Test
    void roundAfterTheWrapUp_postsTheNeedMoreContextCommentAndFinishesWithoutPostingASecondWrapUp() {
        // The wrap-up is offered once, in round maxToolRounds + 1 ...
        strategy.step(ctx, turn("more context", new ToolCall("call_1", "cat", null)), MAX_TOOL_ROUNDS + 1);
        // ... and a model that still insists on tools ends the run like before.
        StepDecision decision = strategy.step(ctx, turn("more context", new ToolCall("call_2", "cat", null)),
                MAX_TOOL_ROUNDS + 2);

        StepDecision.Finish finished = assertFinished(decision);
        assertThat(finished.outcome().success()).isTrue();
        verify(repositoryClient).postIssueComment(eq("o"), eq("r"), eq(ISSUE_NUMBER),
                contains("I need more context"));
        verifyNoInteractions(toolRouter);
    }

    @Test
    void legacyContextRequestAtTheWrapUpRound_isCarriedAsAFollowUp() {
        StepDecision decision = strategy.step(ctx, requestFilesAnswer(), MAX_TOOL_ROUNDS + 1);

        assertThat(assertContinued(decision).nextUserMessage()).contains("Context rounds exhausted");
        verifyNoInteractions(toolRouter);
    }

    @Test
    void legacyContextRequestAfterTheWrapUpRound_postsTheNeedMoreContextComment() {
        StepDecision decision = strategy.step(ctx, requestFilesAnswer(), MAX_TOOL_ROUNDS + 2);

        assertFinished(decision);
        verify(repositoryClient).postIssueComment(eq("o"), eq("r"), eq(ISSUE_NUMBER),
                contains("I need more context"));
    }

    @Test
    void clarifyingAnswerAtTheWrapUpRound_winsOverTheWrapUpInstruction() {
        StepDecision decision = strategy.step(ctx, ChatTurn.text(clarifyingAnswer()), MAX_TOOL_ROUNDS + 1);

        assertFinished(decision);
        verify(repositoryClient).postIssueComment(eq("o"), eq("r"), eq(ISSUE_NUMBER),
                contains("Which component owns the runtime?"));
        verify(repositoryClient, never()).createIssue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void finalAnswerAtTheWrapUpRound_createsTheImprovedIssue() {
        when(repositoryClient.createIssue(eq("o"), eq("r"), anyString(), anyString())).thenReturn(42L);

        StepDecision decision = strategy.step(ctx, ChatTurn.text(finalAnswer()), MAX_TOOL_ROUNDS + 1);

        assertFinished(decision);
        verify(repositoryClient).createIssue(eq("o"), eq("r"), contains("Issue"), contains("Revised body"));
        verify(sessionService).setGeneratedIssueNumber(ctx.session(), 42L);
    }

    @Test
    void narrationWithoutJson_isNudgedForTheContractOnce() {
        StepDecision decision = strategy.step(ctx, ChatTurn.text("Let me look around first."), 1);

        assertThat(assertContinued(decision).nextUserMessage()).contains("JSON object");
        verifyNoInteractions(repositoryClient, toolRouter);
    }

    private static ChatTurn turn(String text, ToolCall... calls) {
        return new ChatTurn(text, List.of(calls), StopReason.END_TURN, 0L, 0L);
    }

    private static String requestFilesAnswer() {
        return """
                {
                  "qualityAssessment": "needs the file",
                  "requestFiles": ["README.md"],
                  "readyToCreate": false
                }
                """;
    }

    private static String clarifyingAnswer() {
        return """
                {
                  "qualityAssessment": "one fact is missing",
                  "clarifyingQuestions": ["Which component owns the runtime?"],
                  "readyToCreate": false
                }
                """;
    }

    private static String finalAnswer() {
        return """
                {
                  "qualityAssessment": "clear enough",
                  "revisedIssueDraft": "Revised body",
                  "readyToCreate": true
                }
                """;
    }

    private static StepDecision.Continue assertContinued(StepDecision decision) {
        assertThat(decision).isInstanceOf(StepDecision.Continue.class);
        return (StepDecision.Continue) decision;
    }

    @Test
    void exhaustedBudget_postsTheNeedMoreContextComment() {
        // The loop cap used to end the run silently: the model narrated instead of
        // answering and the user saw nothing at all.
        LoopOutcome outcome = strategy.onBudgetExhausted(ctx);

        assertThat(outcome.success()).isTrue();
        verify(repositoryClient).postIssueComment(eq("o"), eq("r"), eq(ISSUE_NUMBER),
                contains("need more context"));
        verify(sessionService).setStatus(any(), eq(AgentSession.AgentSessionStatus.IN_PROGRESS));
    }

    private static StepDecision.ContinueWithToolResults assertContinueWithResults(StepDecision decision) {
        assertThat(decision).isInstanceOf(StepDecision.ContinueWithToolResults.class);
        return (StepDecision.ContinueWithToolResults) decision;
    }

    private static StepDecision.Finish assertFinished(StepDecision decision) {
        assertThat(decision).isInstanceOf(StepDecision.Finish.class);
        return (StepDecision.Finish) decision;
    }
}
