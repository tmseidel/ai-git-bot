package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.repository.RepositoryType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentReviewWorkflowTest {

    @Mock
    private AgentReviewServiceFactory serviceFactory;

    @Mock
    private WorkflowSelectionService selectionService;

    private AgentReviewWorkflow workflow() {
        return new AgentReviewWorkflow(serviceFactory, selectionService);
    }

    private PrWorkflowContext context(Bot bot) {
        return new PrWorkflowContext(bot, new WebhookPayload(), 1L,
                (name, log) -> { }, () -> false);
    }

    @Test
    void metadata_isStableAndReview() {
        AgentReviewWorkflow wf = workflow();
        assertEquals("agentic-review", wf.key());
        assertEquals(PrWorkflowCategory.REVIEW, wf.category());
        assertEquals(9, wf.paramsSchema().fields().size());
        assertFalse(wf.paramsSchema().isEmpty());
        var progress = wf.paramsSchema().fields().stream()
                .filter(field -> field.name().equals("enableProgressComments")).findFirst().orElseThrow();
        assertEquals(WorkflowParamField.ParamType.BOOLEAN, progress.type());
        assertEquals("false", progress.defaultValue());
        org.assertj.core.api.Assertions.assertThat(progress.description()).contains("review or clarification");
    }

    @Test
    void theLearningParametersShipOnlyImplementedModesAndDefaultToOff() {
        var schema = workflow().paramsSchema();
        var mode = schema.require("learningMode");
        assertEquals(WorkflowParamField.ParamType.ENUM, mode.type());
        assertEquals("OFF", mode.defaultValue());
        org.assertj.core.api.Assertions.assertThat(mode.allowedValues())
                .extracting(WorkflowParamField.EnumOption::key)
                .containsExactly("OFF", "CURATED");
        // The configuration is shared between bots; the form has to say so.
        org.assertj.core.api.Assertions.assertThat(mode.description()).contains("shared");

        var maintainers = schema.require("learningMaintainers");
        assertEquals(WorkflowParamField.ParamType.TEXT, maintainers.type());
        org.assertj.core.api.Assertions.assertThat(maintainers.defaultValue()).isNull();
        org.assertj.core.api.Assertions.assertThat(maintainers.description()).contains("nobody");
    }

    @Test
    void invalidLearningParametersAreRefusedByTheWorkflow() {
        org.assertj.core.api.Assertions.assertThat(workflow().validateParams(
                Map.of("learningMode", "CURATED", "learningMaintainers", "alex\nmaria")))
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(workflow().validateParams(
                Map.of("learningMaintainers", "two names"))).hasSize(1);
    }

    @Test
    void theFeedbackParameterSaysItGatesFeedbackCommands() {
        var feedback = workflow().paramsSchema().fields().stream()
                .filter(field -> field.name().equals("feedbackEnabled")).findFirst().orElseThrow();

        // The switch does two things — accept @bot feedback commands and keep the evidence snapshot
        // — so its label has to name the one an operator will search for.
        org.assertj.core.api.Assertions.assertThat(feedback.label()).containsIgnoringCase("feedback");
        org.assertj.core.api.Assertions.assertThat(feedback.description())
                .contains("@bot feedback")
                .contains("diff");
    }

    @Test
    void aSuppressedPublicationIsASkippedRunNotAFailure() {
        Bot bot = new Bot();
        var cfg = new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);
        GitIntegration git = new GitIntegration();
        git.setProviderType(RepositoryType.GITHUB);
        bot.setGitIntegration(git);
        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of());
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(bot)).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(), any(),
                anyLong(), any(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.SUPPRESSED);

        // A head that moved while the review ran is normal; calling it a failure hides that the
        // review was correctly held back.
        assertEquals(WorkflowResultStatus.SKIPPED, workflow().run(context(bot)).status());
    }

    @ParameterizedTest
    @EnumSource(RepositoryType.class)
    void progressIsEnabledForEveryProviderWhenSelected(RepositoryType provider) {
        Bot bot = new Bot();
        var cfg = new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);
        GitIntegration git = new GitIntegration();
        git.setProviderType(provider);
        bot.setGitIntegration(git);
        when(selectionService.resolveParams(7L, "agentic-review"))
                .thenReturn(Map.of("enableProgressComments", true));
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(bot)).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(), any(),
                anyLong(), any(), anyBoolean(), any())).thenReturn(AgentReviewService.ReviewResult.POSTED);
        when(service.answerClarification(any(), anyString(), anyInt(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.POSTED);

        assertEquals(WorkflowResultStatus.SUCCESS, workflow().run(context(bot)).status());
        PrWorkflowContext clarification = new PrWorkflowContext(bot, new WebhookPayload(), 1L,
                (name, log) -> { }, () -> false,
                Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, "Why was this changed?"), null);
        assertEquals(WorkflowResultStatus.SUCCESS, workflow().run(clarification).status());

        verify(service).reviewPullRequest(any(), anyInt(), anyBoolean(), any(),
                eq(1L), isNull(), eq(true), any());
        verify(service).answerClarification(any(), anyString(), anyInt(), eq(true), any());
    }

    @Test
    void run_usesDefaults_whenNoConfiguration_andReportsSuccess() {
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.POSTED);

        Bot bot = new Bot();

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        verify(service).reviewPullRequest(any(), eq(12), eq(false),
                eq(new AgentReviewService.SeverityThresholds(null, null, null)), eq(1L), isNull(), eq(false), any());
    }

    @Test
    void run_honoursConfiguredParams_andSkipWhenNoReview() {
        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of("maxToolRounds", 5));
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        lenient().when(service.reviewPullRequest(any(), anyInt(), anyBoolean(),
                        any(AgentReviewService.SeverityThresholds.class), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.NO_DIFF);

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SKIPPED, result.status());
        verify(service).reviewPullRequest(any(), eq(5), eq(false),
                eq(new AgentReviewService.SeverityThresholds(null, null, null)), eq(1L), isNull(), eq(false), any());
    }

    @Test
    void run_reportsFailure_whenReviewCannotComplete() {
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.FAILED);

        WorkflowResult result = workflow().run(context(new Bot()));

        assertEquals(WorkflowResultStatus.FAILED, result.status());
    }

    @ParameterizedTest
    @CsvSource({"POSTED,SUCCESS", "NO_DIFF,SKIPPED", "FAILED,FAILED"})
    void clarification_mapsServiceResult(AgentReviewService.ReviewResult serviceResult,
                                         WorkflowResultStatus expected) {
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.answerClarification(any(), anyString(), anyInt(), anyBoolean(), any())).thenReturn(serviceResult);
        PrWorkflowContext context = new PrWorkflowContext(new Bot(), new WebhookPayload(), 1L,
                (name, log) -> { }, () -> false,
                Map.of(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION, "Why was this changed?"), null);

        assertEquals(expected, workflow().run(context).status());
    }

    @Test
    void run_enablesFormalDecision_whenParamsSet() {
        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of(
                "maxToolRounds", 8,
                "enableFormalReviewDecision", true,
                "blockerThreshold", 0,
                "mediumThreshold", 2,
                "lowThreshold", 5));

        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.POSTED);

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        verify(service).reviewPullRequest(any(), eq(8), eq(true),
                eq(new AgentReviewService.SeverityThresholds(0, 2, 5)), eq(1L), isNull(), eq(false), any());
    }

    @Test
    void boolParam_defaultsToFalse_whenMissing() {
        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of());

        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.POSTED);

        workflow().run(context(bot));

        verify(service).reviewPullRequest(any(), anyInt(), eq(false),
                eq(new AgentReviewService.SeverityThresholds(null, null, null)), eq(1L), isNull(), eq(false), any());
    }

    @Test
    void feedbackCollection_comesFromThisRunsParams() {
        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);
        when(selectionService.resolveParams(7L, "agentic-review"))
                .thenReturn(Map.of("feedbackEnabled", true));

        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(),
                any(AgentReviewService.SeverityThresholds.class), anyLong(), any(), anyBoolean(), any()))
                .thenReturn(AgentReviewService.ReviewResult.POSTED);

        workflow().run(context(bot));

        verify(service).reviewPullRequest(any(), anyInt(), eq(false),
                eq(new AgentReviewService.SeverityThresholds(null, null, null)), eq(1L), isNull(), eq(false),
                eq(new AgentReviewLearningSettings(true)));
        // the configuration is read once per run, not once per consumer of its params
        verify(selectionService, times(1)).resolveParams(7L, "agentic-review");
    }
}
