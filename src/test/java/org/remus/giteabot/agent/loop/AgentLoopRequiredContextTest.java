package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ChatTurn;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopRequiredContextTest {
    @Test
    void usageFallbackCountsTheSameCompleteMessageThatWasSent() {
        var session = new AgentSession("o", "r", 1L, "PR");
        var sessions = mock(AgentSessionService.class);
        when(sessions.toAiMessages(session)).thenReturn(List.of());
        var ai = mock(AiClient.class);
        String required = "x".repeat(16_000);
        when(ai.chatWithTools(anyList(), eq("go\n\n" + required), anyList(), eq("sys"), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("done"));
        var strategy = new AgentStrategy() {
            public String systemPrompt() { return "sys"; }
            public String requiredContext() { return required; }
            public StepDecision step(AgentRunContext context, String text, int round) {
                return new StepDecision.Finish(LoopOutcome.success("main", text));
            }
            public LoopOutcome onBudgetExhausted(AgentRunContext context) { throw new AssertionError(); }
        };
        var loop = new AgentLoop(ai, sessions, new AgentBudget(1, 1, 1, 8_000, 8_000, 120_000, 200_000, 0.7));
        assertThat(loop.run(new AgentRunContext(session, "o", "r", 1L, Path.of("ws"), "main"), "go", strategy).success()).isTrue();
        assertThat(session.getTotalInputTokens()).isEqualTo(4_002);
        verify(sessions, never()).flushMessages(any(), anyList(), anyLong(), anyLong());
    }
}
