package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link AgentLoop} correctly forwards native tool-call results
 * back to the AI provider as a properly-structured conversation history:
 * <ul>
 *   <li>The previous {@code assistant} turn carries its {@code tool_calls} so
 *       providers can correlate {@code tool_result} blocks by id (Anthropic +
 *       OpenAI reject mismatched ids).</li>
 *   <li>Each {@link StepDecision.ToolCallResult} becomes a separate
 *       {@code tool}-role {@link AiMessage} with the right {@code toolCallId}.</li>
 *   <li>No spurious empty user message is inserted between rounds when the
 *       strategy provides no follow-up text.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopNativeToolResultTest {

    @Mock private AiClient aiClient;
    @Mock private AgentSessionService sessionService;

    private AgentSession session;
    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        session = new AgentSession("owner", "repo", 11L, "issue title");
        ctx = new AgentRunContext(session, "owner", "repo", 11L, Path.of("/tmp/ws"), "main");
        when(sessionService.toAiMessages(session)).thenReturn(List.of());
    }

    @Test
    void continueWithToolResults_emitsAssistantWithToolCallsAndOneToolMessagePerResult() {
        when(aiClient.supportsNativeTools()).thenReturn(true);

        // Round 1: model emits two tool_calls.
        ToolCall call1 = new ToolCall("call_aaa", "cat", null);
        ToolCall call2 = new ToolCall("call_bbb", "rg",  null);
        ChatTurn round1 = new ChatTurn("inspecting…", List.of(call1, call2), StopReason.TOOL_USE);
        // Round 2: model returns final text and we Finish.
        ChatTurn round2 = new ChatTurn("done", List.of(), StopReason.END_TURN);

        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(round1, round2);

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(3, 2, 2, 4000));

        AtomicInteger callCount = new AtomicInteger();
        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public ToolingMode preferredToolMode() { return ToolingMode.NATIVE; }
            @Override public List<ToolDescriptor> toolDescriptors() {
                return List.of(new ToolDescriptor("cat", "read", null),
                        new ToolDescriptor("rg", "search", null));
            }
            @Override
            public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
                if (callCount.incrementAndGet() == 1) {
                    return new StepDecision.ContinueWithToolResults(List.of(
                            new StepDecision.ToolCallResult("call_aaa", "file content of cat"),
                            new StepDecision.ToolCallResult("call_bbb", "rg found 3 matches")
                    ), null);
                }
                return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), null));
            }
            @Override
            public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
                throw new AssertionError("text step should not be called in NATIVE mode");
            }
            @Override
            public LoopOutcome onBudgetExhausted(AgentRunContext ctx) {
                return LoopOutcome.fail(ctx.baseBranch());
            }
        };

        LoopOutcome outcome = loop.run(ctx, "go", strategy);

        assertThat(outcome.success()).isTrue();

        // Capture the history passed into round 2 — that's where the assistant+toolCalls and
        // the two tool-role messages from round 1 must show up.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiClient, times(2))
                .chatWithTools(historyCaptor.capture(), anyString(), anyList(), anyString(), isNull(), anyInt());

        List<AiMessage> round2History = historyCaptor.getAllValues().get(1);
        // Expect: user("go"), assistant(toolCalls=[call_aaa, call_bbb]), tool(call_aaa), tool(call_bbb)
        assertThat(round2History).hasSize(4);
        assertThat(round2History.get(0).getRole()).isEqualTo("user");
        assertThat(round2History.get(0).getContent()).isEqualTo("go");

        AiMessage assistant = round2History.get(1);
        assertThat(assistant.getRole()).isEqualTo("assistant");
        assertThat(assistant.getToolCalls()).extracting(ToolCall::id)
                .containsExactly("call_aaa", "call_bbb");

        AiMessage toolA = round2History.get(2);
        assertThat(toolA.getRole()).isEqualTo("tool");
        assertThat(toolA.getToolCallId()).isEqualTo("call_aaa");
        assertThat(toolA.getToolResult()).isEqualTo("file content of cat");

        AiMessage toolB = round2History.get(3);
        assertThat(toolB.getRole()).isEqualTo("tool");
        assertThat(toolB.getToolCallId()).isEqualTo("call_bbb");
        assertThat(toolB.getToolResult()).isEqualTo("rg found 3 matches");

        // Session log received the tool results too (textual mirror).
        verify(sessionService).addMessage(eq(session), eq("tool"),
                eq("[call_aaa] file content of cat"));
        verify(sessionService).addMessage(eq(session), eq("tool"),
                eq("[call_bbb] rg found 3 matches"));
    }
}

