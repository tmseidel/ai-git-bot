package org.remus.giteabot.issueworkflow.triage;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.issueworkflow.IssueWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link TriageIssueWorkflow}: SPI surface, parameter schema,
 * and delegation to {@link IssueTriageService}.
 */
class TriageIssueWorkflowTest {

    private final IssueTriageService triageService = mock(IssueTriageService.class);
    private final TriageIssueWorkflow workflow = new TriageIssueWorkflow(triageService);

    @Test
    void spiSurface_isStable() {
        assertEquals("issue-triage", workflow.key());
        assertEquals("Issue Triage", workflow.displayName());
        assertTrue(workflow.description().contains("Never implements the issue"));
    }

    @Test
    void paramsSchema_exposesEditablePromptAndAssigneesWithDefaults() {
        WorkflowParamsSchema schema = workflow.paramsSchema();

        WorkflowParamField prompt = schema.require("systemPrompt");
        assertEquals(WorkflowParamField.ParamType.TEXT, prompt.type());
        assertTrue(prompt.required());
        assertTrue(prompt.defaultValue().contains("issue_hemingway"));
        assertTrue(prompt.description().contains("MUST be reviewed"));

        WorkflowParamField assignees = schema.require("assignees");
        assertEquals(WorkflowParamField.ParamType.STRING, assignees.type());
        assertTrue(assignees.required());
        assertEquals(TriageIssueWorkflow.DEFAULT_ASSIGNEES, assignees.defaultValue());
    }

    @Test
    void defaultPrompt_usesCanonicalClarificationBotName() {
        assertTrue(TriageIssueWorkflow.DEFAULT_SYSTEM_PROMPT.contains("issue_hemingway"));
        assertFalse(TriageIssueWorkflow.DEFAULT_SYSTEM_PROMPT.contains("writer-bot"));
    }

    @Test
    void onIssueAssigned_delegatesToService() {
        Bot bot = new Bot();
        WebhookPayload payload = new WebhookPayload();
        Map<String, Object> params = Map.of("systemPrompt", "PROMPT");
        IssueWorkflowContext context = new IssueWorkflowContext(bot, payload, params);

        workflow.onIssueAssigned(context);

        verify(triageService).triage(bot, payload, params);
    }

    @Test
    void onIssueComment_isAConsciousNoOp() {
        IssueWorkflowContext context = new IssueWorkflowContext(new Bot(), new WebhookPayload(), Map.of());

        assertDoesNotThrow(() -> workflow.onIssueComment(context));
        verifyNoInteractions(triageService);
    }
}
