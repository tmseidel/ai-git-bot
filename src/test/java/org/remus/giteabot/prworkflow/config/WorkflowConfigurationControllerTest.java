package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.LinkedHashMap;
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
