package org.remus.giteabot.review.structured;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which findings of a review continue a finding of the pull request's previous review, so
 * the Review quality page can tell resolved, persisting and new findings apart.
 *
 * <p>Two findings match when their first location is the same file (or both have none) and
 * <ul>
 *   <li>their titles share at least half of their words, or</li>
 *   <li>their categories agree, their line ranges overlap or lie within {@value #NEAR_LINES} lines
 *       of each other, and their titles share at least a fifth of their words.</li>
 * </ul>
 * Each finding matches at most one finding of the previous review; the most similar pairs win.
 * The rule is deliberately simple and explainable — a heuristic, not proof that the same problem
 * was meant.</p>
 */
public final class FindingMatcher {

    static final int NEAR_LINES = 15;
    static final double TITLE_MATCH = 0.5;
    static final double TITLE_NEAR_MATCH = 0.2;

    private static final Set<String> STOPWORDS = Set.of("the", "and", "for", "not", "with", "without",
            "are", "was", "were", "this", "that", "from", "into", "can", "may", "should", "does", "doesn",
            "when", "its", "has", "have", "but", "too", "via", "all", "any", "missing", "use", "uses");

    /** What matching needs to know about one finding. */
    public record Candidate(long id, Long originId, String category, String title, String path,
                            Integer startLine, Integer endLine) {

        /** The first finding of this finding's chain. */
        public long origin() {
            return originId != null ? originId : id;
        }
    }

    private FindingMatcher() {
    }

    /** The finding's fingerprint: a hash of its normalised path, category and title words. */
    public static String fingerprint(String path, String category, String title) {
        String key = normalizePath(path) + "|" + FindingCategories.normalize(category) + "|"
                + String.join(" ", new TreeSet<>(words(title)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** For each finding of {@code current} that continues one of {@code previous}: its id → that id. */
    public static Map<Long, Long> match(List<Candidate> current, List<Candidate> previous) {
        List<Pair> pairs = new ArrayList<>();
        for (Candidate now : current) {
            for (Candidate before : previous) {
                double score = score(now, before);
                if (score > 0) {
                    pairs.add(new Pair(now.id(), before.id(), score));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::score).reversed()
                .thenComparingLong(Pair::currentId).thenComparingLong(Pair::previousId));
        Map<Long, Long> matches = new HashMap<>();
        Set<Long> taken = new HashSet<>();
        for (Pair pair : pairs) {
            if (!matches.containsKey(pair.currentId()) && taken.add(pair.previousId())) {
                matches.put(pair.currentId(), pair.previousId());
            }
        }
        return matches;
    }

    /** 0 when the findings do not match, otherwise how similar they are (higher is closer). */
    static double score(Candidate a, Candidate b) {
        if (!normalizePath(a.path()).equals(normalizePath(b.path()))) {
            return 0;
        }
        double titles = jaccard(words(a.title()), words(b.title()));
        boolean sameCategory = FindingCategories.normalize(a.category())
                .equals(FindingCategories.normalize(b.category()));
        boolean near = near(a, b);
        if (titles >= TITLE_MATCH || (sameCategory && near && titles >= TITLE_NEAR_MATCH)) {
            return titles + (sameCategory ? 0.25 : 0) + (near ? 0.25 : 0);
        }
        return 0;
    }

    private static boolean near(Candidate a, Candidate b) {
        if (a.startLine() == null || b.startLine() == null) {
            return Objects.equals(a.startLine(), b.startLine());
        }
        int aEnd = a.endLine() != null ? a.endLine() : a.startLine();
        int bEnd = b.endLine() != null ? b.endLine() : b.startLine();
        int gap = Math.max(a.startLine(), b.startLine()) - Math.min(aEnd, bEnd);
        return gap <= NEAR_LINES;
    }

    static Set<String> words(String title) {
        Set<String> words = new HashSet<>();
        if (title == null) {
            return words;
        }
        for (String word : title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOPWORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        long shared = a.stream().filter(b::contains).count();
        return (double) shared / union.size();
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./") || normalized.startsWith("/")) {
            normalized = normalized.startsWith("./") ? normalized.substring(2) : normalized.substring(1);
        }
        return normalized;
    }

    private record Pair(long currentId, long previousId, double score) {
    }
}
