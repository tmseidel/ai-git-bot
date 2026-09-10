package org.remus.giteabot.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates a bot's {@code branchFilter} (a gobwas/glob-style branch/ref
 * allowlist) against an incoming webhook's pull-request <em>target</em> branch
 * (the PR base ref, e.g. {@code releases/1.2} in a git-flow setup).
 *
 * <p>The filter is a comma-separated list of glob patterns. It is empty or
 * {@code *} (or {@code **}) by default, which allows every branch/tag. A
 * pattern may use the short branch name ({@code develop}) or a full ref name
 * ({@code refs/heads/develop}); both forms are matched so that
 * {@code refs/heads/} / {@code refs/tags/} prefixes work as documented. See
 * <a href="https://pkg.go.dev/github.com/gobwas/glob#Compile">gobwas/glob</a>
 * for the pattern syntax: {@code *} (any run, including {@code /}),
 * {@code ?} (any single char), {@code [abc]} / {@code [a-z]} (char classes),
 * {@code {a,b}} (alternation), {@code **}.
 *
 * <p>This is the single enforcement point for PR-workflow triggering
 * ({@code BotWebhookService#reviewPullRequest}); issue/agent workflows are
 * unaffected. Matching runs synchronously before the workflow starts, so a
 * non-matching event neither runs the workflow nor posts a PR comment.
 *
 * <p>Stateless and dependency-free (pure JDK) so it is trivially unit-testable
 * and free of Spring/ArchUnit coupling.
 */
public final class BranchFilter {

    private BranchFilter() {
    }

    /**
     * @param branchFilter the bot's configured filter (may be {@code null} or blank)
     * @param ref          the branch/ref to test (PR base/target ref, falling back to head)
     * @return {@code true} when the ref is allowed to start a PR workflow.
     *         A {@code null} or blank filter always allows (back-compat).
     */
    public static boolean matches(String branchFilter, String ref) {
        if (branchFilter == null || branchFilter.isBlank()) {
            return true;
        }
        String trimmed = branchFilter.trim();
        if ("*".equals(trimmed) || "**".equals(trimmed)) {
            return true;
        }
        return matchesAny(trimmed, ref);
    }

    /**
     * Splits a comma-separated pattern list and returns {@code true} when any
     * non-empty pattern matches the ref. Only top-level commas separate
     * patterns — commas inside a {@code {a,b}} alternation are part of the
     * pattern, not separators.
     */
    public static boolean matchesAny(String filter, String ref) {
        for (String part : topLevelPatterns(filter)) {
            String pattern = part.trim();
            if (!pattern.isEmpty() && match(pattern, ref)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a filter string on commas that are not nested inside a
     * {@code {..}} alternation, preserving alternation commas as part of the
     * pattern.
     */
    static List<String> topLevelPatterns(String filter) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < filter.length(); i++) {
            char ch = filter.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
            } else if (ch == ',' && depth == 0) {
                parts.add(filter.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(filter.substring(start));
        return parts;
    }

    /**
     * Matches a single glob pattern against the ref. The pattern is tested
     * against both the short ref and its full {@code refs/heads/} form, so
     * full-ref patterns such as {@code refs/heads/develop} work.
     */
    public static boolean match(String pattern, String ref) {
        if (ref == null || ref.isEmpty()) {
            return "*".equals(pattern) || "**".equals(pattern);
        }
        if (matchesGlob(pattern, ref)) {
            return true;
        }
        if (!ref.startsWith("refs/")) {
            return matchesGlob(pattern, "refs/heads/" + ref);
        }
        return false;
    }

    /**
     * Converts a gobwas/glob-style pattern to a regular expression and tests
     * whether it matches the whole value.
     */
    static boolean matchesGlob(String pattern, String value) {
        if (value == null) {
            return false;
        }
        return Pattern.compile(toRegex(pattern)).matcher(value).matches();
    }

    /** Anchored form of {@link #toRegexBody}. */
    static String toRegex(String glob) {
        return "^" + toRegexBody(glob) + "$";
    }

    /**
     * Glob → regex body (no anchors). Implements the subset of
     * <a href="https://pkg.go.dev/github.com/gobwas/glob">gobwas/glob</a>
     * that appears in branch/ref allowlists:
     * <ul>
     *   <li>{@code *} / {@code **} → {@code .*} (matches any run, including {@code /})</li>
     *   <li>{@code ?} → {@code .} (any single char)</li>
     *   <li>{@code [..]} / {@code [!..]} / {@code [^..]} → char class (range like {@code a-z} kept)</li>
     *   <li>{@code {a,b,c}} → non-capturing alternation</li>
     *   <li>other regex metacharacters are escaped literally</li>
     * </ul>
     */
    static String toRegexBody(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    // Both '*' and '**' match any run including '/'.
                    regex.append(".*");
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        i += 2;
                    } else {
                        i += 1;
                    }
                }
                case '?' -> {
                    regex.append('.');
                    i += 1;
                }
                case '[' -> {
                    // [a-z], [!a-z] / [^a-z] character class. Build the body in a
                    // local buffer first so the unterminated-class fallback (treat
                    // the '[' as a literal) stays clean.
                    int j = i + 1;
                    StringBuilder cls = new StringBuilder();
                    if (j < n && (glob.charAt(j) == '!' || glob.charAt(j) == '^')) {
                        cls.append('^');
                        j++;
                    }
                    boolean closed = false;
                    while (j < n) {
                        char ch = glob.charAt(j);
                        // A ']' closes the class only when the class is non-empty
                        // (the very first char after '[' is a literal ']').
                        if (ch == ']' && j > i + 1) {
                            closed = true;
                            break;
                        }
                        if (ch == '\\' && j + 1 < n) {
                            cls.append('\\');
                            cls.append(glob.charAt(j + 1));
                            j += 2;
                            continue;
                        }
                        // Inside a class only ']' and '\' are structural; copy the
                        // rest raw so ranges such as 'a-z' keep working.
                        cls.append(ch);
                        j++;
                    }
                    if (closed) {
                        regex.append('[').append(cls).append(']');
                        i = j + 1;
                    } else {
                        // Unterminated class — treat '[' as a literal.
                        regex.append("\\[");
                        i += 1;
                    }
                }
                case '{' -> {
                    // {a,b,c} alternation (no nesting — gobwas/glob default).
                    int j = i + 1;
                    while (j < n && glob.charAt(j) != '}') {
                        j++;
                    }
                    if (j < n) {
                        String inner = glob.substring(i + 1, j);
                        String[] alternatives = inner.split(",", -1);
                        regex.append("(?:");
                        for (int k = 0; k < alternatives.length; k++) {
                            if (k > 0) {
                                regex.append('|');
                            }
                            regex.append(toRegexBody(alternatives[k].trim()));
                        }
                        regex.append(")");
                        i = j + 1;
                    } else {
                        regex.append("\\{");
                        i += 1;
                    }
                }
                default -> {
                    String esc = regexMetaChar(c);
                    if (esc != null) {
                        regex.append(esc);
                    } else {
                        regex.append(c);
                    }
                    i += 1;
                }
            }
        }
        return regex.toString();
    }

    /** Escapes regex metacharacters that would otherwise change a literal segment's meaning. */
    private static String regexMetaChar(char c) {
        return switch (c) {
            case '.', '(', ')', '+', '^', '$', '\\' -> "\\" + c;
            default -> null;
        };
    }
}
