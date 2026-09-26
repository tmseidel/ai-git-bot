package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.loop.*;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.*;
import org.remus.giteabot.config.AgentConfigProperties;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewToolRoundLimitTest {
    @Test
    void countsToolBatchesAndAllowsFinalTextAfterTheLastBatch() {
        var router = mock(AgentToolRouter.class);
        when(router.execute(any(), any())).thenReturn(new ToolResult(true, 0, "read", ""));
        var strategy = new ReviewAgentStrategy("sys", router, new ToolCatalog(new AgentConfigProperties()),
                null, Set.of("cat"), null, null, null, 5, 1);
        var ctx = new AgentRunContext(null, "owner", "repo", 1L, null, "main");
        var tools = new ChatTurn("", List.of(new ToolCall("a", "cat", null), new ToolCall("b", "cat", null)),
                StopReason.TOOL_USE, 10, 10);
        assertThat(strategy.step(ctx, tools, 1)).isInstanceOf(StepDecision.ContinueWithToolResults.class);
        var finalText = (StepDecision.Finish) strategy.step(ctx, ChatTurn.text("Completed review"), 2);
        assertThat(finalText.outcome().success()).isTrue();
        var denied = (StepDecision.Finish) strategy.step(ctx, tools, 2);
        assertThat(denied.outcome().success()).isFalse();
        verify(router, times(2)).execute(any(), any());
    }
}
