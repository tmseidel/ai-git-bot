package org.remus.giteabot.prworkflow.readmesync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadmeSyncWorkflowTest {

    @Mock
    private ReadmeSyncServiceFactory serviceFactory;

    @Mock
    private WorkflowSelectionService selectionService;

    private ReadmeSyncWorkflow workflow() {
        return new ReadmeSyncWorkflow(serviceFactory, selectionService);
    }

    private PrWorkflowContext context(Bot bot) {
        return new PrWorkflowContext(bot, new WebhookPayload(), 1L, (n, l) -> { }, () -> false);
    }

    private PrWorkflowContext context(Bot bot, Map<String, String> hints) {
        return new PrWorkflowContext(bot, new WebhookPayload(), 1L, (n, l) -> { }, () -> false, hints);
    }

    @Test
    void metadata_isStableAndDocs() {
        ReadmeSyncWorkflow wf = workflow();
        assertEquals("readme-sync", wf.key());
        assertEquals(PrWorkflowCategory.DOCS, wf.category());
        assertEquals(3, wf.paramsSchema().fields().size());
    }

    @Test
    void run_usesDefaults_whenNoConfiguration() {
        ReadmeSyncService service = mock(ReadmeSyncService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(ReadmeSyncService.Result.success("done"));

        WorkflowResult result = workflow().run(context(new Bot()));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        ArgumentCaptor<ReadmeSyncService.Request> captor =
                ArgumentCaptor.forClass(ReadmeSyncService.Request.class);
        verify(service).run(captor.capture());
        ReadmeSyncService.Request req = captor.getValue();
        assertEquals(12, req.maxToolRounds());
        assertEquals(SuiteLifecycleMode.COMMIT_TO_PR, req.lifecycleMode());
        // Default patterns parsed into individual globs, README included.
        assertTrue(req.includePatterns().contains("README.md"), req.includePatterns().toString());
    }

    @Test
    void run_honoursConfiguredParams_andForwardsGuidanceHint() {
        Bot bot = new Bot();
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(9L);
        bot.setWorkflowConfiguration(cfg);
        when(selectionService.resolveParams(9L, "readme-sync")).thenReturn(Map.of(
                "includedFilePatterns", "docs/**/*.md",
                "maxToolRounds", 5,
                "suiteLifecycle", "offer-as-pr"));
        ReadmeSyncService service = mock(ReadmeSyncService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(ReadmeSyncService.Result.success("ok"));

        Map<String, String> hints = Map.of(
                PrWorkflowContext.HINT_README_SYNC_GUIDANCE, "add a docker run example");
        workflow().run(context(bot, hints));

        ArgumentCaptor<ReadmeSyncService.Request> captor =
                ArgumentCaptor.forClass(ReadmeSyncService.Request.class);
        verify(service).run(captor.capture());
        ReadmeSyncService.Request req = captor.getValue();
        assertEquals(5, req.maxToolRounds());
        assertEquals(SuiteLifecycleMode.OFFER_AS_PR, req.lifecycleMode());
        assertEquals(java.util.List.of("docs/**/*.md"), req.includePatterns());
        assertEquals("add a docker run example", req.userGuidance());
    }

    @Test
    void run_promoteOnMerge_fallsBackToCommitToPr() {
        Bot bot = new Bot();
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(3L);
        bot.setWorkflowConfiguration(cfg);
        when(selectionService.resolveParams(3L, "readme-sync")).thenReturn(Map.of(
                "suiteLifecycle", "promote-on-merge"));
        ReadmeSyncService service = mock(ReadmeSyncService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(ReadmeSyncService.Result.success("ok"));

        workflow().run(context(bot));

        ArgumentCaptor<ReadmeSyncService.Request> captor =
                ArgumentCaptor.forClass(ReadmeSyncService.Request.class);
        verify(service).run(captor.capture());
        assertEquals(SuiteLifecycleMode.COMMIT_TO_PR, captor.getValue().lifecycleMode());
    }

    @Test
    void run_clampsMaxToolRounds() {
        Bot bot = new Bot();
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(4L);
        bot.setWorkflowConfiguration(cfg);
        when(selectionService.resolveParams(4L, "readme-sync")).thenReturn(Map.of(
                "maxToolRounds", 999));
        ReadmeSyncService service = mock(ReadmeSyncService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(ReadmeSyncService.Result.skipped("no diff"));

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SKIPPED, result.status());
        ArgumentCaptor<ReadmeSyncService.Request> captor =
                ArgumentCaptor.forClass(ReadmeSyncService.Request.class);
        verify(service).run(captor.capture());
        assertEquals(30, captor.getValue().maxToolRounds());
    }
}
