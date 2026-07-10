package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips per-file sections from a unified diff whose file path matches an
 * operator-configured exclude pattern (glob or bare filename/extension).
 *
 * <p>Stateless and side-effect free. Splits the diff on {@code diff --git}
 * headers, drops any section whose new path (the {@code b/} side, or the
 * {@code a/} side for deletions) matches an exclude pattern, and re-joins the
 * survivors. An empty pattern list is a no-op — the diff is returned unchanged.</p>
 */
@Slf4j
public final class DiffFileFilter {

    private static final Pattern DIFF_HEADER = Pattern.compile(
            "^diff --git a/(.+?) b/(.+?)$", Pattern.MULTILINE);

    /** Parses a comma-separated pattern string into a trimmed, non-blank list. */
    public static List<String> parsePatterns(String csv) {
        List<String> patterns = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return patterns;
        }
        for (String raw : csv.split(",")) {
            String p = raw.trim();
            if (!p.isEmpty()) {
                patterns.add(p);
            }
        }
        return patterns;
    }

    /**
     * Removes every diff section whose file path matches a pattern.
     *
     * @param rawDiff  the full unified diff (may be {@code null}/blank)
     * @param patterns exclude patterns; when empty the diff is returned as-is
     * @return the filtered diff, or the original when nothing matched or input
     *         was not a recognizable diff
     */
    public static String filter(String rawDiff, List<String> patterns) {
        if (rawDiff == null || rawDiff.isBlank() || patterns == null || patterns.isEmpty()) {
            return rawDiff;
        }

        List<PathMatcher> matchers = compile(patterns);
        if (matchers.isEmpty()) {
            return rawDiff;
        }

        Matcher m = DIFF_HEADER.matcher(rawDiff);
        List<Integer> starts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            String bPath = m.group(2);
            String aPath = m.group(1);
            paths.add("/dev/null".equals(bPath) ? aPath : bPath);
        }
        if (starts.isEmpty()) {
            return rawDiff;
        }

        StringBuilder out = new StringBuilder(rawDiff.length());
        int excluded = 0;
        for (int i = 0; i < starts.size(); i++) {
            int segStart = starts.get(i);
            int segEnd = (i + 1 < starts.size()) ? starts.get(i + 1) : rawDiff.length();
            String path = paths.get(i);
            if (isExcluded(path, matchers)) {
                excluded++;
                log.debug("Excluding file from review diff: {}", path);
            } else {
                out.append(rawDiff, segStart, segEnd);
            }
        }

        if (excluded > 0) {
            log.info("Filtered {} file section(s) from review diff", excluded);
        }
        return out.toString();
    }

    private static List<PathMatcher> compile(List<String> patterns) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String p : patterns) {
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring invalid exclude pattern '{}': {}", p, e.getMessage());
            }
        }
        return matchers;
    }

    private static boolean isExcluded(String path, List<PathMatcher> matchers) {
        var full = Paths.get(path);
        var fileName = full.getFileName();
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(full)) {
                return true;
            }
            if (fileName != null && matcher.matches(fileName)) {
                return true;
            }
        }
        return false;
    }

    private DiffFileFilter() {
    }
}
