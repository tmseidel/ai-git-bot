package org.remus.giteabot.prworkflow.readmesync;

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

class ReadmeSyncSlashCommandHandlerTest {

    private PrWorkflowOrchestrator orchestrator;
    private WorkflowSelectionService selectionService;
    private GiteaClientFactory giteaClientFactory;
    private RepositoryApiClient repoClient;
    private ReadmeSyncSlashCommandHandler handler;

    private Bot bot;

    @BeforeEach
    void setUp() {
        orchestrator = mock(PrWorkflowOrchestrator.class);
        selectionService = mock(WorkflowSelectionService.class);
        giteaClientFactory = mock(GiteaClientFactory.class);
        repoClient = mock(RepositoryApiClient.class);
        when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
        handler = new ReadmeSyncSlashCommandHandler(orchestrator, selectionService, giteaClientFactory);

        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(7L);
        bot = new Bot();
        bot.setId(99L);
        bot.setName("ai-bot");
        bot.setWorkflowConfiguration(cfg);

        when(selectionService.enabledWorkflowKeys(7L))
                .thenReturn(List.of("review", "readme-sync"));
    }

    @Test
    void dispatchesAndForwardsGuidanceText() {
        WebhookPayload payload = payloadWithComment(
                "@ai-bot regenerate-readme Please add an example code how to run the docker container");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(ReadmeSyncWorkflow.KEY), hints.capture());
        assertThat(hints.getValue()).containsEntry(
                PrWorkflowContext.HINT_README_SYNC_GUIDANCE,
                "Please add an example code how to run the docker container");
    }

    @Test
    void dispatchesEmptyHintsWhenNoTrailingText() {
        WebhookPayload payload = payloadWithComment("@ai-bot regenerate-readme");

        boolean handled = handler.tryHandle(bot, payload);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> hints = ArgumentCaptor.forClass(Map.class);
        verify(orchestrator).run(eq(bot), eq(payload), eq(ReadmeSyncWorkflow.KEY), hints.capture());
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
        WebhookPayload payload = payloadWithComment("@ai-bot regenerate-readme add docs");

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
