package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end persistence round-trip test for the workflow-selection params
 * refactor (JSON column → {@code workflow_selection_params} child table).
 *
 * <p>Reproduces the operator-reported "Werte werden nicht übernommen" bug:
 * save a selection with params, then update the params via a second save and
 * assert that the new values are observable from a fresh transaction (i.e.
 * orphan-removal of the previous param rows actually happens and the
 * replacement rows are committed).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowSelectionServiceIntegrationTest {

    @Autowired private WorkflowConfigurationRepository configurationRepository;
    @Autowired private WorkflowSelectionRepository selectionRepository;
    @Autowired private WorkflowSelectionService service;
    @Autowired private PrWorkflowRegistry registry;
    @Autowired private TransactionTemplate tx;

    /** Pick any registered workflow that declares a non-empty params schema. */
    private PrWorkflow pickWorkflowWithParams() {
        return registry.all().stream()
                .filter(w -> !w.paramsSchema().fields().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No PrWorkflow with params registered — test cannot run"));
    }

    @Test
    void saveSelection_thenUpdate_overwritesPreviouslyPersistedParams() {
        PrWorkflow wf = pickWorkflowWithParams();
        String key = wf.key();
        var schema = wf.paramsSchema();
        // Build two distinct valid value-sets covering all fields
        Map<String, String> firstValues = new java.util.LinkedHashMap<>();
        Map<String, String> secondValues = new java.util.LinkedHashMap<>();
        for (var field : schema.fields()) {
            firstValues.put(field.name(), sampleValueA(field));
            secondValues.put(field.name(), sampleValueB(field));
        }

        Long cfgId = tx.execute(s -> {
            WorkflowConfiguration cfg = new WorkflowConfiguration();
            cfg.setName("Round-trip-" + System.nanoTime());
            cfg.setDefaultEntry(false);
            cfg.setCreatedAt(Instant.now());
            cfg.setUpdatedAt(Instant.now());
            return configurationRepository.save(cfg).getId();
        });
        assertNotNull(cfgId);

        tx.executeWithoutResult(s -> service.saveSelection(cfgId,
                List.of(key), Map.of(key, firstValues)));

        Map<String, String> first = tx.execute(s ->
                selectionRepository.findByConfigurationIdAndWorkflowKey(cfgId, key)
                        .orElseThrow().getParamsMap());
        for (var e : firstValues.entrySet()) {
            assertEquals(e.getValue(), first.get(e.getKey()),
                    "first save did not persist " + e.getKey());
        }

        tx.executeWithoutResult(s -> service.saveSelection(cfgId,
                List.of(key), Map.of(key, secondValues)));

        Map<String, String> second = tx.execute(s ->
                selectionRepository.findByConfigurationIdAndWorkflowKey(cfgId, key)
                        .orElseThrow().getParamsMap());
        for (var e : secondValues.entrySet()) {
            assertEquals(e.getValue(), second.get(e.getKey()),
                    "second save did not overwrite " + e.getKey()
                            + " (still: " + second.get(e.getKey()) + ")");
        }
        assertTrue(second.size() >= secondValues.size(),
                "expected all updated params present after second save");
    }

    @Test
    void saveSelection_droppingAWorkflow_removesItsParamRows() {
        PrWorkflow wf = pickWorkflowWithParams();
        String key = wf.key();
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (var field : wf.paramsSchema().fields()) {
            values.put(field.name(), sampleValueA(field));
        }

        Long cfgId = tx.execute(s -> {
            WorkflowConfiguration cfg = new WorkflowConfiguration();
            cfg.setName("Drop-" + System.nanoTime());
            cfg.setDefaultEntry(false);
            cfg.setCreatedAt(Instant.now());
            cfg.setUpdatedAt(Instant.now());
            return configurationRepository.save(cfg).getId();
        });

        tx.executeWithoutResult(s -> service.saveSelection(cfgId,
                List.of(key), Map.of(key, values)));

        tx.executeWithoutResult(s -> service.saveSelection(cfgId, List.of(), Map.of()));

        boolean present = tx.execute(s ->
                selectionRepository.findByConfigurationIdAndWorkflowKey(cfgId, key).isPresent());
        assertFalse(present, "selection should be gone after dropping it");
    }

    private static String sampleValueA(org.remus.giteabot.prworkflow.WorkflowParamField field) {
        return switch (field.type()) {
            case STRING, TEXT, SECRET -> field.defaultValue() != null && !field.defaultValue().isBlank()
                    ? field.defaultValue() : "alpha";
            case BOOLEAN -> "true";
            case INTEGER -> "1";
            case ENUM -> field.allowedValues().isEmpty() ? "alpha" : field.allowedValues().get(0).key();
        };
    }

    private static String sampleValueB(org.remus.giteabot.prworkflow.WorkflowParamField field) {
        return switch (field.type()) {
            case STRING, TEXT, SECRET -> "BRAVO-" + field.name();
            case BOOLEAN -> "false";
            case INTEGER -> "99";
            case ENUM -> field.allowedValues().isEmpty() ? "bravo"
                    : field.allowedValues().get(field.allowedValues().size() - 1).key();
        };
    }
}


