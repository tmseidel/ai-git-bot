package org.remus.giteabot.learning.knowledge;

import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The path restriction of a lesson revision: repository-relative glob patterns, or none at all for
 * a lesson that applies to every agentic review of its repository.
 *
 * <p>Patterns come from a {@code paths:} line a maintainer writes, so they are validated before
 * anything is stored: relative, no {@code ..}, no backslashes, valid glob syntax, bounded. The
 * resolved form is what the bot shows before activation, so a typo cannot quietly widen a lesson to
 * the whole repository — an empty restriction is only ever the explicit absence of one.</p>
 */
public final class LessonPathScope {

    public static final int MAX_PATTERNS = 10;
    public static final int MAX_PATTERN_CHARS = 200;

    /** What {@code paths: **} means: no restriction. */
    private static final String EVERYTHING = "**";

    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");

    private LessonPathScope() {
    }

    /**
     * Normalizes and validates the text of a {@code paths:} line.
     *
     * @return the patterns in their stored form; empty means repository-wide
     * @throws LessonRejectedException naming the first unusable pattern
     */
    public static List<String> parse(String line) {
        Set<String> patterns = new LinkedHashSet<>();
        if (line == null || line.isBlank()) {
            throw new LessonRejectedException("invalid-paths",
                    "`paths:` needs at least one pattern, for example `paths: src/main/java/**`. "
                            + "Use `paths: **` for the whole repository.");
        }
        for (String token : SEPARATORS.split(line.trim())) {
            if (token.isEmpty()) {
                continue;
            }
            String pattern = token.startsWith("./") ? token.substring(2) : token;
            validate(pattern);
            patterns.add(pattern);
        }
        if (patterns.size() > MAX_PATTERNS) {
            throw new LessonRejectedException("invalid-paths",
                    "A lesson can name at most " + MAX_PATTERNS + " path patterns.");
        }
        if (patterns.contains(EVERYTHING)) {
            return List.of();
        }
        return List.copyOf(patterns);
    }

    /** Stored column form: one pattern per line, {@code null} for repository-wide. */
    public static String serialize(List<String> patterns) {
        return patterns == null || patterns.isEmpty() ? null : String.join("\n", patterns);
    }

    public static List<String> deserialize(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : stored.split("\n")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Whether {@code next} applies to anything {@code previous} did not: dropping a restriction,
     * adding a pattern, or swapping one. Any such change needs a new candidate revision and a fresh
     * activation, which the service guarantees by never editing a revision in place.
     */
    public static boolean expands(List<String> previous, List<String> next) {
        if (next.isEmpty()) {
            return !previous.isEmpty();
        }
        return !previous.isEmpty() && !new HashSet<>(previous).containsAll(next);
    }

    private static void validate(String pattern) {
        String problem = null;
        if (pattern.length() > MAX_PATTERN_CHARS) {
            problem = "is longer than " + MAX_PATTERN_CHARS + " characters";
        } else if (pattern.startsWith("/") || pattern.matches("^[A-Za-z]:.*")) {
            problem = "is absolute; patterns are relative to the repository root";
        } else if (pattern.contains("\\")) {
            problem = "contains a backslash; use `/` between directories";
        } else if (pattern.equals("..") || pattern.startsWith("../") || pattern.contains("/../")
                || pattern.endsWith("/..")) {
            problem = "leaves the repository with `..`";
        } else if (pattern.chars().anyMatch(Character::isISOControl)) {
            problem = "contains control characters";
        } else {
            try {
                FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            } catch (IllegalArgumentException e) {
                problem = "is not a valid glob pattern";
            }
        }
        if (problem != null) {
            throw new LessonRejectedException("invalid-paths",
                    "Path pattern `" + pattern + "` " + problem + ".");
        }
    }
}
