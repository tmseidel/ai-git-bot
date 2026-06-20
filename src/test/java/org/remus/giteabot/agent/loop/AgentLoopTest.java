package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.session.PendingMessage;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Characterization tests for {@link AgentLoop}: round budget, history/session
 * synchronisation, Continue vs Finish dispatch.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopTest {

    @Mock private AiClient aiClient;
    @Mock private AgentSessionService sessionService;

    private AgentSession session;
    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        session = new AgentSession("owner", "repo", 42L, "title");
        session.setId(1L); // persisted session — the loop flushes id-bearing sessions
        ctx = new AgentRunContext(session, "owner", "repo", 42L, Path.of("/tmp/ws"), "main");
        // lenient: the transient-session test below uses its own id-less session.
        lenient().when(sessionService.toAiMessages(session)).thenReturn(List.of());
    }

    @Test
    void run_strategyFinishesImmediately_singleAiCall() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), eq(8000)))
                .thenReturn("ai-final");

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
        verify(aiClient, times(1)).chat(anyList(), anyString(), anyString(), isNull(), anyInt());
        // The round is flushed once with both the initial user message and the
        // assistant response, instead of one transaction per message.
        verify(sessionService).flushMessages(any(), eq(List.of(
                new PendingMessage("user", "go"),
                new PendingMessage("assistant", "ai-final"))), anyLong(), anyLong());
    }

    @Test
    void run_transientSession_neverPersists() {
        // A session with no id (e.g. the read-only review agent) must never be
        // written to the database; the loop is purely an in-memory conversation.
        AgentSession transientSession = new AgentSession("owner", "repo", 42L, "title");
        AgentRunContext transientCtx = new AgentRunContext(
                transientSession, "owner", "repo", 42L, Path.of("/tmp/ws"), "main");
        when(sessionService.toAiMessages(transientSession)).thenReturn(List.of());

        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(5, 3, 3, 8000, 8_000, 120_000, 200_000, 0.7));
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("review-text");

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
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenAnswer(inv -> {
                    historySnapshots.add(java.util.List.copyOf(inv.getArgument(0)));
                    userMessages.add(inv.getArgument(1));
                    return historySnapshots.size() == 1 ? "first-ai" : "second-ai";
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
        assertThat(userMessages.get(0)).isEqualTo("kickoff");
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
    void run_budgetExhausted_invokesStrategyHook_returnsItsOutcome() {
        AgentLoop loop = new AgentLoop(aiClient, sessionService,
                new AgentBudget(2, 3, 3, 8000,
                        8_000, 120_000,
                        200_000, 0.7));
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("a", "b");

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
        verify(aiClient, times(2)).chat(anyList(), anyString(), anyString(), isNull(), anyInt());
    }
}


