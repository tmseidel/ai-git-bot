package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowConfigurationControllerTest {

    private WorkflowConfigurationController newController(WorkflowConfigurationService configurationService,
                                                          WorkflowSelectionService selectionService) {
        return new WorkflowConfigurationController(configurationService, selectionService);
    }

    @Test
    void save_redirectsToWorkflowSelection() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration input = new WorkflowConfiguration();
        input.setName("Security");
        WorkflowConfiguration saved = new WorkflowConfiguration();
        saved.setId(5L);
        when(configurationService.save(input)).thenReturn(saved);
        WorkflowConfigurationController controller = newController(configurationService,
                mock(WorkflowSelectionService.class));

        String view = controller.save(input, new ConcurrentModel(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/workflow-configurations/5/workflows", view);
    }

    @Test
    void save_validationFailure_returnsFormWithError() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration input = new WorkflowConfiguration();
        input.setName("");
        when(configurationService.save(input)).thenThrow(new IllegalArgumentException("Name is required"));
        WorkflowConfigurationController controller = newController(configurationService,
                mock(WorkflowSelectionService.class));
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.save(input, model, new RedirectAttributesModelMap());

        assertEquals("system-settings/workflow-configurations/form", view);
        assertTrue(model.getAttribute("error").toString().contains("Name is required"));
    }

    @Test
    void editForm_unknownId_redirectsBackWithError() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        when(configurationService.findById(99L)).thenReturn(Optional.empty());
        WorkflowConfigurationController controller = newController(configurationService,
                mock(WorkflowSelectionService.class));
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

        String view = controller.editForm(99L, new ConcurrentModel(), flash);

        assertEquals("redirect:/system-settings", view);
        assertEquals("Workflow configuration not found", flash.getFlashAttributes().get("error"));
    }

    @Test
    void saveWorkflowSelection_passesParamsThrough_andRedirects() {
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        WorkflowConfigurationController controller = newController(
                mock(WorkflowConfigurationService.class), selectionService);
        Map<String, String> allParams = new LinkedHashMap<>();
        allParams.put("params.tests.command", "mvn test");
        allParams.put("params.tests.timeoutSeconds", "30");
        allParams.put("foo", "ignored");

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
                Map.of(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/workflow-configurations/4/workflows", view);
    }
}

