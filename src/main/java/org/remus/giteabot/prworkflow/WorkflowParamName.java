package org.remus.giteabot.prworkflow;

/**
 * Compile-time-safe identifier for a {@link WorkflowParamField}'s
 * {@link WorkflowParamField#name() name}.
 *
 * <p>Each {@link PrWorkflow} typically declares an enum that implements
 * this interface (e.g. {@code E2eTestParam}) so the param keys used to
 * build the schema, to look values up from persisted params, and to
 * reference them from the workflow's runtime code all share a single
 * source of truth. Mistyping a name then becomes a compile error
 * instead of a silent default-value fallback at runtime.</p>
 *
 * <p>The {@link #key()} value is what hits both the persisted
 * {@code workflow_selection_params.name} column and the JSON form
 * payload, so existing rows do not need to be migrated — only the Java
 * call-sites are refactored to go through the enum.</p>
 */
public interface WorkflowParamName {

    /**
     * JSON / database key. Conventionally lower-camel-case to match the
     * existing persisted rows.
     */
    String key();
}

