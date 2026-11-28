package org.remus.giteabot.prworkflow.config;

import lombok.RequiredArgsConstructor;

import org.remus.giteabot.issueworkflow.IssueWorkflowRegistry;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowRegistry;
import org.remus.giteabot.prworkflow.WorkflowDescriptor;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
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
 * Loads the available workflow catalog for the admin UI and persists
 * per-configuration selections + per-selection params. Mirrors
 * {@link org.remus.giteabot.systemsettings.BotToolSelectionService}.
 *
 * <p>The catalog is scoped by the configuration's
 * {@link WorkflowConfigurationKind}: PR configurations list
 * {@link PrWorkflowRegistry} entries, ISSUE configurations list
 * {@link IssueWorkflowRegistry} entries. Both SPIs are exposed through the
 * shared {@link WorkflowDescriptor} abstraction, so selection, params
 * validation and the UI rows are identical for either kind.</p>
 *
 * <p>Workflows that are no longer registered (e.g. removed in a release) are
 * kept on the configuration so manual selections survive upgrades, but they
 * are surfaced in {@link #loadAvailableWorkflows(Long)} with a {@code null}
 * {@link WorkflowSelectionRow#workflow()} for the UI to flag.</p>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class WorkflowSelectionService {

    private final WorkflowConfigurationRepository configurationRepository;
    private final WorkflowSelectionRepository selectionRepository;
    private final PrWorkflowRegistry workflowRegistry;
    private final IssueWorkflowRegistry issueWorkflowRegistry;
    private final WorkflowParamsValidator paramsValidator;
    private final WorkflowTextResolver workflowTextResolver;

    @Transactional(readOnly = true)
    public List<WorkflowSelectionRow> loadAvailableWorkflows(Long configurationId) {
        WorkflowConfiguration configuration = requireConfiguration(configurationId);
        Map<String, WorkflowSelection> persistedByKey = new LinkedHashMap<>();
        for (WorkflowSelection persisted : selectionRepository.findByConfigurationId(configurationId)) {
            persistedByKey.put(persisted.getWorkflowKey(), persisted);
        }

        Map<String, WorkflowSelectionRow> rows = new LinkedHashMap<>();
        for (WorkflowDescriptor workflow : catalog(configuration.getKind())) {
            WorkflowSelection persisted = persistedByKey.get(workflow.key());
            rows.put(workflow.key(), new WorkflowSelectionRow(
                    workflow.key(),
                    workflowTextResolver.displayName(workflow),
                    workflowTextResolver.description(workflow),
                    categoryOf(workflow),
                    workflow,
                    workflowTextResolver.localizedSchema(workflow),
                    persisted != null,
                    persisted != null ? persisted.getParamsMap() : Map.of()));
        }
        // Persisted-but-unknown workflows — keep them visible so admins can drop them.
        for (WorkflowSelection persisted : persistedByKey.values()) {
            if (!rows.containsKey(persisted.getWorkflowKey())) {
                rows.put(persisted.getWorkflowKey(), new WorkflowSelectionRow(
                        persisted.getWorkflowKey(),
                        persisted.getWorkflowKey() + " (not registered)",
                        null,
                        "UNKNOWN",
                        null,
                        org.remus.giteabot.prworkflow.WorkflowParamsSchema.empty(),
                        true,
                        persisted.getParamsMap()));
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
     * subset. {@code workflowParams} maps {@code workflowKey -> (name -> value)}
     * and is validated against the registered workflow's
     * {@link WorkflowDescriptor#paramsSchema()} before each child row is
     * persisted.
     *
     * @throws IllegalArgumentException when params validation fails, with
     *         per-workflow error messages.
     */
    public void saveSelection(Long configurationId,
                              List<String> selectedWorkflowKeys,
                              Map<String, Map<String, String>> workflowParams) {
        WorkflowConfiguration configuration = requireConfiguration(configurationId);
        WorkflowConfigurationKind kind = configuration.getKind();

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
        Map<String, Map<String, String>> existingParams = new LinkedHashMap<>();
        for (WorkflowSelection row : selectionRepository.findByConfigurationId(configurationId)) {
            existingParams.put(row.getWorkflowKey(), row.getParamsMap());
        }

        List<WorkflowSelection> replacement = new ArrayList<>();
        for (String key : requested) {
            Optional<? extends WorkflowDescriptor> registered = findWorkflow(kind, key);
            WorkflowSelection row = new WorkflowSelection();
            row.setConfiguration(configuration);
            row.setWorkflowKey(key);

            Map<String, String> rawParams = workflowParams != null ? workflowParams.get(key) : null;
            if (rawParams == null) {
                rawParams = existingParams.get(key);
            }
            if (registered.isPresent()) {
                try {
                    Map<String, String> canonical = paramsValidator.validate(
                            rawParams, registered.get().paramsSchema());
                    List<String> crossField = registered.get().validateParams(canonical);
                    if (!crossField.isEmpty()) {
                        throw new IllegalArgumentException(String.join("; ", crossField));
                    }
                    row.replaceParams(canonical);
                } catch (IllegalArgumentException e) {
                    errors.add("Workflow '" + workflowTextResolver.displayName(registered.get()) + "': " + e.getMessage());
                    continue;
                }
            } else {
                // Unregistered workflow — keep the raw values as-is so a re-install can re-validate.
                row.replaceParams(rawParams);
            }
            replacement.add(row);
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        selectionRepository.deleteByConfigurationId(configurationId);
        // Force the DELETE to hit the DB before the inserts; otherwise
        // Hibernate's action-queue ordering can run INSERTs first and trip
        // the (configuration_id, workflow_key) UNIQUE index when the new
        // selection re-includes a previously-persisted workflow key. The
        // child params rows are removed by the DB-level ON DELETE CASCADE on
        // workflow_selection_params.
        selectionRepository.flush();
        selectionRepository.saveAll(replacement);
    }

    /**
     * Adds (or replaces) a single workflow selection on the configuration.
     * Used by the admin UI and by callers that want to programmatically
     * enable an additional workflow on an existing configuration. {@code
     * params} may be {@code null} for workflows without parameters.
     */
    public void enableWorkflow(Long configurationId, String workflowKey, Map<String, String> params) {
        WorkflowConfiguration configuration = requireConfiguration(configurationId);
        WorkflowDescriptor workflow = findWorkflow(configuration.getKind(), workflowKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No workflow registered for key '" + workflowKey + "'"));
        Map<String, String> canonical = paramsValidator.validate(params, workflow.paramsSchema());
        List<String> crossField = workflow.validateParams(canonical);
        if (!crossField.isEmpty()) {
            throw new IllegalArgumentException("Workflow '" + workflowTextResolver.displayName(workflow)
                    + "': " + String.join("; ", crossField));
        }
        Optional<WorkflowSelection> existing =
                selectionRepository.findByConfigurationIdAndWorkflowKey(configurationId, workflowKey);
        WorkflowSelection row = existing.orElseGet(WorkflowSelection::new);
        row.setConfiguration(configuration);
        row.setWorkflowKey(workflowKey);
        row.replaceParams(canonical);
        selectionRepository.save(row);
    }

    /**
     * Returns the persisted parameter map for the given workflow on the
     * given configuration, type-coerced according to the workflow's schema.
     * Empty map when no selection exists.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolveParams(Long configurationId, String workflowKey) {
        if (configurationId == null || workflowKey == null) {
            return Map.of();
        }
        // Kind comes from the repository (PR fallback) rather than the
        // selection's back-reference: tests construct selections without a
        // configuration attached.
        WorkflowConfigurationKind kind = configurationRepository.findById(configurationId)
                .map(WorkflowConfiguration::getKind)
                .orElse(WorkflowConfigurationKind.PR);
        return selectionRepository.findByConfigurationIdAndWorkflowKey(configurationId, workflowKey)
                .map(s -> paramsValidator.typed(s.getParamsMap(), schemaFor(kind, workflowKey)))
                .orElseGet(Map::of);
    }

    /**
     * Stable order list of workflow keys enabled on the configuration. The
     * orchestrators iterate this list in order.
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
     * with its display name, category and persisted (raw) params.
     */
    @Transactional(readOnly = true)
    public List<WorkflowSelectionRow> describeSelections(Long configurationId) {
        if (configurationId == null) {
            return List.of();
        }
        return loadSelectedWorkflows(configurationId);
    }

    /**
     * Builds a display-ready parameter map for the bot Details modal by
     * iterating every field in the schema. Persisted values take precedence;
     * absent fields are filled from defaults so the popup always lists all
     * available parameters. SECRET fields are masked.
     */
    public Map<String, Object> describeParams(String workflowKey, Map<String, String> raw) {
        return paramsValidator.describeParams(raw, schemaForAnyKind(workflowKey));
    }

    /**
     * Whether {@code fieldName} of the given workflow is a
     * {@link WorkflowParamField.ParamType#BOOLEAN} field. Used by the save
     * controller to apply checkbox ("true"-wins) semantics only to the
     * hidden+checkbox pattern and not to other multi-valued params.
     */
    public boolean isBooleanField(String workflowKey, String fieldName) {
        WorkflowParamsSchema schema = schemaForAnyKind(workflowKey);
        return schema != null && schema.fields().stream()
                .anyMatch(f -> f.name().equals(fieldName)
                        && f.type() == WorkflowParamField.ParamType.BOOLEAN);
    }

    /**
     * The registered catalog for the given kind: PR workflows for
     * {@link WorkflowConfigurationKind#PR}, issue-assigned workflows for
     * {@link WorkflowConfigurationKind#ISSUE}.
     */
    private List<? extends WorkflowDescriptor> catalog(WorkflowConfigurationKind kind) {
        return kind == WorkflowConfigurationKind.ISSUE
                ? issueWorkflowRegistry.all()
                : workflowRegistry.all();
    }

    private Optional<? extends WorkflowDescriptor> findWorkflow(WorkflowConfigurationKind kind, String key) {
        return kind == WorkflowConfigurationKind.ISSUE
                ? issueWorkflowRegistry.find(key)
                : workflowRegistry.find(key);
    }

    private String categoryOf(WorkflowDescriptor workflow) {
        return workflow instanceof PrWorkflow prWorkflow
                ? prWorkflow.category().name()
                : "ISSUE";
    }

    private WorkflowParamsSchema schemaFor(WorkflowConfigurationKind kind, String workflowKey) {
        return findWorkflow(kind, workflowKey)
                .map(WorkflowDescriptor::paramsSchema)
                .orElse(null);
    }

    /**
     * Schema lookup when only the workflow key is known (bot Details modal
     * endpoints). PR keys take precedence; issue-assigned keys live in a
     * separate namespace by convention ({@code issue-*}), so the two
     * registries are not expected to collide.
     */
    private WorkflowParamsSchema schemaForAnyKind(String workflowKey) {
        return workflowRegistry.find(workflowKey)
                .map(WorkflowDescriptor::paramsSchema)
                .or(() -> issueWorkflowRegistry.find(workflowKey).map(WorkflowDescriptor::paramsSchema))
                .orElse(null);
    }

    /**
     * Extracts the {@code params.<workflowKey>.<fieldName>} request
     * parameters submitted by the workflow-selection form into a
     * {@code workflowKey -> {fieldName -> value}} map — validated by
     * {@link #saveSelection} against each workflow's
     * {@link org.remus.giteabot.prworkflow.WorkflowParamsSchema}. Shared by
     * the PR and issue-assigned configuration controllers.
     *
     * <p>The dot separator is used (rather than the historic
     * {@code __} double-underscore) because Thymeleaf's expression
     * preprocessing eats any {@code __...__} pair from attribute values,
     * which silently mangled the field names so the controller never
     * received them.</p>
     */
    public Map<String, Map<String, String>> extractWorkflowParams(
            org.springframework.util.MultiValueMap<String, String> allParams,
            List<String> selectedKeys) {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        if (allParams != null) {
            for (Map.Entry<String, List<String>> entry : allParams.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("params.")) {
                    continue;
                }
                String rest = key.substring("params.".length());
                int sep = rest.indexOf('.');
                if (sep < 0) {
                    continue;
                }
                String workflowKey = rest.substring(0, sep);
                String fieldName = rest.substring(sep + 1);
                List<String> values = entry.getValue();
                if (values == null || values.isEmpty()) {
                    continue;
                }
                // A BOOLEAN field submits the hidden+checkbox pair — ["false"]
                // when unchecked, ["false","true"] when checked — so "true"
                // wins regardless of order. Any other field takes the last
                // submitted value; the "true"-wins rule must not leak to them.
                String effective = isBooleanField(workflowKey, fieldName)
                        ? (Boolean.toString(values.contains("true")))
                        : values.getLast();
                grouped.computeIfAbsent(workflowKey, k -> new LinkedHashMap<>())
                        .put(fieldName, effective);
            }
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            if (selectedKeys != null && !selectedKeys.contains(entry.getKey())) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private WorkflowConfiguration requireConfiguration(Long id) {
        return configurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
    }
}
