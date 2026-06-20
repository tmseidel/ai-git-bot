package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 6 characterisation tests: {@link AgentLoop} picks the right transport
 * (native vs. legacy) based on strategy preference and client capability.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopToolModeTest {

    @Mock private AiClient aiClient;
    @Mock private AgentSessionService sessionService;

    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        AgentSession session = new AgentSession("owner", "repo", 7L, "title");
        ctx = new AgentRunContext(session, "owner", "repo", 7L, Path.of("/tmp/ws"), "main");
        when(sessionService.toAiMessages(session)).thenReturn(List.of());
    }

    @Test
    void run_nativeStrategyAndCapableClient_callsChatWithTools() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("done", List.of(), StopReason.END_TURN, 0L, 0L));

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(3, 2, 2, 4000,
                        8_000, 120_000,
                        200_000, 0.7));

        ToolDescriptor descriptor = new ToolDescriptor("ping", "ping the server", null);
        AgentStrategy strategy = nativeStrategy(List.of(descriptor));

        LoopOutcome outcome = loop.run(ctx, "go", strategy);

        assertThat(outcome.success()).isTrue();
        verify(aiClient, times(1))
                .chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt());
        verify(aiClient, never())
                .chat(anyList(), anyString(), anyString(), isNull(), anyInt());
    }

    @Test
    void run_nativeStrategyButLegacyClient_fallsBackToChat() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("plain text");

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(3, 2, 2, 4000,
                        8_000, 120_000,
                        200_000, 0.7));

        AgentStrategy strategy = nativeStrategy(List.of(
                new ToolDescriptor("ping", "ping", null)));

        loop.run(ctx, "go", strategy);

        verify(aiClient, never())
                .chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt());
        verify(aiClient, times(1))
                .chat(anyList(), anyString(), anyString(), isNull(), anyInt());
    }

    @Test
    void run_nativeStrategyWithoutDescriptors_fallsBackToChat() {
        // supportsNativeTools is irrelevant when descriptors are empty -> no need to stub it.
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("plain text");

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(3, 2, 2, 4000,
                        8_000, 120_000,
                        200_000, 0.7));

        AgentStrategy strategy = nativeStrategy(List.of());

        loop.run(ctx, "go", strategy);

        verify(aiClient, never())
                .chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt());
        verify(aiClient).chat(anyList(), anyString(), anyString(), isNull(), anyInt());
    }

    @Test
    void run_chatTurnWithToolCalls_isForwardedToStrategy() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        ToolCall call = new ToolCall("call_1", "ping", null);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("intermediate", List.of(call), StopReason.TOOL_USE, 0L, 0L));

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(3, 2, 2, 4000,
                        8_000, 120_000,
                        200_000, 0.7));

        java.util.concurrent.atomic.AtomicReference<ChatTurn> seen = new java.util.concurrent.atomic.AtomicReference<>();
        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String aiResponse, int round) {
                throw new AssertionError("text-step should not be called when ChatTurn override is supplied");
            }
            @Override public StepDecision step(AgentRunContext c, ChatTurn turn, int round) {
                seen.set(turn);
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), null));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) { return LoopOutcome.fail(c.baseBranch()); }
            @Override public ToolingMode preferredToolMode() { return ToolingMode.NATIVE; }
            @Override public List<ToolDescriptor> toolDescriptors() {
                return List.of(new ToolDescriptor("ping", "ping", null));
            }
        };

        loop.run(ctx, "go", strategy);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().assistantText()).isEqualTo("intermediate");
        assertThat(seen.get().toolCalls()).hasSize(1);
        assertThat(seen.get().toolCalls().getFirst().id()).isEqualTo("call_1");
    }

    private AgentStrategy nativeStrategy(List<ToolDescriptor> descriptors) {
        return new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), null));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                return LoopOutcome.fail(c.baseBranch());
            }
            @Override public ToolingMode preferredToolMode() { return ToolingMode.NATIVE; }
            @Override public List<ToolDescriptor> toolDescriptors() { return descriptors; }
        };
    }
}

