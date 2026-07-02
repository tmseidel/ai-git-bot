package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowConfigurationControllerTest {

    private static WorkflowConfigurationController newController(
            WorkflowConfigurationService configurationService,
            WorkflowSelectionService selectionService) {
        return new WorkflowConfigurationController(configurationService, selectionService);
    }

    @Test
    void save_validationFailure_returnsFormWithError() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        doThrow(new IllegalArgumentException("Name is required"))
                .when(configurationService).save(any());
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.save(new WorkflowConfiguration(),
                mock(org.springframework.ui.Model.class), new RedirectAttributesModelMap());

        assertEquals("system-settings/workflow-configurations/form", view);
    }

    @Test
    void save_success_redirectsToWorkflowSelection() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration saved = new WorkflowConfiguration();
        saved.setId(42L);
        when(configurationService.save(any())).thenReturn(saved);
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.save(new WorkflowConfiguration(),
                mock(org.springframework.ui.Model.class), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/workflow-configurations/42/workflows", view);
    }

    @Test
    void delete_redirectsToSystemSettings() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.delete(1L, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService).deleteById(1L);
    }

    @Test
    void saveWorkflowSelection_passesParamsThrough_andRedirects() {
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        WorkflowConfigurationController controller = newController(
                mock(WorkflowConfigurationService.class), selectionService);
        MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();
        allParams.add("params.tests.command", "mvn test");
        allParams.add("params.tests.timeoutSeconds", "30");
        allParams.add("foo", "ignored");

        String view = controller.saveWorkflowSelection(3L,
                List.of("tests"), allParams, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(selectionService).saveSelection(eq(3L), eq(List.of("tests")), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveWorkflowSelection_trueWinsForBooleanOnly_othersTakeLastValue() {
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        when(selectionService.isBooleanField("agentic-review", "enableFormalReviewDecision"))
                .thenReturn(true);
        // Any other field defaults to non-boolean (mock returns false).
        WorkflowConfigurationController controller = newController(
                mock(WorkflowConfigurationService.class), selectionService);

        MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();
        // Checked boolean: hidden "false" + checkbox "true".
        allParams.add("params.agentic-review.enableFormalReviewDecision", "false");
        allParams.add("params.agentic-review.enableFormalReviewDecision", "true");
        // Non-boolean field submitting duplicate values — last one wins.
        allParams.add("params.agentic-review.mode", "first");
        allParams.add("params.agentic-review.mode", "second");

        controller.saveWorkflowSelection(7L, List.of("agentic-review"), allParams,
                new RedirectAttributesModelMap());

        ArgumentCaptor<Map<String, Map<String, String>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(selectionService).saveSelection(eq(7L), eq(List.of("agentic-review")), captor.capture());
        Map<String, String> params = captor.getValue().get("agentic-review");
        assertEquals("true", params.get("enableFormalReviewDecision"));
        assertEquals("second", params.get("mode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveWorkflowSelection_uncheckedBooleanPersistsFalse() {
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        when(selectionService.isBooleanField("agentic-review", "enableFormalReviewDecision"))
                .thenReturn(true);
        WorkflowConfigurationController controller = newController(
                mock(WorkflowConfigurationService.class), selectionService);

        MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();
        // Unchecked boolean: only the hidden "false" is submitted.
        allParams.add("params.agentic-review.enableFormalReviewDecision", "false");

        controller.saveWorkflowSelection(8L, List.of("agentic-review"), allParams,
                new RedirectAttributesModelMap());

        ArgumentCaptor<Map<String, Map<String, String>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(selectionService).saveSelection(eq(8L), eq(List.of("agentic-review")), captor.capture());
        assertEquals("false", captor.getValue().get("agentic-review").get("enableFormalReviewDecision"));
    }

    @Test
    void saveWorkflowSelection_validationError_redirectsBackToSelection() {
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        doThrow(new IllegalArgumentException("Workflow 'tests': Parameter 'Command' is required"))
                .when(selectionService).saveSelection(anyLong(), anyList(), any());
        WorkflowConfigurationController controller = newController(
                mock(WorkflowConfigurationService.class), selectionService);

        String view = controller.saveWorkflowSelection(4L, List.of("tests"),
                new LinkedMultiValueMap<>(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/workflow-configurations/4/workflows", view);
    }
}
