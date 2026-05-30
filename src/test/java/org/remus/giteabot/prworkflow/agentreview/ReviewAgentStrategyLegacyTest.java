package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAgentStrategyLegacyTest {

    @Mock
    private AgentToolRouter toolRouter;

    @Mock
    private ToolExecutionService toolExecutionService;

    private ReviewAgentStrategy strategy() {
        return new ReviewAgentStrategy(
                "system",
                toolRouter,
                null,                 // ToolCatalog unused on the legacy path
                null,                 // mcp catalog -> defaults to empty
                null,                 // no whitelist
                new AiResponseParser(),
                new BranchSwitcher(toolExecutionService),
                (owner, repo, ref, files) -> "FILE-CONTENT",
                5);
    }

    private AgentRunContext ctx() {
        return new AgentRunContext(null, "octo", "repo", 1L, Path.of("/tmp/ws"), "main");
    }

    @Test
    void legacy_contextRequest_executesToolsAndContinues() {
        when(toolRouter.execute(any(), any()))
                .thenReturn(new ToolResult(true, 0, "tool output", ""));

        String json = "{\"summary\":\"exploring\",\"requestTools\":[{\"tool\":\"cat\",\"args\":[\"README.md\"]}]}";
        StepDecision decision = strategy().step(ctx(), json, 1);

        // Read-only tool was executed and the loop continues to gather more context.
        verify(toolRouter, times(1)).execute(any(), any());
        StepDecision.Continue cont = assertInstanceOf(StepDecision.Continue.class, decision);
        assertTrue(cont.nextUserMessage().contains("requested repository context"));
        assertTrue(cont.nextUserMessage().contains("tool output"));
    }

    @Test
    void legacy_plainText_isTreatedAsFinalReview() {
        StepDecision decision = strategy().step(ctx(), "LGTM — the change looks correct.", 1);

        verify(toolRouter, never()).execute(any(), any());
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        assertTrue(finish.outcome().success());
        assertEquals("LGTM — the change looks correct.", finish.outcome().payload());
    }
}

