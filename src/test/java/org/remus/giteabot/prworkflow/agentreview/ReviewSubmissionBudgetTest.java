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
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.structured.ReviewInvocation;
import org.remus.giteabot.review.structured.ReviewSubmissionToolExecutor;
import org.remus.giteabot.review.structured.ReviewToolCall;
import org.remus.giteabot.review.structured.StructuredReviewService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The submission budget bounds mutations, not finalization.
 *
 * <p>A review with five findings needs six mutating rounds (five findings and the assessment) before
 * the completion call. Charging completion against the same budget made that impossible, and failing
 * the run on exhaustion discarded findings that were already persisted — the reviewer received no
 * review at all. These tests pin both halves of the fix.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReviewSubmissionBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private ReviewSubmissionToolExecutor executor;
    @Mock private StructuredReviewService reviewService;
    @Mock private RepositoryApiClient repositoryClient;

    private final AgentRunContext context =
            AgentRunContext.forPullRequest(null, "owner", "repo", 7L, null, "main");

    @BeforeEach
    void setUp() {
        when(executor.isSubmissionTool(anyString())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            return ReviewSubmissionToolExecutor.UPSERT_FINDING.equals(tool)
                    || ReviewSubmissionToolExecutor.SET_ASSESSMENT.equals(tool)
                    || ReviewSubmissionToolExecutor.COMPLETE.equals(tool);
        });
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            String tool = ((ReviewToolCall) invocation.getArgument(1)).tool();
            return ReviewSubmissionToolExecutor.COMPLETE.equals(tool)
                    ? receipt("{\"ok\":true,\"finalized\":true,\"reference\":\"O42\"}")
                    : receipt("{\"ok\":true,\"ordinal\":1}");
        });
    }

    @Test
    void completionDoesNotConsumeTheSubmissionBudget() {
        ReviewAgentStrategy strategy = strategyWithBudget(2);

        assertThat(strategy.step(context, findingTurn("op-1", "finding-1"), 1))
                .isInstanceOf(StepDecision.ContinueWithToolResults.class);
        assertThat(strategy.step(context, findingTurn("op-2", "finding-2"), 2))
                .isInstanceOf(StepDecision.ContinueWithToolResults.class);

        var finished = (StepDecision.Finish) strategy.step(context, completionTurn(), 3);

        assertThat(finished.outcome().success()).isTrue();
        assertThat(finished.outcome().payload()).isEqualTo("O42");
        assertThat(strategy.submissionRoundsUsed()).isEqualTo(2);
    }

    @Test
    void theAssessmentIsAlsoFreeSoASpentBudgetCanStillFinalize() {
        ReviewAgentStrategy strategy = strategyWithBudget(1);
        strategy.step(context, findingTurn("op-1", "finding-1"), 1);

        var assessed = (StepDecision.ContinueWithToolResults) strategy.step(context,
                assessmentTurn(), 2);

        assertThat(assessed.results()).singleElement().satisfies(result ->
                assertThat(result.resultText()).contains("\"ok\":true"));
        assertThat(strategy.submissionRoundsUsed()).isEqualTo(1);

        var finished = (StepDecision.Finish) strategy.step(context, completionTurn(), 3);
        assertThat(finished.outcome().success()).isTrue();
    }

    @Test
    void anOverBudgetMutationIsRefusedWhileTheDraftStaysCompletable() {
        ReviewAgentStrategy strategy = strategyWithBudget(1);
        strategy.step(context, findingTurn("op-1", "finding-1"), 1);

        var refused = (StepDecision.ContinueWithToolResults) strategy.step(context,
                findingTurn("op-2", "finding-2"), 2);

        assertThat(refused.results()).singleElement().satisfies(result ->
                assertThat(result.resultText()).contains("Not executed"));
        // The over-budget finding never reached the executor, so nothing was lost or half-applied.
        verify(executor, times(1)).execute(any(), any());

        var finished = (StepDecision.Finish) strategy.step(context, completionTurn(), 3);
        assertThat(finished.outcome().success()).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private ReviewAgentStrategy strategyWithBudget(int maxSubmissionRounds) {
        ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());
        Set<String> allowed = Set.of("get-issue");
        AgentToolRouter router = new AgentToolRouter(null, catalog, null, null,
                McpToolCatalog.empty(), repositoryClient, allowed, mock(PythonExecutionService.class));
        ReviewInvocation invocation = new ReviewInvocation(1L, 2L, 3L, "owner", "repo", 7L, 1L,
                () -> false);
        return new ReviewAgentStrategy("sys", router, catalog, McpToolCatalog.empty(), allowed,
                null, null, null, 5, 5, maxSubmissionRounds,
                null, new ReviewAgentStrategy.Submission(executor, invocation, reviewService));
    }

    private static ChatTurn findingTurn(String operationId, String clientKey) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", operationId);
        arguments.put("clientKey", clientKey);
        arguments.put("severity", "LOW");
        arguments.put("category", "correctness");
        arguments.put("title", "Title");
        arguments.put("explanation", "Explanation.");
        arguments.put("impact", "Impact.");
        arguments.put("recommendation", "Recommendation.");
        return new ChatTurn("", List.of(new ToolCall("call-" + operationId,
                ReviewSubmissionToolExecutor.UPSERT_FINDING, arguments)), StopReason.TOOL_USE, 10, 10);
    }

    private static ChatTurn assessmentTurn() {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", "op-assessment");
        arguments.put("summary", "Summary.");
        arguments.put("conclusion", "FINDINGS_PRESENT");
        return new ChatTurn("", List.of(new ToolCall("call-assessment",
                ReviewSubmissionToolExecutor.SET_ASSESSMENT, arguments)), StopReason.TOOL_USE, 10, 10);
    }

    private static ChatTurn completionTurn() {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", "op-complete");
        return new ChatTurn("", List.of(new ToolCall("call-complete",
                ReviewSubmissionToolExecutor.COMPLETE, arguments)), StopReason.TOOL_USE, 10, 10);
    }

    private static ToolResult receipt(String json) {
        return new ToolResult(true, 0, json, "");
    }
}
