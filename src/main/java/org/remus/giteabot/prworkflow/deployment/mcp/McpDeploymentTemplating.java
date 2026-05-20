package org.remus.giteabot.prworkflow.deployment.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight {@code {placeholder}} substitution used by
 * {@link MCPDeploymentStrategy} to fill an operator-defined
 * {@code argsTemplate} JSON document with values from a deployment request.
 *
 * <p>String leaves get the substitution; numeric, boolean and nested object
 * / array leaves pass through unchanged (with deep recursion). Missing
 * placeholders are left literal so the operator can spot them in tool
 * logs.</p>
 *
 * <p>This is intentionally <em>not</em> a Spring bean: pure-function
 * utility, trivially testable in isolation.</p>
 */
public final class McpDeploymentTemplating {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    private McpDeploymentTemplating() {
        // utility
    }

    /**
     * Substitutes every {@code {key}} occurrence in {@code template} with
     * the corresponding {@code values} entry. {@code null} values become an
     * empty string. Returns the original string when {@code template} is
     * {@code null}.
     */
    public static String substitute(String template, Map<String, ?> values) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 32);
        while (m.find()) {
            String key = m.group(1);
            Object replacement = values == null ? null : values.get(key);
            String replacementText = replacement == null ? m.group() : replacement.toString();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacementText));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Returns a deep copy of {@code template} with every string leaf
     * substituted via {@link #substitute(String, Map)}. Numbers, booleans
     * and {@code null} pass through. {@link Map} and {@link List} branches
     * are recursed; other reference types are returned as-is.
     */
    public static Object substituteDeep(Object template, Map<String, ?> values) {
        switch (template) {
            case null -> {
                return null;
            }
            case String s -> {
                return substitute(s, values);
            }
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>(map.size());
                for (Entry<?, ?> e : map.entrySet()) {
                    out.put(String.valueOf(e.getKey()), substituteDeep(e.getValue(), values));
                }
                return out;
            }
            case List<?> list -> {
                List<Object> out = new java.util.ArrayList<>(list.size());
                for (Object element : list) {
                    out.add(substituteDeep(element, values));
                }
                return out;
            }
            default -> {
            }
        }
        return template;
    }
}
