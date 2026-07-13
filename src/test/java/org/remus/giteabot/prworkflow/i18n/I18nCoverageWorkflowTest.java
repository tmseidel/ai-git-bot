package org.remus.giteabot.prworkflow.i18n;

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
import org.remus.giteabot.prworkflow.config.WorkflowParamsValidator;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class I18nCoverageWorkflowTest {

    @Mock
    private I18nCoverageServiceFactory serviceFactory;

    @Mock
    private WorkflowSelectionService selectionService;

    private I18nCoverageWorkflow workflow() {
        return new I18nCoverageWorkflow(serviceFactory, selectionService);
    }

    private PrWorkflowContext context(Bot bot) {
        return new PrWorkflowContext(bot, new WebhookPayload(), 1L, (n, l) -> { }, () -> false);
    }

    private PrWorkflowContext context(Bot bot, Map<String, String> hints) {
        return new PrWorkflowContext(bot, new WebhookPayload(), 1L, (n, l) -> { }, () -> false, hints);
    }

    @Test
    void metadata_isStableAndDocs() {
        I18nCoverageWorkflow wf = workflow();
        assertEquals("i18n-coverage", wf.key());
        assertEquals(PrWorkflowCategory.DOCS, wf.category());
        assertEquals(4, wf.paramsSchema().fields().size());
    }

    /**
     * Regression: a blank baseline locale is a meaningful value ("auto-detect
     * per family") and MUST survive validation unchanged. If the field carried
     * a non-blank default, {@link WorkflowParamsValidator} would silently
     * substitute that default on save, so a cleared field would revert on the
     * next page load. Contrast with maxToolRounds, whose default IS applied.
     */
    @Test
    void blankBaselineLocale_isPersistedAsBlank_notRevertedToDefault() {
        WorkflowParamsValidator validator = new WorkflowParamsValidator();
        Map<String, String> submitted = new HashMap<>();
        submitted.put(I18nCoverageParam.BASELINE_LOCALE.key(), "");   // operator cleared the field
        submitted.put(I18nCoverageParam.INCLUDED_FILE_PATTERNS.key(), "messages_*.properties");

        Map<String, String> canonical = validator.validate(submitted, workflow().paramsSchema());

        // Blank baseline is dropped (not forced to "en"), so on reload the field is empty.
        assertFalse(canonical.containsKey(I18nCoverageParam.BASELINE_LOCALE.key()),
                "blank baselineLocale must not be persisted as a non-blank default: " + canonical);
        // A field that DOES declare a default still gets it applied.
        assertEquals("14", canonical.get(I18nCoverageParam.MAX_TOOL_ROUNDS.key()));
    }

    @Test
    void run_usesDefaults_whenNoConfiguration() {
        I18nCoverageService service = mock(I18nCoverageService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(I18nCoverageService.Result.success("done"));

        WorkflowResult result = workflow().run(context(new Bot()));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        ArgumentCaptor<I18nCoverageService.Request> captor =
                ArgumentCaptor.forClass(I18nCoverageService.Request.class);
        verify(service).run(captor.capture());
        I18nCoverageService.Request req = captor.getValue();
        assertEquals(14, req.maxToolRounds());
        assertEquals(SuiteLifecycleMode.COMMIT_TO_PR, req.lifecycleMode());
        // Baseline defaults to blank (auto-detect per family), NOT a hard "en" —
        // a blank value must be persistable, so it can't carry a non-blank default.
        assertEquals("", req.baselineLocale());
        assertTrue(req.includePatterns().contains("messages_*.properties"),
                req.includePatterns().toString());
        assertTrue(req.includePatterns().contains("i18n/*.json"), req.includePatterns().toString());
    }

    @Test
    void run_honoursConfiguredParams_andForwardsGuidanceHint() {
        Bot bot = new Bot();
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(9L);
        bot.setWorkflowConfiguration(cfg);
        when(selectionService.resolveParams(9L, "i18n-coverage")).thenReturn(Map.of(
                "includedFilePatterns", "i18n/*.json",
                "baselineLocale", "de",
                "maxToolRounds", 5,
                "suiteLifecycle", "offer-as-pr"));
        I18nCoverageService service = mock(I18nCoverageService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(I18nCoverageService.Result.success("ok"));

        Map<String, String> hints = Map.of(
                PrWorkflowContext.HINT_I18N_COVERAGE_GUIDANCE, "use flâner for french");
        workflow().run(context(bot, hints));

        ArgumentCaptor<I18nCoverageService.Request> captor =
                ArgumentCaptor.forClass(I18nCoverageService.Request.class);
        verify(service).run(captor.capture());
        I18nCoverageService.Request req = captor.getValue();
        assertEquals(5, req.maxToolRounds());
        assertEquals("de", req.baselineLocale());
        assertEquals(SuiteLifecycleMode.OFFER_AS_PR, req.lifecycleMode());
        assertEquals(List.of("i18n/*.json"), req.includePatterns());
        assertEquals("use flâner for french", req.userGuidance());
    }

    @Test
    void run_promoteOnMerge_fallsBackToCommitToPr() {
        Bot bot = new Bot();
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(3L);
        bot.setWorkflowConfiguration(cfg);
        when(selectionService.resolveParams(3L, "i18n-coverage")).thenReturn(Map.of(
                "suiteLifecycle", "promote-on-merge"));
        I18nCoverageService service = mock(I18nCoverageService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(I18nCoverageService.Result.success("ok"));

        workflow().run(context(bot));

        ArgumentCaptor<I18nCoverageService.Request> captor =
                ArgumentCaptor.forClass(I18nCoverageService.Request.class);
        verify(service).run(captor.capture());
        assertEquals(SuiteLifecycleMode.COMMIT_TO_PR, captor.getValue().lifecycleMode());
    }

    @Test
    void run_clampsMaxToolRounds() {
        Bot bot = new Bot();
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(4L);
        bot.setWorkflowConfiguration(cfg);
        when(selectionService.resolveParams(4L, "i18n-coverage")).thenReturn(Map.of(
                "maxToolRounds", 999));
        I18nCoverageService service = mock(I18nCoverageService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.run(any())).thenReturn(I18nCoverageService.Result.skipped("no gaps"));

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SKIPPED, result.status());
        ArgumentCaptor<I18nCoverageService.Request> captor =
                ArgumentCaptor.forClass(I18nCoverageService.Request.class);
        verify(service).run(captor.capture());
        assertEquals(30, captor.getValue().maxToolRounds());
    }
}
