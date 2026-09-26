package org.remus.giteabot.agent.issueimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link CodingAgentStrategy} that pin down the
 * three documented edge cases the previous {@code runToolImplementationLoop}
 * supported (Step 4B docs):
 * <ul>
 *     <li>Multi-round validation retry (legacy {@code attempt--}-style budget).</li>
 *     <li>{@code IGNORE_MCP_AFTER_VALIDATION_SUCCESS} policy.</li>
 *     <li>File-only success without a validation tool.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CodingAgentStrategyTest {

    @Mock private RepositoryApiClient repositoryClient;
    @Mock private AgentSessionService sessionService;
    @Mock private WorkspaceService workspaceService;
    @Mock private IssueNotificationService notificationService;
    @Mock private BranchSwitcher branchSwitcher;
    @Mock private AgentToolRouter toolRouter;

    private AgentConfigProperties agentConfig;
    private ToolCatalog toolCatalog;
    private AgentRunContext ctx;
    private AgentPromptBuilder promptBuilder;
    private AiResponseParser responseParser;

    @BeforeEach
    void setUp() {
        agentConfig = new AgentConfigProperties();
        agentConfig.getValidation().setEnabled(true);
        agentConfig.getBudget().setMaxValidationRetries(3);
        agentConfig.getValidation().setMaxToolExecutions(10);
        // Real catalog: "mvn" is validation (config default), "write-file" is FILE, etc.
        toolCatalog = new ToolCatalog(agentConfig);
        promptBuilder = new AgentPromptBuilder();
        responseParser = new AiResponseParser();
        AgentSession session = new AgentSession("o", "r", 1L, "t");
        ctx = new AgentRunContext(session, "o", "r", 1L, Path.of("/tmp/ws"), "main");
        // The native tool path consults the branch switcher on every round.
        lenient().when(branchSwitcher.apply(any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> new BranchSwitcher.Result("main", "main", inv.getArgument(2)));
    }

    private CodingAgentStrategy newStrategy() {
        return new CodingAgentStrategy("sys", promptBuilder, responseParser, notificationService,
                sessionService, branchSwitcher, toolRouter, toolCatalog,
                workspaceService, agentConfig, null, McpToolCatalog.empty(), null,
                (owner, repo, branch, files, tools, ws) -> "fetched-context");
    }

    private static ChatTurn textTurn(String text, StopReason reason) {
        return new ChatTurn(text, List.of(), reason, 0L, 0L);
    }

    /** A native round that writes a file and runs the project build. */
    private static ChatTurn nativeWriteAndValidateTurn() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        var writeArgs = nodes.objectNode();
        writeArgs.put("path", "src/X.java");
        writeArgs.put("content", "class X {}");
        var validationArgs = nodes.objectNode();
        validationArgs.set("args", nodes.arrayNode().add("compile"));
        return new ChatTurn("", List.of(
                new ToolCall("call-1", "write-file", writeArgs),
                new ToolCall("call-2", "mvn", validationArgs)),
                StopReason.TOOL_USE, 0L, 0L);
    }

    @Test
    void step_validationFailsThenSucceeds_continuesAndFinishesAfterRetry() {
        // Round 1: write-file + mvn — mvn fails. Round 2: write-file + mvn — mvn passes.
        String failing = """
                ```json
                {"summary":"v1","runTools":[
                    {"id":"a","tool":"write-file","args":["X.java","x"]},
                    {"id":"b","tool":"mvn","args":["compile"]}]}
                ```""";
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // write-file
                .thenReturn(new ToolResult(false, 1, "", "compile error")) // mvn fail
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // write-file
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", "")); // mvn pass
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);

        CodingAgentStrategy strategy = newStrategy();

        // Round 1 -> Continue (validation failed, attempt incremented to 2)
        StepDecision d1 = strategy.step(ctx, failing, 1);
        assertThat(d1).isInstanceOf(StepDecision.Continue.class);
        // Round 2 -> Finish success (validation passed, workspace has changes)
        StepDecision d2 = strategy.step(ctx, failing, 2);
        assertThat(d2).isInstanceOf(StepDecision.Finish.class);
        assertThat(((StepDecision.Finish) d2).outcome().success()).isTrue();
    }

    @Test
    void step_ignoreMcpAfterValidationSuccessPolicy_treatsMcpFailureAsSuccess() {
        agentConfig.getValidation().setNonValidationFailurePolicy(
                AgentConfigProperties.ValidationConfig.NonValidationFailurePolicy.IGNORE_MCP_AFTER_VALIDATION_SUCCESS);
        // Build a strategy with a McpOrchestrationService + catalog that knows "mcp-tool".
        var orchestration = mock(org.remus.giteabot.mcp.McpOrchestrationService.class);
        var catalog = new McpToolCatalog(List.of(
                new org.remus.giteabot.mcp.McpToolDefinition(
                        "s", "mcp-tool", "Mcp Tool", "d", java.util.Map.of(), "s.mcp-tool")));
        when(orchestration.isMcpTool(any(), eq("mcp-tool"))).thenReturn(true);
        lenient().when(orchestration.isMcpTool(any(), eq("mvn"))).thenReturn(false);
        lenient().when(orchestration.isMcpTool(any(), eq("write-file"))).thenReturn(false);
        CodingAgentStrategy strategy = new CodingAgentStrategy("sys", promptBuilder, responseParser,
                notificationService, sessionService, branchSwitcher, toolRouter,
                toolCatalog, workspaceService, agentConfig, orchestration, catalog, null,
                (a, b, c, d, e, f) -> "ctx");

        String response = """
                ```json
                {"summary":"x","runTools":[
                    {"id":"a","tool":"write-file","args":["F.java","x"]},
                    {"id":"b","tool":"mvn","args":["compile"]},
                    {"id":"c","tool":"mcp-tool","args":[]}]}
                ```""";
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // write-file
                .thenReturn(new ToolResult(true, 0, "ok", ""))   // mvn pass
                .thenReturn(new ToolResult(false, 1, "", "mcp boom")); // mcp fail
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);

        StepDecision d = strategy.step(ctx, response, 1);

        assertThat(d).isInstanceOf(StepDecision.Finish.class);
        assertThat(((StepDecision.Finish) d).outcome().success()).isTrue();
    }

    @Test
    void step_nativeTextOnlyTurnAfterWorkspaceChanges_finishesAsSuccess() {
        // Native models routinely end (or interleave) with a plain-language turn
        // that carries no tool_calls and is NOT a JSON plan. When the agent has
        // already produced workspace changes, such a turn means "I'm done" and
        // must finish successfully — not be fed to the JSON parser and hard-fail.
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);
        ctx.setToolingMode(org.remus.giteabot.agent.loop.ToolingMode.NATIVE);
        org.remus.giteabot.ai.ChatTurn textOnly = new org.remus.giteabot.ai.ChatTurn(
                "I've implemented the feature and the build passes.",
                List.of(), org.remus.giteabot.ai.StopReason.END_TURN, 0L, 0L);

        StepDecision d = newStrategy().step(ctx, textOnly, 1);

        assertThat(d).isInstanceOf(StepDecision.Finish.class);
        assertThat(((StepDecision.Finish) d).outcome().success()).isTrue();
    }

    @Test
    void step_nativeTextOnlyTurnWithoutChanges_nudgesOnceWithBothExits() {
        // A plain-language turn before any work is done (no tool_calls, no
        // workspace changes) must not fail the run, and must not loop either:
        // one nudge names both exits — call tools, or answer without tools.
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(false);
        ctx.setToolingMode(ToolingMode.NATIVE);

        StepDecision d = newStrategy().step(ctx,
                textTurn("Let me think about how to approach this.", StopReason.END_TURN), 1);

        assertThat(d).isInstanceOf(StepDecision.Continue.class);
        String nudge = ((StepDecision.Continue) d).nextUserMessage();
        assertThat(nudge).contains("call the tools");
        assertThat(nudge).contains("plain text");
        assertThat(nudge).contains("no pull request is opened");
        // The legacy JSON-envelope instruction must not leak into NATIVE mode.
        assertThat(nudge).doesNotContain("runTools");
    }

    @Test
    void step_nativeAnswerAfterNudge_finishesWithAnswerPayload() {
        // The reported bug: the model answers a read-only issue and the run must
        // end by publishing that answer instead of nudging until the round cap.
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(false);
        ctx.setToolingMode(ToolingMode.NATIVE);
        CodingAgentStrategy strategy = newStrategy();
        String answer = """
                The first 10 lines of docker-compose.yaml are:

                services:
                  ollama:
                    image: ollama/ollama
                """;

        assertThat(strategy.step(ctx, textTurn(answer, StopReason.END_TURN), 1))
                .isInstanceOf(StepDecision.Continue.class);

        StepDecision second = strategy.step(ctx, textTurn(answer, StopReason.END_TURN), 2);

        assertThat(second).isInstanceOf(StepDecision.Finish.class);
        LoopOutcome outcome = ((StepDecision.Finish) second).outcome();
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.payload()).isInstanceOf(LoopOutcome.AgentAnswer.class);
        assertThat(((LoopOutcome.AgentAnswer) outcome.payload()).text()).isEqualTo(answer.strip());
    }

    @Test
    void step_nativeAnswerAfterNudge_publishesThePostNudgeTurn() {
        // Only a turn that follows the nudge can be an answer: the nudge is what
        // offers that exit, so a pre-nudge turn is narration however it reads.
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(false);
        ctx.setToolingMode(ToolingMode.NATIVE);
        CodingAgentStrategy strategy = newStrategy();
        String narration = "Let me look into how the Docker setup works and then decide what to change.";

        assertThat(strategy.step(ctx, textTurn(narration, StopReason.END_TURN), 1))
                .isInstanceOf(StepDecision.Continue.class);

        StepDecision second = strategy.step(ctx,
                textTurn("Nothing to change in the repository.", StopReason.END_TURN), 2);

        assertThat(((LoopOutcome.AgentAnswer) ((StepDecision.Finish) second).outcome().payload()).text())
                .isEqualTo("Nothing to change in the repository.");
    }

    @Test
    void step_nativeNarrationFollowedByBlankTurn_failsWithoutPublishingTheNarration() {
        // The reviewer case: a substantial-looking pre-nudge turn must not become
        // the answer when the post-nudge turn is unusable — the run fails instead of
        // claiming the issue needs no change on the strength of earlier narration.
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(false);
        ctx.setToolingMode(ToolingMode.NATIVE);
        CodingAgentStrategy strategy = newStrategy();

        strategy.step(ctx, textTurn(
                "Let me look into how the Docker setup works and then decide what to change.",
                StopReason.END_TURN), 1);
        StepDecision second = strategy.step(ctx, textTurn("", StopReason.END_TURN), 2);

        assertThat(second).isInstanceOf(StepDecision.Finish.class);
        LoopOutcome outcome = ((StepDecision.Finish) second).outcome();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.payload()).isNull();
    }

    @Test
    void step_nativeTruncatedTurnAfterNudge_failsWithoutPublishingAnAnswer() {
        // A MAX_TOKENS turn is truncated, so it must never be posted as the answer.
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(false);
        ctx.setToolingMode(ToolingMode.NATIVE);
        CodingAgentStrategy strategy = newStrategy();

        strategy.step(ctx, textTurn("The first lines are version, services, ollama, image, ports", StopReason.MAX_TOKENS), 1);
        StepDecision second = strategy.step(ctx,
                textTurn("The first lines are version, services, ollama, image, ports", StopReason.MAX_TOKENS), 2);

        assertThat(second).isInstanceOf(StepDecision.Finish.class);
        LoopOutcome outcome = ((StepDecision.Finish) second).outcome();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.payload()).isNull();
    }


    @Test
    void step_proseTurnDoesNotConsumeTheToolRoundBudget() {
        // `attempt` is the validation retry budget. A prose turn used to increment
        // it, so with a single retry configured the next real tool round was
        // rejected by `attempt > maxRetries` before executing anything.
        agentConfig.getBudget().setMaxValidationRetries(1);
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(false, true);
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));
        ctx.setToolingMode(ToolingMode.NATIVE);
        CodingAgentStrategy strategy = newStrategy();

        assertThat(strategy.step(ctx, textTurn("Let me think about this first.", StopReason.END_TURN), 1))
                .isInstanceOf(StepDecision.Continue.class);

        StepDecision tools = strategy.step(ctx, nativeWriteAndValidateTurn(), 2);

        verify(toolRouter, times(2)).execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class));
        assertThat(((StepDecision.Finish) tools).outcome().success()).isTrue();
    }

    @Test
    void step_proseAfterAttemptedImplementationWithoutDiff_failsInsteadOfAnswering() {
        // An implementation attempt that leaves no diff must keep reporting failure:
        // answering "no changes needed" after trying to change files would hide it.
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(false);
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""));
        ctx.setToolingMode(ToolingMode.NATIVE);
        CodingAgentStrategy strategy = newStrategy();

        assertThat(strategy.step(ctx, nativeWriteAndValidateTurn(), 1))
                .isInstanceOf(StepDecision.ContinueWithToolResults.class);
        assertThat(strategy.step(ctx, textTurn("I am not sure how to proceed.", StopReason.END_TURN), 2))
                .isInstanceOf(StepDecision.Continue.class);

        StepDecision third = strategy.step(ctx, textTurn("Still nothing to change.", StopReason.END_TURN), 3);

        assertThat(third).isInstanceOf(StepDecision.Finish.class);
        LoopOutcome outcome = ((StepDecision.Finish) third).outcome();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.payload()).isNull();
    }

    @Test
    void step_fileOnlyResponseWithoutValidationTool_finishesAsSuccess() {
        String response = """
                ```json
                {"summary":"file-only","runTools":[
                    {"id":"a","tool":"write-file","args":["F.java","x"]}]}
                ```""";
        when(toolRouter.execute(eq(AgentToolRouter.Mode.CODING), any(ToolCallContext.class)))
                .thenReturn(new ToolResult(true, 0, "ok", ""));
        when(workspaceService.hasUncommittedChanges(any())).thenReturn(true);

        StepDecision d = newStrategy().step(ctx, response, 1);

        assertThat(d).isInstanceOf(StepDecision.Finish.class);
        assertThat(((StepDecision.Finish) d).outcome().success()).isTrue();
        verify(workspaceService).hasUncommittedChanges(any());
    }
}


