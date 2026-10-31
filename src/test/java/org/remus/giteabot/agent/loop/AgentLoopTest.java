package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.session.PendingMessage;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for {@link AgentLoop}: round budget, history/session
 * synchronisation, Continue vs Finish dispatch.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopTest {

    @Mock private AiClient aiClient;
    @Mock private AgentSessionService sessionService;

    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        AgentSession session = new AgentSession("owner", "repo", 42L, "title");
        session.setId(1L); // persisted session — the loop flushes id-bearing sessions
        ctx = new AgentRunContext(session, "owner", "repo", 42L, Workspace.unmanaged(Path.of("/tmp/ws")), "main");
        // lenient: the transient-session test below uses its own id-less session.
        lenient().when(sessionService.toAiMessages(session)).thenReturn(List.of());
    }

    @Test
    void run_strategyFinishesImmediately_singleAiCall() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), eq(8000)))
                .thenReturn(new ChatTurn("ai-final", List.of(), StopReason.END_TURN, 100, 7));

        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                assertThat(round).isEqualTo(1);
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), "payload"));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                throw new AssertionError("budget should not be exhausted");
            }
        };

        LoopOutcome outcome = loop.run(ctx, "go", strategy);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.payload()).isEqualTo("payload");
        verify(aiClient).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
        verify(aiClient, never()).chat(anyList(), anyString(), anyString(), isNull(), anyInt());
        // The round is flushed once with both the initial user message and the
        // assistant response, instead of one transaction per message.
        verify(sessionService).flushMessages(any(), eq(List.of(
                new PendingMessage("user", "go"),
                new PendingMessage("assistant", "ai-final"))), eq(100L), eq(7L));
    }

    @Test
    void run_transientSession_neverPersists() {
        // A session with no id (e.g. the read-only review agent) must never be
        // written to the database; the loop is purely an in-memory conversation.
        AgentSession transientSession = new AgentSession("owner", "repo", 42L, "title");
        AgentRunContext transientCtx = new AgentRunContext(
                transientSession, "owner", "repo", 42L, Workspace.unmanaged(Path.of("/tmp/ws")), "main");
        when(sessionService.toAiMessages(transientSession)).thenReturn(List.of());

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000, 8_000, 120_000, 200_000, 0.7));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("review-text"));

        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), r));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                throw new AssertionError("budget should not be exhausted");
            }
        };

        LoopOutcome outcome = loop.run(transientCtx, "go", strategy);

        assertThat(outcome.success()).isTrue();
        verify(sessionService, never()).flushMessages(any(), anyList(), anyLong(), anyLong());
    }

    @Test
    void run_strategyContinuesOnce_thenFinishes_synchronizesHistoryBetweenCalls() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        // Snapshot the history list passed to each call (Mockito captures a live reference).
        java.util.List<java.util.List<AiMessage>> historySnapshots = new java.util.ArrayList<>();
        java.util.List<String> userMessages = new java.util.ArrayList<>();
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    historySnapshots.add(java.util.List.copyOf(inv.getArgument(0)));
                    userMessages.add(inv.getArgument(1));
                    return ChatTurn.text(historySnapshots.size() == 1 ? "first-ai" : "second-ai");
                });

        AtomicInteger calls = new AtomicInteger();
        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                int n = calls.incrementAndGet();
                if (n == 1) return new StepDecision.Continue("follow-up-prompt");
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), null));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) { return LoopOutcome.fail(c.baseBranch()); }
        };

        loop.run(ctx, "kickoff", strategy);

        // First call: empty history, user="kickoff"
        assertThat(historySnapshots.get(0)).isEmpty();
        assertThat(userMessages.getFirst()).isEqualTo("kickoff");
        // Second call: history contains the kickoff/first-ai pair, user="follow-up-prompt"
        assertThat(historySnapshots.get(1)).hasSize(2);
        assertThat(historySnapshots.get(1).get(0).getRole()).isEqualTo("user");
        assertThat(historySnapshots.get(1).get(0).getContent()).isEqualTo("kickoff");
        assertThat(historySnapshots.get(1).get(1).getRole()).isEqualTo("assistant");
        assertThat(historySnapshots.get(1).get(1).getContent()).isEqualTo("first-ai");
        assertThat(userMessages.get(1)).isEqualTo("follow-up-prompt");

        // The first round is flushed as a single batch containing the kickoff
        // user message, the assistant turn, and the follow-up user prompt.
        verify(sessionService).flushMessages(any(), eq(List.of(
                new PendingMessage("user", "kickoff"),
                new PendingMessage("assistant", "first-ai"),
                new PendingMessage("user", "follow-up-prompt"))), anyLong(), anyLong());
    }

    @Test
    void run_publishesAgentLoopRoundDuringEachCall_andClearsItAfter() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        java.util.List<Integer> roundsDuringCalls = new java.util.ArrayList<>();
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    roundsDuringCalls.add(AiAuditContext.getRound());
                    return ChatTurn.text("ai-" + roundsDuringCalls.size());
                });

        AtomicInteger calls = new AtomicInteger();
        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                if (calls.incrementAndGet() == 1) return new StepDecision.Continue("follow-up");
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), null));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) { return LoopOutcome.fail(c.baseBranch()); }
        };

        loop.run(ctx, "kickoff", strategy);

        // The audit recorder reads the round from the thread-local while the call is
        // in flight; it must be gone once the loop stops calling the provider.
        assertThat(roundsDuringCalls).containsExactly(1, 2);
        assertThat(AiAuditContext.getRound()).isNull();
    }

    @Test
    void run_budgetExhausted_invokesStrategyHook_returnsItsOutcome() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(2, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("a"), ChatTurn.text("b"));

        AgentStrategy strategy = new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                return new StepDecision.Continue("again-" + round);
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                return LoopOutcome.fail("dead-branch");
            }
        };

        LoopOutcome outcome = loop.run(ctx, "go", strategy);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.selectedBranch()).isEqualTo("dead-branch");
        verify(aiClient, times(2)).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
    }

    @Test
    void run_brokenPipeDuringAiRequest_retriesOnceAndSucceeds() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        HttpMessageNotWritableException brokenPipe = new HttpMessageNotWritableException(
                "Could not write JSON", new SocketException("Broken pipe"));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(brokenPipe)
                .thenReturn(ChatTurn.text("ai-final"));

        LoopOutcome outcome = loop.run(ctx, "go", finishingStrategy());

        assertThat(outcome.success()).isTrue();
        verify(aiClient, times(2)).chatWithTools(anyList(), eq("go"), eq(List.of()), eq("sys"), isNull(), eq(8000));
    }

    @Test
    void run_nonNetworkSerializationFailure_doesNotRetry() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        HttpMessageNotWritableException serializationFailure =
                new HttpMessageNotWritableException("Could not write JSON");
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(serializationFailure);

        assertThatThrownBy(() -> loop.run(ctx, "go", finishingStrategy()))
                .isSameAs(serializationFailure);
        verify(aiClient).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "temporary failure", "maximum context length"})
    void run_http408_retriesOnceWithExistingHistory(String body) {
        List<AiMessage> history = List.of(
                AiMessage.builder().role("user").content("earlier question").build(),
                AiMessage.builder().role("assistant").content("earlier answer").build(),
                AiMessage.builder().role("user").content("inspect the change").build(),
                AiMessage.builder().role("assistant")
                        .toolCalls(List.of(new ToolCall("read_1", "cat", null))).build(),
                AiMessage.builder().role("tool").toolCallId("read_1")
                        .toolResult("existing file contents").build());
        when(sessionService.toAiMessages(ctx.session())).thenReturn(history);
        when(aiClient.supportsNativeTools()).thenReturn(true);
        lenient().when(aiClient.isPromptTooLongError(any())).thenCallRealMethod();
        HttpClientErrorException failure = new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT,
                "Request Timeout", body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        List<List<AiMessage>> attempts = new ArrayList<>();
        when(aiClient.chatWithTools(anyList(), eq("go"), anyList(), eq("sys"), isNull(), eq(8000)))
                .thenAnswer(invocation -> {
                    attempts.add(List.copyOf(invocation.getArgument(0)));
                    if (attempts.size() == 1) throw failure;
                    return ChatTurn.text("ai-final");
                });
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                // A prompt-length misclassification would compact this history.
                new AgentBudget(1, 3, 3, 8000, 8_000, 32, 200_000, 0.7));

        LoopOutcome outcome = loop.run(ctx, "go", new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public ToolingMode preferredToolMode() { return ToolingMode.NATIVE; }
            @Override public List<ToolDescriptor> toolDescriptors() {
                return List.of(new ToolDescriptor("cat", "read", null));
            }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                assertThat(round).isEqualTo(1);
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), r));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                throw new AssertionError("retry must not consume another round");
            }
        });

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.payload()).isEqualTo("ai-final");
        assertThat(attempts).containsExactly(history, history);
        verify(sessionService).flushMessages(any(), eq(List.of(
                new PendingMessage("user", "go"), new PendingMessage("assistant", "ai-final"))),
                anyLong(), anyLong());
    }

    @Test
    void run_http408FailsAgain_surfacesFailureAfterOneRetry() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000, 8_000, 120_000, 200_000, 0.7));
        HttpClientErrorException failure = new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT);
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(failure);

        assertThatThrownBy(() -> loop.run(ctx, "go", finishingStrategy())).isSameAs(failure);

        verify(aiClient, times(2)).chatWithTools(anyList(), eq("go"), eq(List.of()), eq("sys"), isNull(), eq(8000));
        verify(sessionService, never()).flushMessages(any(), anyList(), anyLong(), anyLong());
        assertThat(AiAuditContext.getRound()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 429})
    void run_otherHttpErrors_doesNotRetry(int status) {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000, 8_000, 120_000, 200_000, 0.7));
        HttpClientErrorException failure = new HttpClientErrorException(HttpStatus.valueOf(status));
        when(aiClient.chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt()))
                .thenThrow(failure);

        assertThatThrownBy(() -> loop.run(ctx, "go", finishingStrategy())).isSameAs(failure);

        verify(aiClient).chatWithTools(anyList(), anyString(), eq(List.of()), anyString(), isNull(), anyInt());
    }

    @Test
    void transientNetworkFailure_requiresKnownNetworkFailure() {
        assertThat(AgentLoop.isTransientNetworkFailure(
                new RuntimeException(new SocketException("Connection reset by peer")))).isTrue();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new RuntimeException(new SocketException("Permission denied")))).isFalse();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new RuntimeException("broken pipe"))).isFalse();
    }

    @Test
    void transientNetworkFailure_streamingTransportFailuresAreRetried() {
        // A mid-stream read timeout (per-chunk stall detector) and a malformed
        // stream line both surface as ResourceAccessException and must be
        // retried, matching the streaming clients' contract.
        assertThat(AgentLoop.isTransientNetworkFailure(
                new ResourceAccessException("I/O error on POST request to \"/api/chat\": Read timed out",
                        new java.net.SocketTimeoutException("Read timed out")))).isTrue();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new ResourceAccessException("Malformed Ollama stream line: Unexpected token"))).isTrue();
        assertThat(AgentLoop.isTransientNetworkFailure(
                new ResourceAccessException("I/O error on POST request to \"/completion\": Connection reset",
                        new java.net.SocketException("Connection reset")))).isTrue();
    }

    private static AgentStrategy finishingStrategy() {
        return new AgentStrategy() {
            @Override public String systemPrompt() { return "sys"; }
            @Override public StepDecision step(AgentRunContext c, String r, int round) {
                return new StepDecision.Finish(LoopOutcome.success(c.baseBranch(), r));
            }
            @Override public LoopOutcome onBudgetExhausted(AgentRunContext c) {
                throw new AssertionError("budget should not be exhausted");
            }
        };
    }
}
