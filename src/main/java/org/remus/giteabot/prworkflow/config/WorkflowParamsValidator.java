package org.remus.giteabot.prworkflow.config;

import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validates and normalises workflow parameter maps against the
 * {@link WorkflowParamsSchema} declared by the corresponding
 * {@link org.remus.giteabot.prworkflow.PrWorkflow}.
 *
 * <p>Behaviour:</p>
 * <ul>
 *     <li>Values are kept as plain {@link String}s — they are persisted as
 *     plain strings in {@code workflow_selection_params.value} and only
 *     coerced to typed Java objects on read via {@link #typed(Map, WorkflowParamsSchema)}.</li>
 *     <li>Missing or blank values for non-required fields are filled in from
 *     {@link WorkflowParamField#defaultValue()} when present, otherwise
 *     dropped.</li>
 *     <li>Required fields must be present and non-blank; otherwise an
 *     {@link IllegalArgumentException} with a human-friendly message is
 *     thrown (errors for all fields are aggregated into a single exception).</li>
 *     <li>Type-checked values are normalised (e.g. {@code "On"} → {@code "true"},
 *     {@code "42 "} → {@code "42"}) so the persisted form is canonical.</li>
 *     <li>Unknown keys (not declared in the schema) are silently dropped — the
 *     UI never submits them, but old rows after a workflow downgrade should
 *     not break.</li>
 * </ul>
 */
@Component
public class WorkflowParamsValidator {

    /**
     * Validates the given raw form values against the schema and returns a
     * canonical, ordered map suitable for persistence on
     * {@link WorkflowSelection}. Empty result when the schema declares no
     * fields.
     *
     * @throws IllegalArgumentException when validation fails (aggregated message)
     */
    public Map<String, String> validate(Map<String, String> raw, WorkflowParamsSchema schema) {
        Map<String, String> normalised = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (WorkflowParamField field : schema.fields()) {
            String value = raw == null ? null : raw.get(field.name());
            if (value == null || value.isBlank()) {
                if (field.defaultValue() != null && !field.defaultValue().isBlank()) {
                    value = field.defaultValue();
                } else if (field.required()) {
                    errors.add("Parameter '" + field.label() + "' is required");
                    continue;
                } else {
                    continue;
                }
            }
            String coerced = coerce(value.trim(), field, errors);
            if (coerced != null) {
                normalised.put(field.name(), coerced);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return normalised;
    }

    /**
     * Type-coerces a persisted {@code name -> String} parameter map into a
     * runtime {@code name -> typed Object} map according to the schema. Unknown
     * keys are passed through as-is so older payloads after a workflow
     * downgrade do not break consumers.
     */
    public Map<String, Object> typed(Map<String, String> raw, WorkflowParamsSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        Map<String, WorkflowParamField> byName = new LinkedHashMap<>();
        if (schema != null) {
            for (WorkflowParamField f : schema.fields()) {
                byName.put(f.name(), f);
            }
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            WorkflowParamField field = byName.get(entry.getKey());
            String v = entry.getValue();
            if (field == null) {
                out.put(entry.getKey(), v);
                continue;
            }
            out.put(entry.getKey(), toTyped(v, field));
        }
        return out;
    }

    /**
     * Returns a copy of {@code raw} with any value mapped to a
     * {@link WorkflowParamField.ParamType#SECRET} field replaced by a fixed
     * mask. Used by the bot Details modal.
     */
    public Map<String, Object> maskSecrets(Map<String, String> raw, WorkflowParamsSchema schema) {
        Map<String, Object> masked = new LinkedHashMap<>(typed(raw, schema));
        if (schema == null) {
            return masked;
        }
        for (WorkflowParamField field : schema.fields()) {
            if (field.type() == WorkflowParamField.ParamType.SECRET && masked.containsKey(field.name())) {
                Object current = masked.get(field.name());
                if (current != null && !current.toString().isBlank()) {
                    masked.put(field.name(), "********");
                }
            }
        }
        return masked;
    }

    private String coerce(String text, WorkflowParamField field, List<String> errors) {
        return switch (field.type()) {
            case STRING, TEXT, SECRET -> text;
            case ENUM -> {
                List<WorkflowParamField.EnumOption> opts = field.allowedValues();
                if (!opts.isEmpty()) {
                    java.util.Optional<String> canonical = opts.stream()
                            .map(WorkflowParamField.EnumOption::key)
                            .filter(k -> k.equalsIgnoreCase(text))
                            .findFirst();
                    if (canonical.isPresent()) {
                        yield canonical.get();   // persist the canonical casing
                    }
                    errors.add("Parameter '" + field.label() + "' must be one of: "
                            + opts.stream().map(WorkflowParamField.EnumOption::key)
                                    .collect(Collectors.joining(", ")));
                    yield null;
                }
                yield text;
            }
            case BOOLEAN -> {
                if (text.equalsIgnoreCase("true") || text.equals("1") || text.equalsIgnoreCase("on")) {
                    yield "true";
                }
                if (text.equalsIgnoreCase("false") || text.equals("0") || text.equalsIgnoreCase("off")
                        || text.isEmpty()) {
                    yield "false";
                }
                errors.add("Parameter '" + field.label() + "' must be a boolean (true/false)");
                yield null;
            }
            case INTEGER -> {
                try {
                    yield Long.toString(Long.parseLong(text));
                } catch (NumberFormatException nfe) {
                    errors.add("Parameter '" + field.label() + "' must be an integer");
                    yield null;
                }
            }
        };
    }

    private Object toTyped(String value, WorkflowParamField field) {
        if (value == null) {
            return null;
        }
        return switch (field.type()) {
            case STRING, TEXT, SECRET, ENUM -> value;
            case BOOLEAN -> value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("on");
            case INTEGER -> {
                try {
                    yield Long.parseLong(value.trim());
                } catch (NumberFormatException nfe) {
                    yield value;
                }
            }
        };
    }
}
