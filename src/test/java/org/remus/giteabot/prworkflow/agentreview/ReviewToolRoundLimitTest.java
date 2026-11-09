package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewToolRoundLimitTest {

    @Mock
    private RepositoryApiClient repositoryClient;

    private final AgentRunContext context = AgentRunContext.forPullRequest(null, "owner", "repo", 1L, null, "main");

    @BeforeEach
    void setUp() {
        when(repositoryClient.getIssueDetails("owner", "repo", 1L))
                .thenReturn(Map.of("body", "read-only context"));
    }

    @Test
    void permitsTwoMultiToolBatchesThenRejectsAThirdWithoutFetchingMoreContext() {
        ReviewAgentStrategy strategy = strategy(2);

        assertToolResults(strategy.step(context, toolBatch("first-"), 1));
        assertToolResults(strategy.step(context, toolBatch("second-"), 2));
        clearInvocations(repositoryClient);

        var denied = (StepDecision.Finish) strategy.step(context, toolBatch("third-"), 3);

        assertThat(denied.outcome().success()).isFalse();
        verifyNoInteractions(repositoryClient);
    }

    @Test
    void acceptsFinalTextAfterTheLastPermittedMultiToolBatch() {
        ReviewAgentStrategy strategy = strategy(1);
        assertToolResults(strategy.step(context, toolBatch("last-"), 1));
        clearInvocations(repositoryClient);

        var finished = (StepDecision.Finish) strategy.step(context, ChatTurn.text("Completed review"), 2);

        assertThat(finished.outcome().success()).isTrue();
        assertThat(finished.outcome().payload()).isEqualTo("Completed review");
        verifyNoInteractions(repositoryClient);
    }

    private ReviewAgentStrategy strategy(int maxToolRounds) {
        ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());
        Set<String> allowed = Set.of("get-issue");
        AgentToolRouter router = new AgentToolRouter(null, catalog, null, null,
                McpToolCatalog.empty(), repositoryClient, allowed, mock(PythonExecutionService.class));
        return new ReviewAgentStrategy("sys", router, catalog, McpToolCatalog.empty(),
                allowed, null, null, null, 5, maxToolRounds);
    }

    private static ChatTurn toolBatch(String prefix) {
        return new ChatTurn("", List.of(new ToolCall(prefix + "a", "get-issue", null),
                new ToolCall(prefix + "b", "get-issue", null)), StopReason.TOOL_USE, 10, 10);
    }

    private static void assertToolResults(StepDecision decision) {
        assertThat(decision).isInstanceOfSatisfying(StepDecision.ContinueWithToolResults.class, results ->
                assertThat(results.results()).hasSize(2).allSatisfy(result ->
                        assertThat(result.resultText()).contains("read-only context")));
    }
}
