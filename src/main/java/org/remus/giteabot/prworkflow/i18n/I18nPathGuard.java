package org.remus.giteabot.prworkflow.i18n;

import org.remus.giteabot.prworkflow.readmesync.DocPathGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single source of truth for deciding whether a workspace-relative path is an
 * in-scope i18n locale file for the {@link I18nCoverageWorkflow}, and for the
 * glob matching that drives it.
 *
 * <p>Analogue of {@code DocPathGuard}. A path is accepted only when it BOTH:</p>
 * <ol>
 *   <li>carries a supported i18n file extension ({@code .properties} for
 *       {@code messages_*.properties}-style files, {@code .json} for
 *       {@code i18n/*.json}-style files), and</li>
 *   <li>matches at least one of the operator-configured include glob patterns
 *       (the same list defines the detection input scope and the allowed write
 *       scope).</li>
 * </ol>
 *
 * <p>Both the write-time guard ({@code I18nToolExecutor}) and the pre-commit
 * guard ({@code I18nCoverageService}) delegate here so the two layers can never
 * drift apart.</p>
 *
 * <p>Supported glob syntax (a small, dependency-free subset, identical to
 * {@code DocPathGuard}):</p>
 * <ul>
 *   <li>{@code **} — matches any number of path segments (including {@code /});</li>
 *   <li>{@code *} — matches any run of characters within a single segment
 *       (does not cross {@code /});</li>
 *   <li>{@code ?} — matches a single non-{@code /} character;</li>
 *   <li>every other character is matched literally.</li>
 * </ul>
 */
public final class I18nPathGuard {

    private I18nPathGuard() {
    }

    /**
     * @param includePatterns operator-configured include globs (already split
     *                         into individual patterns); {@code null} / empty
     *                         means nothing is in scope
     * @param path             a workspace-relative path (forward or back slashes)
     * @return {@code true} when the path is a supported i18n file AND matches an
     *         include pattern
     */
    public static boolean isAllowedI18nPath(List<String> includePatterns, String path) {
        if (includePatterns == null || includePatterns.isEmpty() || path == null) {
            return false;
        }
        String normalized = normalize(path);
        if (normalized.isBlank() || !isSupportedI18nFile(normalized)) {
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

    /** {@code true} when the path carries a supported i18n filename extension. */
    public static boolean isSupportedI18nFile(String path) {
        return isProperties(path) || isJson(path);
    }

    /** {@code true} when the path is a {@code .properties} resource bundle. */
    public static boolean isProperties(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".properties");
    }

    /** {@code true} when the path is a {@code .json} translation file. */
    public static boolean isJson(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /**
     * Splits an operator-supplied include-patterns string (newline and/or comma
     * separated) into individual trimmed, non-blank glob patterns.
     */
    public static List<String> parsePatterns(String raw) {
        return DocPathGuard.parsePatterns(raw);
    }

    static String normalize(String path) {
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
     * {@code *} and {@code ?} stay within a single segment. Mirrors
     * {@code DocPathGuard.globToPattern} exactly.
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
