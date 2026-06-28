package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowOrchestrator;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the agentic-review slash command handler. The handler must:
 * <ul>
 *   <li>recognise {@code @bot clarify <question>} (case-insensitive) and dispatch
 *       a new {@code agentic-review} workflow run via {@link PrWorkflowOrchestrator};</li>
 *   <li>recognise freeform {@code @bot <text>} as a fallback when no other handler
 *       matched and agentic-review is enabled;</li>
 *   <li>ignore any other comment so the regular code-review path keeps working;</li>
 *   <li>refuse to dispatch when the bot's workflow configuration does not
 *       enable {@code agentic-review}.</li>
 * </ul>
 */
class AgentReviewSlashCommandHandlerTest {

    private PrWorkflowOrchestrator orchestrator;
    private WorkflowSelectionService selectionService;
    private GiteaClientFactory giteaClientFactory;
    private RepositoryApiClient repoClient;
    private AgentReviewSlashCommandHandler handler;

    private Bot bot;
    private WorkflowConfiguration workflowConfig;

    @BeforeEach
    void setUp() {
        orchestrator = mock(PrWorkflowOrchestrator.class);
        selectionService = mock(WorkflowSelectionService.class);
        giteaClientFactory = mock(GiteaClientFactory.class);
        repoClient = mock(RepositoryApiClient.class);
        when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
        handler = new AgentReviewSlashCommandHandler(orchestrator, selectionService, giteaClientFactory);

        workflowConfig = new WorkflowConfiguration();
        workflowConfig.setId(7L);
        workflowConfig.setName("Review Bot Config");

        bot = new Bot();
        bot.setId(99L);
        bot.setName("ai-bot");
        bot.setWorkflowConfiguration(workflowConfig);

        when(selectionService.enabledWorkflowKeys(7L))
                .thenReturn(List.of("review", "agentic-review"));
    }

    @Test
    void dispatchesOnClarifyCommand() {
        WebhookPayload payload = payloadWithComment("@ai-bot clarify Why did you flag this?");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), key.capture(), hints.capture());
        assertThat(key.getValue()).isEqualTo(AgentReviewWorkflow.KEY);
        assertThat(hints.getValue())
                .containsEntry(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION,
                        "Why did you flag this?");
    }

    @Test
    void dispatchesOnClarifyNoTrailingText() {
        // @bot clarify with nothing after (question is empty string)
        WebhookPayload payload = payloadWithComment("@ai-bot clarify");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(AgentReviewWorkflow.KEY), hints.capture());
        assertThat(hints.getValue())
                .containsEntry(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, "");
    }

    @Test
    void dispatchesOnClarifyCaseInsensitive() {
        WebhookPayload payload = payloadWithComment("@ai-bot CLARIFY Is this safe?");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(AgentReviewWorkflow.KEY), hints.capture());
        assertThat(hints.getValue())
                .containsEntry(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION,
                        "Is this safe?");
    }

    @Test
    void dispatchesFallbackOnFreeformMention() {
        // @bot <text> without "clarify" — fallback catch-all
        WebhookPayload payload = payloadWithComment("@ai-bot what about the error handling?");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(AgentReviewWorkflow.KEY), hints.capture());
        assertThat(hints.getValue())
                .containsEntry(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION,
                        "what about the error handling?");
    }

    @Test
    void doesNotDispatchFallbackOnEmptyMention() {
        // @bot with nothing after it — fallback catches but question is blank
        WebhookPayload payload = payloadWithComment("@ai-bot");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void ignoresUnrelatedComment() {
        WebhookPayload payload = payloadWithComment("No mention here");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void doesNotDispatchWhenAgenticReviewDisabled() {
        when(selectionService.enabledWorkflowKeys(7L)).thenReturn(List.of("review"));
        WebhookPayload payload = payloadWithComment("@ai-bot clarify Why?");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void ignoresWhenBotHasNoWorkflowConfiguration() {
        bot.setWorkflowConfiguration(null);
        WebhookPayload payload = payloadWithComment("@ai-bot clarify Why?");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void ignoresPayloadWithoutComment() {
        WebhookPayload payload = new WebhookPayload();

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void addsEyesReactionWhenSlashCommandRecognised() {
        WebhookPayload payload = payloadWithCommentAndIdentity(
                "@ai-bot clarify Why?", 4242L, "acme", "web");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        verify(repoClient).addReaction("acme", "web", 4242L, "eyes");
    }

    @Test
    void doesNotReactWhenAgenticReviewDisabled() {
        when(selectionService.enabledWorkflowKeys(7L)).thenReturn(List.of("review"));
        WebhookPayload payload = payloadWithCommentAndIdentity(
                "@ai-bot clarify Why?", 99L, "acme", "web");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(repoClient, never()).addReaction(any(), any(), any(), any());
    }

    @Test
    void doesNotReactOnUnrelatedComment() {
        WebhookPayload payload = payloadWithCommentAndIdentity(
                "No mention here", 100L, "acme", "web");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(repoClient, never()).addReaction(any(), any(), any(), any());
    }

    @Test
    void reactionFailureDoesNotBlockDispatch() {
        WebhookPayload payload = payloadWithCommentAndIdentity(
                "@ai-bot clarify Why?", 7L, "acme", "web");
        org.mockito.Mockito.doThrow(new RuntimeException("API down"))
                .when(repoClient).addReaction(any(), any(), any(), any());

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        verify(orchestrator).run(eq(bot), eq(payload), eq(AgentReviewWorkflow.KEY), any());
    }

    @Test
    void dispatchesFallbackOnNonClarifyFreeformMention() {
        // "review again" without the "clarify" keyword — still caught by fallback
        WebhookPayload payload = payloadWithComment("@ai-bot review again please");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(AgentReviewWorkflow.KEY), hints.capture());
        assertThat(hints.getValue())
                .containsEntry(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION,
                        "review again please");
    }

    @Test
    void botMentionAtStartOfCommentMatches() {
        WebhookPayload payload = payloadWithComment("@ai-bot clarify Is this a bug?");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
    }

    @Test
    void botMentionInMiddleOfCommentMatches() {
        WebhookPayload payload = payloadWithComment("Thanks for the review! @ai-bot clarify Why is this unsafe?");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
    }

    private WebhookPayload payloadWithComment(String body) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setBody(body);
        payload.setComment(comment);
        return payload;
    }

    private WebhookPayload payloadWithCommentAndIdentity(String body, long commentId,
                                                         String owner, String repo) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(commentId);
        comment.setBody(body);
        payload.setComment(comment);
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName(repo);
        WebhookPayload.Owner ownerUser = new WebhookPayload.Owner();
        ownerUser.setLogin(owner);
        repository.setOwner(ownerUser);
        payload.setRepository(repository);
        return payload;
    }
}
