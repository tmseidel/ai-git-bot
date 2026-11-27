package org.remus.giteabot.review.structured;

import java.util.Locale;
import java.util.Map;

/**
 * Normalises the free-text finding category the model chooses, so "Auth", "authorization" and
 * "security" count as one category on the Review quality page and in finding matching.
 */
public final class FindingCategories {

    /** Category of a finding without a usable category. */
    public static final String UNCATEGORIZED = "uncategorized";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("auth", "security"),
            Map.entry("authentication", "security"),
            Map.entry("authorization", "security"),
            Map.entry("authorisation", "security"),
            Map.entry("vulnerability", "security"),
            Map.entry("bug", "correctness"),
            Map.entry("logic", "correctness"),
            Map.entry("logic-error", "correctness"),
            Map.entry("errors", "error-handling"),
            Map.entry("exception-handling", "error-handling"),
            Map.entry("test", "testing"),
            Map.entry("tests", "testing"),
            Map.entry("test-coverage", "testing"),
            Map.entry("perf", "performance"),
            Map.entry("doc", "documentation"),
            Map.entry("docs", "documentation"),
            Map.entry("readability", "maintainability"),
            Map.entry("code-quality", "maintainability"),
            Map.entry("style", "maintainability"));

    private FindingCategories() {
    }

    /** Lower case, words joined by {@code -}, aliases mapped; blank becomes {@link #UNCATEGORIZED}. */
    public static String normalize(String category) {
        if (category == null || category.isBlank()) {
            return UNCATEGORIZED;
        }
        String key = category.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_/]+", "-")
                .replaceAll("[^a-z0-9-]", "").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (key.isEmpty()) {
            return UNCATEGORIZED;
        }
        return ALIASES.getOrDefault(key, key);
    }
}
