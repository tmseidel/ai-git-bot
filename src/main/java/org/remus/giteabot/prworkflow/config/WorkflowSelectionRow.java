package org.remus.giteabot.prworkflow.config;

import org.remus.giteabot.prworkflow.PrWorkflow;

import java.util.Map;

/**
 * Row in the workflow-selection UI. Mirrors
 * {@link org.remus.giteabot.systemsettings.BotToolSelectionRow}.
 *
 * @param workflowKey     stable lower-case identifier from {@code PrWorkflow.key()}
 * @param displayName     human-readable name shown in the admin UI
 * @param category        snapshot of {@link org.remus.giteabot.prworkflow.PrWorkflowCategory}
 *                        or {@code "UNKNOWN"} for orphaned rows
 * @param prWorkflow      the registered workflow bean (or {@code null} if the
 *                        row references a workflow that is no longer registered)
 * @param selected        whether this workflow is enabled in the configuration
 * @param persistedParams persisted parameter values as a {@code name -> value}
 *                        map; never {@code null} (empty when the row has no
 *                        params). Form templates use this map to prefill the
 *                        inputs so the user sees the values they previously
 *                        saved instead of the schema defaults.
 */
public record WorkflowSelectionRow(
        String workflowKey,
        String displayName,
        String category,
        PrWorkflow prWorkflow,
        boolean selected,
        Map<String, String> persistedParams) {
}
