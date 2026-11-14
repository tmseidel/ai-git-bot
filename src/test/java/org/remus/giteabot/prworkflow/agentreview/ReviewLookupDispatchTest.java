package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.review.structured.PublishedReviewLookup;
import org.remus.giteabot.review.structured.ReviewLookupInvocation;
import org.remus.giteabot.review.structured.ReviewLookupToolExecutor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A follow-up has no conversation history, so resolving a cited reference has to be a tool call. This
 * pins that {@code review-lookup} reaches the lookup executor in that mode — where there is no
 * submission protocol at all — and that the repository router never sees it.
 */
class ReviewLookupDispatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PublishedReviewLookup stored = mock(PublishedReviewLookup.class);
    private final RepositoryApiClient repositoryClient = mock(RepositoryApiClient.class);
    private final AgentRunContext context =
            AgentRunContext.forPullRequest(null, "owner", "repo", 7L, null, "main");

    private ReviewAgentStrategy strategy;

    @BeforeEach
    void setUp() {
        ReviewLookupToolExecutor executor = new ReviewLookupToolExecutor(stored);
        ReviewLookupInvocation invocation = new ReviewLookupInvocation(1L, 2L, "owner", "repo", 7L,
                () -> false);
        ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());
        Set<String> allowed = Set.of("get-issue");
        AgentToolRouter router = new AgentToolRouter(null, catalog, null, null,
                McpToolCatalog.empty(), repositoryClient, allowed, mock(PythonExecutionService.class));
        strategy = new ReviewAgentStrategy("sys", router, catalog, McpToolCatalog.empty(), allowed,
                null, null, null, 5, 5, 5,
                new ReviewAgentStrategy.Lookup(executor, invocation), null);
    }

    @Test
    void theLookupToolIsAdvertisedEvenWithoutTheSubmissionProtocol() {
        List<String> names = strategy.toolDescriptors().stream().map(ToolDescriptor::name).toList();

        assertThat(names).contains("review-lookup");
        assertThat(names).doesNotContain("review-complete");
    }

    @Test
    void aReferenceCallIsServedFromTheStoredReviews() {
        when(stored.byReference(1L, 2L, "owner", "repo", 7L, "O12-F1"))
                .thenReturn("O12-F1 · MEDIUM · Stray title edit");

        StepDecision decision = strategy.step(context, lookupTurn("O12-F1", null), 1);

        assertThat(decision).isInstanceOfSatisfying(StepDecision.ContinueWithToolResults.class,
                results -> assertThat(results.results()).singleElement().satisfies(result ->
                        assertThat(result.resultText()).contains("O12-F1 · MEDIUM · Stray title edit")));
        verify(stored).byReference(1L, 2L, "owner", "repo", 7L, "O12-F1");
        verifyNoInteractions(repositoryClient);
    }

    @Test
    void aCallWithoutArgumentsListsWhatWasPublished() {
        when(stored.list(1L, 2L, "owner", "repo", 7L)).thenReturn("Review O12 · FINDINGS_PRESENT");

        StepDecision decision = strategy.step(context, lookupTurn(null, null), 1);

        assertThat(decision).isInstanceOfSatisfying(StepDecision.ContinueWithToolResults.class,
                results -> assertThat(results.results()).singleElement().satisfies(result ->
                        assertThat(result.resultText()).contains("Review O12 · FINDINGS_PRESENT")));
        verify(stored).list(1L, 2L, "owner", "repo", 7L);
    }

    @Test
    void aQuerySearchesThePublishedFindings() {
        when(stored.search(1L, 2L, "owner", "repo", 7L, "localStorage"))
                .thenReturn("- O12-F4 · LOW · Unguarded localStorage");

        strategy.step(context, lookupTurn(null, "localStorage"), 1);

        verify(stored).search(1L, 2L, "owner", "repo", 7L, "localStorage");
    }

    @Test
    void aLookupDoesNotConsumeTheExplorationRounds() {
        when(stored.list(1L, 2L, "owner", "repo", 7L)).thenReturn("Review O12");

        for (int round = 1; round <= 6; round++) {
            assertThat(strategy.step(context, lookupTurn(null, null), round))
                    .isInstanceOf(StepDecision.ContinueWithToolResults.class);
        }
        verify(stored, org.mockito.Mockito.times(6))
                .list(eq(1L), eq(2L), anyString(), anyString(), eq(7L));
    }

    private static ChatTurn lookupTurn(String reference, String query) {
        ObjectNode arguments = JSON.createObjectNode();
        if (reference != null) {
            arguments.put("reference", reference);
        }
        if (query != null) {
            arguments.put("query", query);
        }
        return new ChatTurn("", List.of(new ToolCall("call-lookup", ReviewLookupToolExecutor.LOOKUP,
                arguments)), StopReason.TOOL_USE, 10, 10);
    }
}
