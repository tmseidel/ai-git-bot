package org.remus.giteabot.prworkflow;

import java.util.Objects;

/**
 * One typed parameter exposed by a {@link PrWorkflow} via
 * {@link PrWorkflow#paramsSchema()}. Used by the workflow-configuration UI
 * to render dynamic form fields and by
 * {@link org.remus.giteabot.prworkflow.config.WorkflowParamsValidator} to
 * validate persisted JSON before a run.
 *
 * <p>This is a deliberately small subset of JSON Schema: it covers the
 * primitive types operators actually need today (strings, booleans, integers,
 * long-form text, and secrets that should be masked in the UI) without
 * dragging in a full schema engine for the per-workflow settings.</p>
 *
 * @param name         JSON key under which the value is stored
 *                     (lower-camel-case)
 * @param label        human-readable label shown in the admin UI
 * @param type         primitive type used for HTML input rendering and
 *                     validation
 * @param required     when {@code true}, the field must be present and
 *                     non-blank
 * @param defaultValue default applied when the params JSON omits the key
 *                     (may be {@code null})
 * @param description  short hint shown beneath the input
 */
public record WorkflowParamField(
        String name,
        String label,
        ParamType type,
        boolean required,
        String defaultValue,
        String description) {

    public WorkflowParamField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Convenience constructor that takes a {@link WorkflowParamName} enum
     * value instead of a raw string. Use this from new code so the key
     * is checked at compile time:
     *
     * <pre>{@code
     * new WorkflowParamField(E2eTestParam.MAX_RETRIES, "Max retries per test",
     *         ParamType.INTEGER, false, "1", "...");
     * }</pre>
     */
    public WorkflowParamField(WorkflowParamName name,
                              String label,
                              ParamType type,
                              boolean required,
                              String defaultValue,
                              String description) {
        this(Objects.requireNonNull(name, "name").key(),
                label, type, required, defaultValue, description);
    }

    public enum ParamType {
        /** Single-line text. */
        STRING,
        /** Multi-line text (rendered as a textarea). */
        TEXT,
        /** Checkbox; persisted as {@code true} / {@code false}. */
        BOOLEAN,
        /** Whole number; persisted as a JSON number. */
        INTEGER,
        /** Like {@link #STRING} but masked in the Bot Details modal. */
        SECRET
    }
}

