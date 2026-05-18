package org.remus.giteabot.prworkflow.config;

import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowRegistry;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the available {@link PrWorkflow} catalog for the admin UI and
 * persists per-configuration selections + per-selection params. Mirrors
 * {@link org.remus.giteabot.systemsettings.BotToolSelectionService}.
 *
 * <p>Workflows that are no longer registered (e.g. removed in a release) are
 * kept on the configuration so manual selections survive upgrades, but they
 * are surfaced in {@link #loadAvailableWorkflows(Long)} with a {@code null}
 * {@link WorkflowSelectionRow#prWorkflow()} for the UI to flag.</p>
 */
@Service
@Transactional
public class WorkflowSelectionService {

    private final WorkflowConfigurationRepository configurationRepository;
    private final WorkflowSelectionRepository selectionRepository;
    private final PrWorkflowRegistry workflowRegistry;
    private final WorkflowParamsValidator paramsValidator;

    public WorkflowSelectionService(WorkflowConfigurationRepository configurationRepository,
                                    WorkflowSelectionRepository selectionRepository,
                                    PrWorkflowRegistry workflowRegistry,
                                    WorkflowParamsValidator paramsValidator) {
        this.configurationRepository = configurationRepository;
        this.selectionRepository = selectionRepository;
        this.workflowRegistry = workflowRegistry;
        this.paramsValidator = paramsValidator;
    }

    @Transactional(readOnly = true)
    public List<WorkflowSelectionRow> loadAvailableWorkflows(Long configurationId) {
        requireConfiguration(configurationId);
        Map<String, WorkflowSelection> persistedByKey = new LinkedHashMap<>();
        for (WorkflowSelection persisted : selectionRepository.findByConfigurationId(configurationId)) {
            persistedByKey.put(persisted.getWorkflowKey(), persisted);
        }

        Map<String, WorkflowSelectionRow> rows = new LinkedHashMap<>();
        for (PrWorkflow workflow : workflowRegistry.all()) {
            WorkflowSelection persisted = persistedByKey.get(workflow.key());
            rows.put(workflow.key(), new WorkflowSelectionRow(
                    workflow.key(),
                    workflow.displayName(),
                    workflow.category().name(),
                    workflow,
                    persisted != null,
                    persisted != null ? persisted.getParamsJson() : null));
        }
        // Persisted-but-unknown workflows — keep them visible so admins can drop them.
        for (WorkflowSelection persisted : persistedByKey.values()) {
            if (!rows.containsKey(persisted.getWorkflowKey())) {
                rows.put(persisted.getWorkflowKey(), new WorkflowSelectionRow(
                        persisted.getWorkflowKey(),
                        persisted.getWorkflowKey() + " (not registered)",
                        "UNKNOWN",
                        null,
                        true,
                        persisted.getParamsJson()));
            }
        }
        return List.copyOf(rows.values());
    }

    @Transactional(readOnly = true)
    public List<WorkflowSelectionRow> loadSelectedWorkflows(Long configurationId) {
        return loadAvailableWorkflows(configurationId).stream()
                .filter(WorkflowSelectionRow::selected)
                .toList();
    }

    /**
     * Replaces all selections for the given configuration with the given
     * subset. {@code workflowParams} maps {@code workflowKey -> raw JSON
     * (or empty/blank for "no params")} and is validated against the
     * registered workflow's {@link PrWorkflow#paramsSchema()}.
     *
     * @throws IllegalArgumentException when params validation fails, with
     *         per-workflow error messages.
     */
    public void saveSelection(Long configurationId,
                              List<String> selectedWorkflowKeys,
                              Map<String, String> workflowParams) {
        WorkflowConfiguration configuration = requireConfiguration(configurationId);

        Set<String> requested = new LinkedHashSet<>();
        if (selectedWorkflowKeys != null) {
            for (String raw : selectedWorkflowKeys) {
                if (raw == null) {
                    continue;
                }
                String normalised = raw.trim().toLowerCase();
                if (!normalised.isEmpty()) {
                    requested.add(normalised);
                }
            }
        }

        List<String> errors = new ArrayList<>();
        Map<String, String> existingParams = new LinkedHashMap<>();
        for (WorkflowSelection row : selectionRepository.findByConfigurationId(configurationId)) {
            existingParams.put(row.getWorkflowKey(), row.getParamsJson());
        }

        List<WorkflowSelection> replacement = new ArrayList<>();
        for (String key : requested) {
            Optional<PrWorkflow> registered = workflowRegistry.find(key);
            WorkflowSelection row = new WorkflowSelection();
            row.setConfiguration(configuration);
            row.setWorkflowKey(key);

            String rawParams = workflowParams != null ? workflowParams.get(key) : null;
            if (rawParams == null) {
                rawParams = existingParams.get(key);
            }
            if (registered.isPresent()) {
                try {
                    row.setParamsJson(paramsValidator.validateAndCanonicalise(
                            rawParams, registered.get().paramsSchema()));
                } catch (IllegalArgumentException e) {
                    errors.add("Workflow '" + registered.get().displayName() + "': " + e.getMessage());
                    continue;
                }
            } else {
                // Unregistered workflow — keep the raw payload as-is so a re-install can re-validate.
                row.setParamsJson(rawParams != null ? rawParams : "{}");
            }
            replacement.add(row);
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        selectionRepository.deleteByConfigurationId(configurationId);
        selectionRepository.saveAll(replacement);
    }

    /**
     * Adds (or replaces) a single workflow selection on the configuration.
     * Used by the admin UI and by callers that want to programmatically
     * enable an additional workflow on an existing configuration.
     */
    public void enableWorkflow(Long configurationId, String workflowKey, String paramsJson) {
        WorkflowConfiguration configuration = requireConfiguration(configurationId);
        PrWorkflow workflow = workflowRegistry.require(workflowKey);
        String canonical = paramsValidator.validateAndCanonicalise(paramsJson, workflow.paramsSchema());
        Optional<WorkflowSelection> existing =
                selectionRepository.findByConfigurationIdAndWorkflowKey(configurationId, workflowKey);
        WorkflowSelection row = existing.orElseGet(WorkflowSelection::new);
        row.setConfiguration(configuration);
        row.setWorkflowKey(workflowKey);
        row.setParamsJson(canonical);
        selectionRepository.save(row);
    }

    /**
     * Returns the persisted parameter map for the given workflow on the
     * given configuration. Empty map when no selection exists or the params
     * payload is blank.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolveParams(Long configurationId, String workflowKey) {
        if (configurationId == null || workflowKey == null) {
            return Map.of();
        }
        return selectionRepository.findByConfigurationIdAndWorkflowKey(configurationId, workflowKey)
                .map(s -> paramsValidator.parseToMap(s.getParamsJson()))
                .orElseGet(Map::of);
    }

    /**
     * Stable order list of workflow keys enabled on the configuration. The
     * orchestrator's {@code runAll} iterates this list in order.
     */
    @Transactional(readOnly = true)
    public List<String> enabledWorkflowKeys(Long configurationId) {
        if (configurationId == null) {
            return List.of();
        }
        return selectionRepository.findByConfigurationIdOrderByWorkflowKeyAsc(configurationId).stream()
                .map(WorkflowSelection::getWorkflowKey)
                .toList();
    }

    /**
     * Helper for the bot Details modal: returns one row per enabled workflow
     * with its display name, category and persisted (masked) params.
     */
    @Transactional(readOnly = true)
    public List<WorkflowSelectionRow> describeSelections(Long configurationId) {
        if (configurationId == null) {
            return List.of();
        }
        return loadSelectedWorkflows(configurationId);
    }

    /**
     * Mask {@link WorkflowParamField.ParamType#SECRET} values for display in
     * the bot Details modal.
     */
    public Map<String, Object> maskSecrets(String workflowKey, String paramsJson) {
        Map<String, Object> values = paramsValidator.parseToMap(paramsJson);
        Optional<PrWorkflow> workflow = workflowRegistry.find(workflowKey);
        if (workflow.isEmpty()) {
            return values;
        }
        Map<String, Object> masked = new LinkedHashMap<>(values);
        for (WorkflowParamField field : workflow.get().paramsSchema().fields()) {
            if (field.type() == WorkflowParamField.ParamType.SECRET && masked.containsKey(field.name())) {
                Object current = masked.get(field.name());
                if (current != null && !current.toString().isBlank()) {
                    masked.put(field.name(), "********");
                }
            }
        }
        return masked;
    }

    private WorkflowConfiguration requireConfiguration(Long id) {
        return configurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
    }
}

