package org.remus.giteabot.prworkflow.i18n;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class I18nCoverageSlashCommandHandlerTest {

    private PrWorkflowOrchestrator orchestrator;
    private WorkflowSelectionService selectionService;
    private GiteaClientFactory giteaClientFactory;
    private RepositoryApiClient repoClient;
    private I18nCoverageSlashCommandHandler handler;

    private Bot bot;

    @BeforeEach
    void setUp() {
        orchestrator = mock(PrWorkflowOrchestrator.class);
        selectionService = mock(WorkflowSelectionService.class);
        giteaClientFactory = mock(GiteaClientFactory.class);
        repoClient = mock(RepositoryApiClient.class);
        when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
        handler = new I18nCoverageSlashCommandHandler(orchestrator, selectionService, giteaClientFactory);

        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(7L);
        bot = new Bot();
        bot.setId(99L);
        bot.setName("ai-bot");
        bot.setWorkflowConfiguration(cfg);

        when(selectionService.enabledWorkflowKeys(7L))
                .thenReturn(List.of("review", "i18n-coverage"));
    }

    @Test
    void dispatchesAndForwardsGuidanceText() {
        WebhookPayload payload = payloadWithComment(
                "@ai-bot regenerate-i18n Please use for the french translation the word \"flâner\"");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(I18nCoverageWorkflow.KEY), hints.capture());
        assertThat(hints.getValue()).containsEntry(
                PrWorkflowContext.HINT_I18N_COVERAGE_GUIDANCE,
                "Please use for the french translation the word \"flâner\"");
    }

    @Test
    void dispatchesEmptyHintsWhenNoTrailingText() {
        WebhookPayload payload = payloadWithComment("@ai-bot regenerate-i18n");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(I18nCoverageWorkflow.KEY), hints.capture());
        assertThat(hints.getValue()).isEmpty();
    }

    @Test
    void ignoresUnrelatedComment() {
        WebhookPayload payload = payloadWithComment("@ai-bot please review this");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    @Test
    void ignoresWhenWorkflowNotEnabled() {
        when(selectionService.enabledWorkflowKeys(7L)).thenReturn(List.of("review"));
        WebhookPayload payload = payloadWithComment("@ai-bot regenerate-i18n add french");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isFalse();
        verify(orchestrator, never()).run(any(), any(), any(), any());
    }

    private WebhookPayload payloadWithComment(String body) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setBody(body);
        payload.setComment(comment);
        return payload;
    }
}
