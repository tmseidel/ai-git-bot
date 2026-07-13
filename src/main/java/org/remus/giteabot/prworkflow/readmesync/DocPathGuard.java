package org.remus.giteabot.prworkflow.readmesync;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single source of truth for deciding whether a workspace-relative path is an
 * allowed <em>documentation</em> target for the {@link ReadmeSyncWorkflow}.
 *
 * <p>Analogue of {@code UnitTestPathGuard}. A path is accepted only when it
 * BOTH:</p>
 * <ol>
 *   <li>is a Markdown file ({@code .md} / {@code .markdown}, case-insensitive) —
 *       the workflow only ever produces Markdown outputs, and</li>
 *   <li>matches at least one of the operator-configured include glob patterns
 *       (the same list defines the documentation input scope and the allowed
 *       output scope).</li>
 * </ol>
 *
 * <p>Both the write-time guard ({@code ReadmeSyncToolExecutor}) and the
 * pre-commit guard ({@code ReadmeSyncService}) delegate here so the two layers
 * can never drift apart.</p>
 *
 * <p>Supported glob syntax (a small, dependency-free subset):</p>
 * <ul>
 *   <li>{@code **} — matches any number of path segments (including {@code /});</li>
 *   <li>{@code *} — matches any run of characters within a single segment
 *       (does not cross {@code /});</li>
 *   <li>{@code ?} — matches a single non-{@code /} character;</li>
 *   <li>every other character is matched literally.</li>
 * </ul>
 */
public final class DocPathGuard {

    private DocPathGuard() {
    }

    /**
     * @param includePatterns operator-configured include globs (already split
     *                         into individual patterns); {@code null} / empty
     *                         means nothing is in scope
     * @param path             a workspace-relative path (forward or back slashes)
     * @return {@code true} when the path is Markdown AND matches an include pattern
     */
    public static boolean isAllowedDocPath(List<String> includePatterns, String path) {
        if (includePatterns == null || includePatterns.isEmpty() || path == null) {
            return false;
        }
        String normalized = normalize(path);
        if (normalized.isBlank() || !isMarkdown(normalized)) {
            return false;
        }
        for (String pattern : includePatterns) {
            String p = normalize(pattern);
            if (!p.isBlank() && globToPattern(p).matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} when the path carries a Markdown filename extension. */
    public static boolean isMarkdown(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    /**
     * Splits an operator-supplied include-patterns string (newline and/or
     * comma separated) into individual trimmed, non-blank glob patterns.
     */
    public static List<String> parsePatterns(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split("[,\\r\\n]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Compiles a glob into a regex. {@code **} matches across path separators;
     * {@code *} and {@code ?} stay within a single segment.
     *
     * <p>The {@code **}{@code /} construct matches <em>zero or more</em> whole
     * path segments, so {@code doc/**}{@code /*.md} matches both
     * {@code doc/README.md} (zero intermediate segments) and
     * {@code doc/sub/install.md}. A bare {@code **} not followed by {@code /}
     * matches any run of characters including {@code /}.</p>
     */
    static Pattern globToPattern(String glob) {
        StringBuilder sb = new StringBuilder("(?i)");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++; // consume the second '*'
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            // "**/" matches zero or more whole path segments
                            sb.append("(?:.*/)?");
                            i++; // consume the trailing '/'
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
            i++;
        }
        return Pattern.compile(sb.toString());
    }
}
