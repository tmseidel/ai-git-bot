package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        assertEquals(3, wf.paramsSchema().fields().size());
        assertFalse(wf.paramsSchema().isEmpty());
    }

    @Test
    void run_usesDefaults_whenNoConfiguration_andReportsSuccess() {
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(true);

        Bot bot = new Bot();

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        verify(service).reviewPullRequest(any(), eq(12), eq(false),
                eq(AgentReviewWorkflow.DEFAULT_FORMAL_REVIEW_DECISION_PROMPT));
    }

    @Test
    void run_honoursConfiguredParams_andSkipWhenNoReview() {
        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of(
                "maxToolRounds", 5));
        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        lenient().when(service.reviewPullRequest(any(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(false);

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SKIPPED, result.status());
        verify(service).reviewPullRequest(any(), eq(5), eq(false),
                eq(AgentReviewWorkflow.DEFAULT_FORMAL_REVIEW_DECISION_PROMPT));
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
                "formalReviewDecisionPrompt", "Custom criteria here"));

        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(true);

        WorkflowResult result = workflow().run(context(bot));

        assertEquals(WorkflowResultStatus.SUCCESS, result.status());
        verify(service).reviewPullRequest(any(), eq(8), eq(true),
                eq("Custom criteria here"));
    }

    @Test
    void boolParam_defaultsToFalse_whenMissing() {
        AgentReviewWorkflow wf = workflow();
        when(selectionService.resolveParams(7L, "agentic-review")).thenReturn(Map.of());

        Bot bot = new Bot();
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(7L);
        bot.setWorkflowConfiguration(cfg);

        AgentReviewService service = mock(AgentReviewService.class);
        when(serviceFactory.create(any())).thenReturn(service);
        when(service.reviewPullRequest(any(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(true);

        workflow().run(context(bot));

        verify(service).reviewPullRequest(any(), anyInt(), eq(false), anyString());
    }
}
