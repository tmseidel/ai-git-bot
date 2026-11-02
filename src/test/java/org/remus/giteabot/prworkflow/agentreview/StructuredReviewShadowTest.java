package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.agent.session.AgentSessionRepository;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.*;
import org.remus.giteabot.ai.*;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.prworkflow.PrWorkflowRunService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestDetails;
import org.remus.giteabot.repository.model.PullRequestState;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StructuredReviewShadowTest {
    private static final Path WORKSPACE = Path.of("shadow-workspace");
    private static final String DIFF = "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new\n";
    private static final String COMPLETE = "{\"result\":\"COMPLETE\",\"summary\":\"Checked change\",\"complete\":true}";
    private final AiClient ai = mock(AiClient.class);
    private final RepositoryApiClient repository = mock(RepositoryApiClient.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final PrWorkflowRunService runs = mock(PrWorkflowRunService.class);
    private final EventHookPublisher events = mock(EventHookPublisher.class);
    private final AtomicReference<ReviewDraft> stored = new AtomicReference<>();
    private AgentReviewService service;
    private WebhookPayload payload;

    @BeforeEach
    void setup() {
        var owner = new WebhookPayload.Owner();
        owner.setLogin("owner");
        var repo = new WebhookPayload.Repository();
        repo.setOwner(owner);
        repo.setName("repo");
        var head = new WebhookPayload.Head();
        head.setRef("feature");
        var pr = new WebhookPayload.PullRequest();
        pr.setNumber(1L);
        pr.setTitle("Change");
        pr.setHead(head);
        payload = new WebhookPayload();
        payload.setRepository(repo);
        payload.setPullRequest(pr);
        when(repository.getPullRequestDetails("owner", "repo", 1L)).thenReturn(Optional.of(new PullRequestDetails(
                "Change", null, PullRequestState.OPEN, "feature", "a".repeat(40), "main", "b".repeat(40))));
        when(repository.getPullRequestDiff("owner", "repo", 1L)).thenReturn(DIFF);
        when(workspaces.preparePullRequestWorkspace(repository, "owner", "repo", "feature", 1L, PullRequestWorkspaceMode.READ_ONLY))
                .thenReturn(WorkspaceResult.success(WORKSPACE));
        when(workspaces.pinReviewRevision(WORKSPACE, "a".repeat(40))).thenReturn(true);
        when(workspaces.isReviewRevision(WORKSPACE, "a".repeat(40))).thenReturn(true);
        when(runs.isActive(1L)).thenReturn(true);
        doAnswer(call -> { stored.set(call.getArgument(3)); return null; }).when(runs)
                .saveReviewDraft(eq(1L), any(), anyLong(), any());
        doAnswer(call -> { stored.set(call.getArgument(3)); return null; }).when(runs)
                .sealReviewShadow(eq(1L), any(), anyLong(), any());
        when(runs.loadReviewDraft(eq(1L), any())).thenAnswer(call -> stored.get());
        when(ai.supportsNativeTools()).thenReturn(true);
        var config = new AgentConfigProperties();
        config.getBudget().setMaxRounds(4);
        var catalog = new ToolCatalog(config);
        service = new AgentReviewService(new AgentReviewContext(repository, ai, "Review safely", "bot",
                null, null, Set.of("pr-diff"), 200_000), new AgentSessionService(mock(AgentSessionRepository.class)),
                new ToolExecutionService(config, catalog, workspaces), catalog, workspaces, config, null,
                new Bot(), events, null, runs);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyASealedSnapshotFinishesAndNothingIsEverPublished(boolean nativeMode) {
        when(ai.supportsNativeTools()).thenReturn(nativeMode);
        String update = "{\"add\":[{\"severity\":\"blocker\",\"title\":\"SQL injection\",\"description\":\"Unsafe query construction\"}]}";
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(nativeMode ? call(ReviewFindingsLedger.TOOL, update)
                                : ChatTurn.text("{\"record_findings\":" + update + "}"),
                        nativeMode ? call(ReviewFindingsLedger.SUBMIT, COMPLETE)
                                : ChatTurn.text("{\"submit_review\":" + COMPLETE + "}"));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.SHADOW_RECORDED);
        assertThat(stored.get().status()).isEqualTo(ReviewDraft.Status.COMPLETE);
        assertThat(stored.get().render()).contains("F1 [BLOCKER] SQL injection", "Unsafe query construction");
        verify(runs).saveReviewDraft(eq(1L), any(), eq(0L), any());
        verify(runs).sealReviewShadow(eq(1L), any(), eq(1L), any());
        assertNoPublication();
    }

    @Test
    void anEmptyConfirmedRecordCannotApproveWithoutCoverage() {
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(call(ReviewFindingsLedger.SUBMIT, COMPLETE));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.SHADOW_RECORDED);
        assertThat(stored.get().findings()).isEmpty();
        assertNoPublication();
    }

    @Test
    void assistantProseAndTrailingCountsCannotCompleteOrOverrideTheRecord() {
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("BLOCKER: Missing ownership check. Do not merge.\n{\"blocker\":0}"));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(stored.get()).isNull();
        assertNoPublication();
    }

    @Test
    void twoTerminalTurnsRemainAvailableAfterExplorationIncludingARepair() {
        var calls = new AtomicInteger();
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt())).thenAnswer(call -> {
            int round = calls.incrementAndGet();
            List<ToolDescriptor> tools = call.getArgument(2);
            if (round <= 2) return call(ReviewFindingsLedger.TOOL, "{\"complete\":false}");
            assertThat(tools).extracting(ToolDescriptor::name).containsExactly(ReviewFindingsLedger.SUBMIT);
            if (round == 4) {
                List<AiMessage> history = call.getArgument(0);
                assertThat(history).anySatisfy(message -> assertThat(message.getToolResult())
                        .contains("Explicit complete:true is required"));
            }
            return call(ReviewFindingsLedger.SUBMIT, round == 3 ? "{\"result\":\"COMPLETE\",\"summary\":\"No confirmation\"}" : COMPLETE);
        });
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.SHADOW_RECORDED);
        assertThat(calls).hasValue(4);
        assertNoPublication();
    }

    @Test
    void aDatabaseFailureCannotBeMistakenForRepairableModelInput() {
        doThrow(new IllegalArgumentException("Persistence failed")).when(runs).sealReviewShadow(eq(1L), any(), anyLong(), any());
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(call(ReviewFindingsLedger.SUBMIT, COMPLETE));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(ai).chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        assertNoPublication();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAX_TOKENS", "OTHER"})
    void truncatedToolArgumentsCannotSealEvenWhenTheirJsonLooksComplete(String reason) {
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("", call(ReviewFindingsLedger.SUBMIT, COMPLETE).toolCalls(),
                        StopReason.valueOf(reason), 100, 20));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(runs, never()).sealReviewShadow(any(), any(), anyLong(), any());
        assertNoPublication();
    }

    @Test
    void insufficientContextKeepsTheDraftButDoesNotCompleteTheReview() {
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(call(ReviewFindingsLedger.SUBMIT, "{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"Need missing source\"}"));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertThat(stored.get().status()).isEqualTo(ReviewDraft.Status.INSUFFICIENT_CONTEXT);
        assertNoPublication();
    }

    @Test
    void aStaleDiffCannotBeSealedAsComplete() {
        when(repository.getPullRequestDiff("owner", "repo", 1L)).thenReturn(DIFF, DIFF + "changed");
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(call(ReviewFindingsLedger.SUBMIT, COMPLETE));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(runs, never()).sealReviewShadow(any(), any(), anyLong(), any());
        assertNoPublication();
    }

    @Test
    void reloadMustMatchTheDurablySealedSnapshot() {
        when(runs.loadReviewDraft(eq(1L), any())).thenReturn(new ReviewDraft(1, 1, ReviewDraft.Status.COMPLETE,
                1, true, "Different summary", List.of(), List.of(), null));
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(call(ReviewFindingsLedger.SUBMIT, COMPLETE));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        assertNoPublication();
    }

    @Test
    void legacyDuplicateFieldsAreRejectedBeforeSealing() {
        when(ai.supportsNativeTools()).thenReturn(false);
        when(ai.chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("{\"submit_review\":{\"result\":\"COMPLETE\",\"result\":\"COMPLETE\",\"summary\":\"x\",\"complete\":true}}"));
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(runs, never()).sealReviewShadow(any(), any(), anyLong(), any());
        assertNoPublication();
    }

    @Test
    void cancellationStopsInference() {
        when(runs.isActive(1L)).thenReturn(false);
        assertThat(run()).isEqualTo(AgentReviewService.ReviewResult.FAILED);
        verify(ai, never()).chatWithTools(anyList(), any(), anyList(), anyString(), isNull(), anyInt());
        assertNoPublication();
    }

    private AgentReviewService.ReviewResult run() { return service.reviewPullRequestShadow(payload, 1, 1L, null); }

    private static ChatTurn call(String tool, String json) {
        return new ChatTurn("Diagnostic text must never publish", List.of(new ToolCall("call", tool,
                AgentJackson.mapper().readTree(json))), StopReason.TOOL_USE, 100, 20);
    }

    private void assertNoPublication() {
        verify(repository, never()).postReview(anyString(), anyString(), anyLong(), anyString(), any());
        verify(repository, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
        verify(repository, never()).postPullRequestComment(anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(events);
        verify(workspaces).cleanupWorkspace(WORKSPACE);
    }
}
