package org.remus.giteabot.issueworkflow.triage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TriageAgentStrategy}: the terminal-decision protocol
 * (native {@code assign_issue} tool call / legacy JSON), read-only context
 * gathering, and the bounded invalid-answer handling.
 */
@ExtendWith(MockitoExtension.class)
class TriageAgentStrategyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED =
            new LinkedHashSet<>(List.of("Alice", "Bob", "none"));

    @Mock
    private AgentToolRouter toolRouter;
    @Mock
    private BranchSwitcher branchSwitcher;

    private TriageAgentStrategy strategy;
    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        strategy = new TriageAgentStrategy("SYS", toolRouter,
                new ToolCatalog(new AgentConfigProperties()), McpToolCatalog.empty(), null,
                ALLOWED, "triage-bot", new AiResponseParser(), branchSwitcher, 2);
        ctx = new AgentRunContext(new AgentSession("owner", "repo", 42L, "Issue"),
                "owner", "repo", 42L, Workspace.unmanaged(Path.of("/tmp/ws")), "main");
        lenient().when(branchSwitcher.apply(any(), anyString(), anyList(), any()))
                .thenAnswer(inv -> new BranchSwitcher.Result("main", "main", inv.getArgument(2)));
    }

    @Test
    void toolDescriptors_advertiseReadOnlyToolsPlusAssignIssue() {
        List<ToolDescriptor> descriptors = strategy.toolDescriptors();

        List<String> names = descriptors.stream().map(ToolDescriptor::name).toList();
        assertTrue(names.contains("assign_issue"));
        assertTrue(names.contains("cat"));
        assertTrue(names.contains("rg"));
        assertTrue(names.contains("get-issue"));
        assertFalse(names.contains("write-file"));
        assertFalse(names.contains("mvn"));

        ToolDescriptor assignIssue = descriptors.stream()
                .filter(d -> "assign_issue".equals(d.name())).findFirst().orElseThrow();
        String schema = assignIssue.jsonSchema().toString();
        assertTrue(schema.contains("Alice"));
        assertTrue(schema.contains("none"));
    }

    @Test
    void native_validAssignIssue_finishesWithCanonicalDecision() {
        StepDecision decision = strategy.step(ctx,
                toolTurn("assign_issue", "{\"name\":\"alice\",\"reason\":\"Frontend styling fix\"}"), 1);

        LoopOutcome outcome = assertFinish(decision);
        assertTrue(outcome.success());
        RoutingDecision routing = assertInstanceOf(RoutingDecision.class, outcome.payload());
        assertEquals("Alice", routing.assignee());
        assertEquals("Frontend styling fix", routing.reason());
    }

    @Test
    void native_invalidAssignee_isFedBackAsToolResult_thenFailsAfterSecondInvalid() {
        StepDecision first = strategy.step(ctx,
                toolTurn("assign_issue", "{\"name\":\"Mallory\",\"reason\":\"Taking over\"}"), 1);

        StepDecision.ContinueWithToolResults cont = assertInstanceOf(
                StepDecision.ContinueWithToolResults.class, first);
        assertTrue(cont.results().getFirst().resultText().contains("unsupported assignment"));

        StepDecision second = strategy.step(ctx,
                toolTurn("assign_issue", "{\"name\":\"Mallory\",\"reason\":\"Still trying\"}"), 2);
        LoopOutcome outcome = assertFinish(second);
        assertFalse(outcome.success());
    }

    @Test
    void native_assignIssueMixedWithContextCalls_isRejected() {
        ChatTurn turn = new ChatTurn("", List.of(
                toolCall("rg", "{\"args\":[\"foo\"]}"),
                toolCall("assign_issue", "{\"name\":\"Alice\",\"reason\":\"x\"}")),
                StopReason.END_TURN, 0, 0);

        StepDecision decision = strategy.step(ctx, turn, 1);

        StepDecision.ContinueWithToolResults cont = assertInstanceOf(
                StepDecision.ContinueWithToolResults.class, decision);
        assertEquals(2, cont.results().size());
        assertTrue(cont.results().stream()
                .allMatch(r -> r.resultText().contains("must be the only tool call")));
    }

    @Test
    void native_contextCalls_areExecutedAndResultsFedBack() {
        when(toolRouter.execute(any(), any()))
                .thenReturn(new ToolResult(true, 0, "src/App.java: class App", ""));

        StepDecision decision = strategy.step(ctx,
                toolTurn("rg", "{\"args\":[\"class App\"]}"), 1);

        StepDecision.ContinueWithToolResults cont = assertInstanceOf(
                StepDecision.ContinueWithToolResults.class, decision);
        assertTrue(cont.results().getFirst().resultText().contains("src/App.java"));
    }

    @Test
    void native_textOnlyJsonAnswer_isAcceptedAsTerminalDecision() {
        ctx.setToolingMode(ToolingMode.NATIVE);
        StepDecision decision = strategy.step(ctx,
                ChatTurn.text("Here you go:\n{\"assignment\":\"none\",\"reason\":\"Unclear scope\"}"), 1);

        LoopOutcome outcome = assertFinish(decision);
        assertTrue(outcome.success());
        assertEquals("none", ((RoutingDecision) outcome.payload()).assignee());
    }

    @Test
    void native_textOnlyProse_isNudgedThenFails() {
        ctx.setToolingMode(ToolingMode.NATIVE);
        StepDecision first = strategy.step(ctx, ChatTurn.text("I think Alice should do it."), 1);
        assertInstanceOf(StepDecision.Continue.class, first);

        StepDecision second = strategy.step(ctx, ChatTurn.text("Still thinking about Alice."), 2);
        assertFalse(assertFinish(second).success());
    }

    @Test
    void legacy_contextRequest_executesToolsAndContinues() {
        when(toolRouter.execute(any(), any()))
                .thenReturn(new ToolResult(true, 0, "file content", ""));

        StepDecision decision = strategy.step(ctx,
                "{\"requestFiles\":[\"src/App.java\"]}", 1);

        StepDecision.Continue cont = assertInstanceOf(StepDecision.Continue.class, decision);
        assertTrue(cont.nextUserMessage().contains("file content"));
        assertTrue(cont.nextUserMessage().contains("Triage tool results"));
    }

    @Test
    void legacy_terminalJson_finishes() {
        StepDecision decision = strategy.step(ctx,
                "{\"assignment\":\"Bob\",\"reason\":\"Backend API change\"}", 1);

        LoopOutcome outcome = assertFinish(decision);
        assertTrue(outcome.success());
        assertEquals("Bob", ((RoutingDecision) outcome.payload()).assignee());
    }

    @Test
    void legacy_selfAssignment_isRejectedToPreventLoops() {
        TriageAgentStrategy selfAssignable = new TriageAgentStrategy("SYS", toolRouter,
                new ToolCatalog(new AgentConfigProperties()), McpToolCatalog.empty(), null,
                new LinkedHashSet<>(List.of("Alice", "triage-bot", "none")),
                "triage-bot", new AiResponseParser(), branchSwitcher, 2);

        StepDecision first = selfAssignable.step(ctx,
                "{\"assignment\":\"triage-bot\",\"reason\":\"I do it myself\"}", 1);
        assertInstanceOf(StepDecision.Continue.class, first);

        StepDecision second = selfAssignable.step(ctx,
                "{\"assignment\":\"triage-bot\",\"reason\":\"Really\"}", 2);
        assertFalse(assertFinish(second).success());
    }

    @Test
    void onBudgetExhausted_failsWithExplanation() {
        LoopOutcome outcome = strategy.onBudgetExhausted(ctx);

        assertFalse(outcome.success());
        assertTrue(String.valueOf(outcome.payload()).contains("round budget"));
    }

    private static LoopOutcome assertFinish(StepDecision decision) {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        return finish.outcome();
    }

    private static ChatTurn toolTurn(String toolName, String argsJson) {
        return new ChatTurn("", List.of(toolCall(toolName, argsJson)), StopReason.END_TURN, 0, 0);
    }

    private static ToolCall toolCall(String toolName, String argsJson) {
        JsonNode args;
        try {
            args = JSON.readTree(argsJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new ToolCall("call-" + toolName, toolName, args, Map.of());
    }
}
