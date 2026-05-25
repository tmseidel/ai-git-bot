package org.remus.giteabot.prworkflow;

import java.util.List;
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
 * long-form text, secrets that should be masked in the UI, and enumerated
 * choices rendered as radio-button groups) without dragging in a full schema
 * engine for the per-workflow settings.</p>
 *
 * @param name          JSON key under which the value is stored
 *                      (lower-camel-case)
 * @param label         human-readable label shown in the admin UI
 * @param type          primitive type used for HTML input rendering and
 *                      validation
 * @param required      when {@code true}, the field must be present and
 *                      non-blank
 * @param defaultValue  default applied when the params JSON omits the key
 *                      (may be {@code null})
 * @param description   short hint shown beneath the input
 * @param allowedValues ordered list of permitted choices; non-empty only when
 *                      {@code type == }{@link ParamType#ENUM}
 */
public record WorkflowParamField(
        String name,
        String label,
        ParamType type,
        boolean required,
        String defaultValue,
        String description,
        List<EnumOption> allowedValues) {

    public WorkflowParamField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        if (type == ParamType.ENUM && allowedValues.isEmpty()) {
            throw new IllegalArgumentException(
                    "ENUM field '" + name + "' must define at least one allowedValues option");
        }
        if (type != ParamType.ENUM && !allowedValues.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedValues is only supported for ENUM fields, but field '" + name
                            + "' has type " + type);
        }
        for (EnumOption opt : allowedValues) {
            Objects.requireNonNull(opt, "allowedValues entry must not be null (field '" + name + "')");
            if (opt.key() == null || opt.key().isBlank()) {
                throw new IllegalArgumentException(
                        "allowedValues option key must not be blank (field '" + name + "')");
            }
            if (opt.label() == null || opt.label().isBlank()) {
                throw new IllegalArgumentException(
                        "allowedValues option label must not be blank (field '" + name
                                + "', key '" + opt.key() + "')");
            }
        }
    }

    /**
     * Convenience constructor for non-{@link ParamType#ENUM ENUM} fields that
     * takes a {@link WorkflowParamName} enum value for compile-time key safety.
     */
    public WorkflowParamField(WorkflowParamName name,
                              String label,
                              ParamType type,
                              boolean required,
                              String defaultValue,
                              String description) {
        this(Objects.requireNonNull(name, "name").key(),
                label, type, required, defaultValue, description, List.of());
    }

    /**
     * Convenience constructor for {@link ParamType#ENUM ENUM} fields.
     * Automatically sets {@code type = ENUM}.
     */
    public WorkflowParamField(WorkflowParamName name,
                              String label,
                              boolean required,
                              String defaultValue,
                              String description,
                              List<EnumOption> allowedValues) {
        this(Objects.requireNonNull(name, "name").key(),
                label, ParamType.ENUM, required, defaultValue, description, allowedValues);
    }

    /**
     * One selectable option for a field of type {@link ParamType#ENUM}.
     *
     * @param key         persisted string value (e.g. {@code "ephemeral"})
     * @param label       short human-readable label shown next to the radio button
     * @param description optional longer explanation shown as helper text
     */
    public record EnumOption(String key, String label, String description) {

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
        SECRET,
        /** Enumerated choice rendered as a radio-button group in the UI. */
        ENUM
    }
}

