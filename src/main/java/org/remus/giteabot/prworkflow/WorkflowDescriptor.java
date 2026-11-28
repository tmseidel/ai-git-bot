package org.remus.giteabot.prworkflow;

import java.util.List;
import java.util.Map;

/**
 * Catalog-descriptor contract shared by the pluggable workflow SPIs
 * ({@link PrWorkflow} for pull-request events, {@code IssueWorkflow} for
 * issue-assigned/issue-comment events). The admin workflow-selection UI and
 * {@code WorkflowSelectionService} operate on this abstraction so both
 * workflow domains reuse one catalog/params infrastructure.
 */
public interface WorkflowDescriptor {

    /**
     * Stable, lowercase, kebab-case identifier persisted on
     * {@code workflow_selections.workflow_key}. Must be unique across all
     * registered workflows of the same SPI; collisions are rejected on
     * startup by the SPI's registry.
     */
    String key();

    /**
     * Human-readable name shown in the admin UI.
     */
    String displayName();

    /**
     * Short, human-readable summary (one or two sentences) of what the
     * workflow does and when it triggers, shown beneath the workflow name in
     * the workflow-selection UI.
     *
     * <p>The default implementation returns an empty string so the UI can
     * omit the description line for workflows that do not supply one.</p>
     */
    default String description() {
        return "";
    }

    /**
     * Declarative description of the parameters the workflow accepts. Used by
     * the workflow-configuration UI to render per-workflow form fields and
     * to validate persisted params on save/read.
     *
     * <p>The default implementation returns
     * {@link WorkflowParamsSchema#empty()} — appropriate for workflows that
     * do not expose any tunables.</p>
     */
    default WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.empty();
    }

    /**
     * Cross-field checks the per-field {@link #paramsSchema()} cannot express, run on admin saves,
     * programmatic enablement and configuration imports — an import must not reach a state the admin
     * form would refuse.
     *
     * <p>The default accepts everything the schema accepts.</p>
     *
     * @param params persisted string values, after schema validation where the caller applies it
     * @return field-specific error messages; empty when the parameters are consistent
     */
    default List<String> validateParams(Map<String, String> params) {
        return List.of();
    }
}
