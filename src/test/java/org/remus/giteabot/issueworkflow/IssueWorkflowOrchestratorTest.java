package org.remus.giteabot.issueworkflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.ai.AiRetryContext;
import org.remus.giteabot.eventhook.EventHookEventType;
import org.remus.giteabot.eventhook.EventHookPublisher;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.notification.IssueCommentAcknowledgement;
import org.remus.giteabot.notification.WorkflowRetryNotices;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueWorkflowOrchestratorTest {

    @Mock private WorkflowSelectionService workflowSelectionService;
    @Mock private BotService botService;
    @Mock private EventHookPublisher eventHookPublisher;
    @Mock private WorkflowRetryNotices retryNotices;
    @Mock private IssueCommentAcknowledgement commentAcknowledgement;

    private IssueWorkflowOrchestrator orchestrator;
    private RecordingWorkflow recording;
    private Bot bot;

    /** Fake workflow that records the contexts it was called with. */
    private static class RecordingWorkflow implements IssueWorkflow {
        final List<IssueWorkflowContext> assigned = new ArrayList<>();
        final List<IssueWorkflowContext> comments = new ArrayList<>();
        RuntimeException failOnAssigned;

        @Override public String key() { return "issue-x"; }
        @Override public String displayName() { return "X"; }

        @Override
        public void onIssueAssigned(IssueWorkflowContext context) {
            assigned.add(context);
            if (failOnAssigned != null) {
                throw failOnAssigned;
            }
        }

        @Override
        public void onIssueComment(IssueWorkflowContext context) {
            comments.add(context);
        }
    }

    @BeforeEach
    void setUp() {
        recording = new RecordingWorkflow();
        orchestrator = new IssueWorkflowOrchestrator(
                new IssueWorkflowRegistry(List.of(recording)),
                workflowSelectionService, botService, eventHookPublisher,
                retryNotices, commentAcknowledgement);
        bot = new Bot();
        bot.setName("test-bot");
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(5L);
        configuration.setName("issue-cfg");
        bot.setIssueWorkflowConfiguration(configuration);
        lenient().when(workflowSelectionService.enabledWorkflowKeys(5L))
                .thenReturn(List.of("issue-x"));
        lenient().when(workflowSelectionService.resolveParams(5L, "issue-x"))
                .thenReturn(Map.of("k", "v"));
    }

    @AfterEach
    void clearRetryContext() {
        AiRetryContext.clear();
    }

    private static WebhookPayload issuePayload() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("my-repo");
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("Test");
        repository.setOwner(owner);
        payload.setRepository(repository);
        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(12L);
        issue.setTitle("Vague issue");
        payload.setIssue(issue);
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(77L);
        payload.setComment(comment);
        return payload;
    }

    @Test
    void runAssigned_invokesWorkflowWithContext_andPublishesStartedThenCompleted() {
        WebhookPayload payload = issuePayload();

        orchestrator.runAssigned(bot, payload);

        assertEquals(1, recording.assigned.size());
        IssueWorkflowContext context = recording.assigned.getFirst();
        assertSame(bot, context.bot());
        assertSame(payload, context.payload());
        assertEquals("v", context.params().get("k"));

        var order = inOrder(eventHookPublisher);
        order.verify(eventHookPublisher).publish(
                eq(EventHookEventType.ISSUE_ASSIGNMENT_STARTED),
                eq(bot), eq("Test"), eq("my-repo"), isNull(), eq(12L),
                argThat(data -> Long.valueOf(12L).equals(data.get("issueNumber"))
                        && "Vague issue".equals(data.get("issueTitle"))));
        order.verify(eventHookPublisher).publish(
                eq(EventHookEventType.ISSUE_ASSIGNMENT_COMPLETED),
                eq(bot), eq("Test"), eq("my-repo"), isNull(), eq(12L),
                argThat(data -> !data.containsKey("error")));
    }

    @Test
    void runAssigned_workflowThrows_recordsErrorAndPublishesFailed() {
        recording.failOnAssigned = new RuntimeException("boom");
        WebhookPayload payload = issuePayload();

        orchestrator.runAssigned(bot, payload);

        verify(botService).recordError(bot, "boom");
        verify(eventHookPublisher).publish(
                eq(EventHookEventType.ISSUE_ASSIGNMENT_FAILED),
                eq(bot), eq("Test"), eq("my-repo"), isNull(), eq(12L),
                argThat(data -> "boom".equals(data.get("error"))));
        verify(eventHookPublisher, never()).publish(
                eq(EventHookEventType.ISSUE_ASSIGNMENT_COMPLETED),
                any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void runAssigned_nullConfiguration_isSilentNoOp() {
        bot.setIssueWorkflowConfiguration(null);

        orchestrator.runAssigned(bot, issuePayload());

        assertEquals(0, recording.assigned.size());
        verify(eventHookPublisher, never()).publish(any(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void runAssigned_emptyKeys_isSilentNoOp() {
        when(workflowSelectionService.enabledWorkflowKeys(5L)).thenReturn(List.of());

        orchestrator.runAssigned(bot, issuePayload());

        assertEquals(0, recording.assigned.size());
    }

    @Test
    void runAssigned_unregisteredKey_isSkipped() {
        when(workflowSelectionService.enabledWorkflowKeys(5L))
                .thenReturn(List.of("issue-ghost"));

        orchestrator.runAssigned(bot, issuePayload());

        assertEquals(0, recording.assigned.size());
    }

    @Test
    void runComment_invokesWorkflow_andPublishesNoEvents() {
        WebhookPayload payload = issuePayload();

        orchestrator.runComment(bot, payload);

        assertEquals(1, recording.comments.size());
        assertSame(bot, recording.comments.getFirst().bot());
        verify(eventHookPublisher, never()).publish(any(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void runComment_acknowledgesTheTriggeringComment() {
        WebhookPayload payload = issuePayload();

        orchestrator.runComment(bot, payload);

        verify(commentAcknowledgement).acknowledge(bot, payload);
    }

    @Test
    void runComment_noWorkflowsResolved_noAcknowledgement() {
        when(workflowSelectionService.enabledWorkflowKeys(5L)).thenReturn(List.of());

        orchestrator.runComment(bot, issuePayload());

        verify(commentAcknowledgement, never()).acknowledge(any(), any());
    }

    @Test
    void runComment_workflowThrows_recordsErrorOnly() {
        RecordingWorkflow failing = new RecordingWorkflow() {
            @Override public void onIssueComment(IssueWorkflowContext context) {
                throw new RuntimeException("comment boom");
            }
        };
        orchestrator = new IssueWorkflowOrchestrator(
                new IssueWorkflowRegistry(List.of(failing)),
                workflowSelectionService, botService, eventHookPublisher,
                retryNotices, commentAcknowledgement);

        orchestrator.runComment(bot, issuePayload());

        verify(botService).recordError(bot, "comment boom");
        verify(eventHookPublisher, never()).publish(any(), any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void runComment_pointsRetryNoticesAtTheIssueAndClearsThemAfterwards() {
        doAnswer(invocation -> {
            AiRetryContext.install(new AiRetryContext.Notice("issue-x", body -> { }));
            return null;
        }).when(retryNotices).installForIssue(any(), any(), any(), any(), any());

        orchestrator.runComment(bot, issuePayload());

        verify(retryNotices).installForIssue(bot, "issue-x", "Test", "my-repo", 12L);
        assertNull(AiRetryContext.notice(), "retry-notice context must not leak into the next task");
    }

    @Test
    void runComment_noticeInstallationFailure_stillClearsTheRetryContext() {
        doAnswer(invocation -> {
            AiRetryContext.install(new AiRetryContext.Notice("issue-x", body -> { }));
            throw new IllegalStateException("notice target blew up");
        }).when(retryNotices).installForIssue(any(), any(), any(), any(), any());

        orchestrator.runComment(bot, issuePayload());

        verify(botService).recordError(bot, "notice target blew up");
        assertNull(AiRetryContext.notice(), "retry-notice context must not leak into the next task");
    }

    @Test
    void runAssigned_pointsRetryNoticesAtTheIssue() {
        orchestrator.runAssigned(bot, issuePayload());

        verify(retryNotices).installForIssue(bot, "issue-x", "Test", "my-repo", 12L);
    }

    @Test
    void runAssigned_payloadWithoutIssueIdentity_forwardsIncompleteTarget() {
        WebhookPayload payload = issuePayload();
        payload.setIssue(null);

        orchestrator.runAssigned(bot, payload);

        assertEquals(1, recording.assigned.size());
        verify(retryNotices).installForIssue(bot, "issue-x", "Test", "my-repo", null);
    }
}
