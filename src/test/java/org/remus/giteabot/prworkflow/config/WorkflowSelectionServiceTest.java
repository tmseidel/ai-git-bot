package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowRegistry;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSelectionServiceTest {

    @Mock private WorkflowConfigurationRepository configurationRepository;
    @Mock private WorkflowSelectionRepository selectionRepository;

    private final WorkflowParamsValidator paramsValidator = new WorkflowParamsValidator();
    private WorkflowSelectionService service;

    @BeforeEach
    void setUp() {
        PrWorkflowRegistry registry = new PrWorkflowRegistry(List.of(new ReviewLike(), new TestsLike()));
        service = new WorkflowSelectionService(configurationRepository, selectionRepository,
                registry, paramsValidator);
    }

    @Test
    void saveSelection_validatesParams_andPersistsAsChildRows() {
        WorkflowConfiguration cfg = configuration();
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(cfg));
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(List.of());

        service.saveSelection(1L,
                List.of("tests-like"),
                Map.of("tests-like", Map.of("command", "mvn test", "timeoutSeconds", "42")));

        ArgumentCaptor<Iterable<WorkflowSelection>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(selectionRepository).deleteByConfigurationId(1L);
        verify(selectionRepository).saveAll(captor.capture());
        WorkflowSelection persisted = captor.getValue().iterator().next();
        assertEquals("tests-like", persisted.getWorkflowKey());
        // canonical form: typed coercion (timeoutSeconds parsed and re-serialised as "42")
        Map<String, String> params = persisted.getParamsMap();
        assertEquals("mvn test", params.get("command"));
        assertEquals("42", params.get("timeoutSeconds"));
    }

    @Test
    void saveSelection_missingRequiredParam_throwsAggregatedError() {
        WorkflowConfiguration cfg = configuration();
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(cfg));
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.saveSelection(1L,
                        List.of("tests-like"),
                        Map.of("tests-like", Map.of("timeoutSeconds", "5"))));
        assertTrue(e.getMessage().contains("Command"));
        verify(selectionRepository, never()).saveAll(any());
    }

    @Test
    void enableWorkflow_addsSelectionWithCanonicalParams() {
        WorkflowConfiguration cfg = configuration();
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(cfg));
        when(selectionRepository.findByConfigurationIdAndWorkflowKey(1L, "review-like"))
                .thenReturn(Optional.empty());

        service.enableWorkflow(1L, "review-like", null);

        ArgumentCaptor<WorkflowSelection> captor = ArgumentCaptor.forClass(WorkflowSelection.class);
        verify(selectionRepository).save(captor.capture());
        assertEquals("review-like", captor.getValue().getWorkflowKey());
        assertTrue(captor.getValue().getParamsMap().isEmpty());
    }

    @Test
    void enabledWorkflowKeys_returnsStableOrderByKey() {
        when(selectionRepository.findByConfigurationIdOrderByWorkflowKeyAsc(1L))
                .thenReturn(List.of(selection("review-like"), selection("tests-like")));

        List<String> keys = service.enabledWorkflowKeys(1L);
        assertEquals(List.of("review-like", "tests-like"), keys);
    }

    @Test
    void resolveParams_typedAccordingToSchema() {
        WorkflowSelection sel = new WorkflowSelection();
        sel.setWorkflowKey("tests-like");
        sel.replaceParams(Map.of("command", "mvn", "timeoutSeconds", "42"));
        when(selectionRepository.findByConfigurationIdAndWorkflowKey(1L, "tests-like"))
                .thenReturn(Optional.of(sel));

        Map<String, Object> params = service.resolveParams(1L, "tests-like");
        assertEquals("mvn", params.get("command"));
        assertEquals(42L, params.get("timeoutSeconds"));
    }

    @Test
    void loadAvailableWorkflows_listsRegisteredAndOrphans() {
        WorkflowConfiguration cfg = configuration();
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(cfg));
        WorkflowSelection orphan = new WorkflowSelection();
        orphan.setWorkflowKey("ghost");
        when(selectionRepository.findByConfigurationId(1L)).thenReturn(List.of(orphan));

        List<WorkflowSelectionRow> rows = service.loadAvailableWorkflows(1L);
        // 2 registered + 1 orphan
        assertEquals(3, rows.size());
        assertNotNull(rows.stream().filter(r -> r.workflowKey().equals("ghost")).findFirst().orElseThrow());
    }

    private WorkflowConfiguration configuration() {
        WorkflowConfiguration cfg = new WorkflowConfiguration();
        cfg.setId(1L);
        cfg.setName("Default");
        cfg.setDefaultEntry(true);
        return cfg;
    }

    private WorkflowSelection selection(String key) {
        WorkflowSelection s = new WorkflowSelection();
        s.setWorkflowKey(key);
        return s;
    }

    // -------- test workflows --------

    private static final class ReviewLike implements PrWorkflow {
        @Override public String key() { return "review-like"; }
        @Override public String displayName() { return "Review-like"; }
        @Override public PrWorkflowCategory category() { return PrWorkflowCategory.REVIEW; }
        @Override public WorkflowResult run(PrWorkflowContext context) { return WorkflowResult.skipped("noop"); }
    }

    private static final class TestsLike implements PrWorkflow {
        @Override public String key() { return "tests-like"; }
        @Override public String displayName() { return "Tests-like"; }
        @Override public PrWorkflowCategory category() { return PrWorkflowCategory.TESTING; }
        @Override public WorkflowResult run(PrWorkflowContext context) { return WorkflowResult.skipped("noop"); }
        @Override public WorkflowParamsSchema paramsSchema() {
            return WorkflowParamsSchema.of(
                    new WorkflowParamField("command", "Command",
                            WorkflowParamField.ParamType.STRING, true, null, "Test command", List.of()),
                    new WorkflowParamField("timeoutSeconds", "Timeout",
                            WorkflowParamField.ParamType.INTEGER, false, "300", "Timeout in seconds", List.of()));
        }
    }
}
