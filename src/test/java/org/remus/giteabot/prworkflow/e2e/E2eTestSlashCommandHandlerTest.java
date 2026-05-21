package org.remus.giteabot.prworkflow.e2e;

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
 * Unit tests for the M4 wave-2 slash command handler. The handler must:
 * <ul>
 *   <li>recognise {@code @bot rerun-tests} and {@code @bot regenerate-tests}
 *       (case-insensitive, with or without leading whitespace) and dispatch
 *       a new {@code e2e-test} workflow run via {@link PrWorkflowOrchestrator};</li>
 *   <li>ignore any other comment so the regular code-review path keeps
 *       working;</li>
 *   <li>refuse to dispatch when the bot's workflow configuration does not
 *       enable {@code e2e-test} — yielding the comment back to the standard
 *       handler.</li>
 * </ul>
 */
class E2eTestSlashCommandHandlerTest {

    private PrWorkflowOrchestrator orchestrator;
    private WorkflowSelectionService selectionService;
    private GiteaClientFactory giteaClientFactory;
    private RepositoryApiClient repoClient;
    private E2eTestSlashCommandHandler handler;

    private Bot bot;
    private WorkflowConfiguration workflowConfig;

    @BeforeEach
    void setUp() {
        orchestrator = mock(PrWorkflowOrchestrator.class);
        selectionService = mock(WorkflowSelectionService.class);
        giteaClientFactory = mock(GiteaClientFactory.class);
        repoClient = mock(RepositoryApiClient.class);
        when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
        handler = new E2eTestSlashCommandHandler(orchestrator, selectionService, giteaClientFactory);

        workflowConfig = new WorkflowConfiguration();
        workflowConfig.setId(7L);
        workflowConfig.setName("Full-stack QA");

        bot = new Bot();
        bot.setId(99L);
        bot.setName("ai-bot");
        bot.setWorkflowConfiguration(workflowConfig);

        when(selectionService.enabledWorkflowKeys(7L))
                .thenReturn(List.of("review", "e2e-test"));
    }

    @Test
    void dispatchesOnRerunTests() {
        WebhookPayload payload = payloadWithComment("@ai-bot rerun-tests please");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), key.capture(), hints.capture());
        assertThat(key.getValue()).isEqualTo(E2ETestWorkflow.KEY);
        // "please" trails `rerun-tests` and is captured as feedback — it is a hint, not a no-op.
        assertThat(hints.getValue()).containsEntry(PrWorkflowContext.HINT_E2E_FEEDBACK, "please");
    }

    @Test
    void dispatchesOnRegenerateTestsWithFeedback() {
        WebhookPayload payload = payloadWithComment(
                "Hey @ai-bot regenerate-tests focus on the checkout flow");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator, times(1))
                .run(eq(bot), eq(payload), eq(E2ETestWorkflow.KEY), hints.capture());
        assertThat(hints.getValue())
                .containsEntry(PrWorkflowContext.HINT_E2E_FEEDBACK, "focus on the checkout flow");
    }

    @Test
    void dispatchesEmptyHintsWhenNoTrailingFeedback() {
        WebhookPayload payload = payloadWithComment("@ai-bot rerun-tests");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(E2ETestWorkflow.KEY), hints.capture());
        assertThat(hints.getValue()).isEmpty();
    }

    @Test
    void ignoresUnrelatedComment() {
        WebhookPayload payload = payloadWithComment("@ai-bot please review the diff again");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void doesNotDispatchWhenE2eDisabledOnConfiguration() {
        when(selectionService.enabledWorkflowKeys(7L)).thenReturn(List.of("review"));
        WebhookPayload payload = payloadWithComment("@ai-bot rerun-tests");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void ignoresWhenBotHasNoWorkflowConfiguration() {
        bot.setWorkflowConfiguration(null);
        WebhookPayload payload = payloadWithComment("@ai-bot rerun-tests");

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
                "@ai-bot rerun-tests", 4242L, "acme", "web");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        verify(repoClient).addReaction("acme", "web", 4242L, "eyes");
    }

    @Test
    void doesNotReactWhenE2eDisabled() {
        when(selectionService.enabledWorkflowKeys(7L)).thenReturn(List.of("review"));
        WebhookPayload payload = payloadWithCommentAndIdentity(
                "@ai-bot rerun-tests", 99L, "acme", "web");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(repoClient, never()).addReaction(any(), any(), any(), any());
    }

    @Test
    void doesNotReactOnUnrelatedComment() {
        WebhookPayload payload = payloadWithCommentAndIdentity(
                "@ai-bot please review again", 100L, "acme", "web");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(repoClient, never()).addReaction(any(), any(), any(), any());
    }

    @Test
    void reactionFailureDoesNotBlockDispatch() {
        WebhookPayload payload = payloadWithCommentAndIdentity(
                "@ai-bot rerun-tests", 7L, "acme", "web");
        org.mockito.Mockito.doThrow(new RuntimeException("API down"))
                .when(repoClient).addReaction(any(), any(), any(), any());

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        verify(orchestrator).run(eq(bot), eq(payload), eq(E2ETestWorkflow.KEY), any());
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



