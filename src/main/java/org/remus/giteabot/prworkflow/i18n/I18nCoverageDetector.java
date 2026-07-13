package org.remus.giteabot.prworkflow.i18n;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Detects i18n key coverage mismatches across the locale files of a checkout.
 *
 * <p>The detector is baseline-driven: within each locale <em>family</em> (a set
 * of files translating the same bundle — see {@link I18nFileParser}) one file is
 * chosen as the baseline (the configured baseline locale, else the implicit
 * default {@code ""} locale, else the first file). Every other locale file in
 * the family is compared against that baseline:</p>
 * <ul>
 *   <li><b>missing keys</b> — present in the baseline but absent in the locale
 *       file (a newly added / changed key the translation has not caught up
 *       with) → the workflow drafts translations for them;</li>
 *   <li><b>stale keys</b> — present in the locale file but absent from the
 *       baseline (a key deleted from the baseline) → the workflow deletes
 *       them.</li>
 * </ul>
 *
 * <p>Per the issue assumptions the whole repository checkout is scanned — every
 * file matching the configured include patterns is considered, not only the
 * files touched by the PR. The PR diff is passed to the agent separately as
 * extra context.</p>
 */
@Slf4j
public final class I18nCoverageDetector {

    private I18nCoverageDetector() {
    }

    /** One non-baseline locale file that is out of sync with its baseline. */
    public record LocaleGap(String path, String locale,
                            List<String> missingKeys, List<String> staleKeys) {
        public LocaleGap {
            missingKeys = missingKeys == null ? List.of() : List.copyOf(missingKeys);
            staleKeys = staleKeys == null ? List.of() : List.copyOf(staleKeys);
        }

        public boolean hasGap() {
            return !missingKeys.isEmpty() || !staleKeys.isEmpty();
        }
    }

    /** One bundle family, its baseline and every out-of-sync sibling locale. */
    public record FamilyCoverage(String familyId, String baselinePath, String baselineLocale,
                                 List<LocaleGap> gaps) {
        public FamilyCoverage {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    /** Full coverage report across all in-scope families. */
    public record Report(List<FamilyCoverage> families) {
        public Report {
            families = families == null ? List.of() : List.copyOf(families);
        }

        /** {@code true} when at least one locale file is missing or has stale keys. */
        public boolean hasGaps() {
            return families.stream().anyMatch(f -> f.gaps().stream().anyMatch(LocaleGap::hasGap));
        }

        public int totalMissingKeys() {
            return families.stream()
                    .flatMap(f -> f.gaps().stream())
                    .mapToInt(g -> g.missingKeys().size())
                    .sum();
        }

        public int totalStaleKeys() {
            return families.stream()
                    .flatMap(f -> f.gaps().stream())
                    .mapToInt(g -> g.staleKeys().size())
                    .sum();
        }

        public int affectedLocaleFileCount() {
            return (int) families.stream()
                    .flatMap(f -> f.gaps().stream())
                    .filter(LocaleGap::hasGap)
                    .count();
        }
    }

    /**
     * Scans {@code workspace}, groups every in-scope i18n file by family and
     * computes the coverage gaps of every non-baseline locale against the family
     * baseline.
     *
     * @param workspace        repository checkout root
     * @param includePatterns  operator-configured i18n include globs
     * @param baselineLocale   configured baseline locale (e.g. {@code en}); may be
     *                         blank to fall back to the implicit-default file
     */
    public static Report detect(Path workspace, List<String> includePatterns, String baselineLocale) {
        List<String> files = findInScopeFiles(workspace, includePatterns);
        Map<String, List<I18nFileParser.LocaleFile>> byFamily = new LinkedHashMap<>();
        for (String rel : files) {
            try {
                String content = Files.readString(workspace.resolve(rel), StandardCharsets.UTF_8);
                if (I18nPathGuard.isJson(rel) && I18nFileParser.isNestedJson(content)) {
                    log.warn("i18n-coverage: skipping nested JSON file `{}` — only flat " +
                            "{\"key\":\"value\"} shapes are currently supported; nested objects " +
                            "and arrays cannot be safely round-tripped by the agent write path",
                            rel);
                    continue;
                }
                I18nFileParser.LocaleFile lf = I18nFileParser.parse(rel, content);
                byFamily.computeIfAbsent(lf.familyId(), k -> new ArrayList<>()).add(lf);
            } catch (IOException e) {
                log.debug("i18n-coverage: could not read {}: {}", rel, e.getMessage());
            }
        }

        String wantedBaseline = I18nFileParser.normalizeLocale(baselineLocale);
        List<FamilyCoverage> families = new ArrayList<>();
        for (Map.Entry<String, List<I18nFileParser.LocaleFile>> e : byFamily.entrySet()) {
            List<I18nFileParser.LocaleFile> members = e.getValue();
            if (members.size() < 2) {
                // A family with a single locale file has no sibling to compare against.
                continue;
            }
            I18nFileParser.LocaleFile baseline = pickBaseline(members, wantedBaseline);
            List<LocaleGap> gaps = new ArrayList<>();
            for (I18nFileParser.LocaleFile member : members) {
                if (member == baseline) {
                    continue;
                }
                TreeSet<String> missing = new TreeSet<>(baseline.entries().keySet());
                missing.removeAll(member.entries().keySet());
                TreeSet<String> stale = new TreeSet<>(member.entries().keySet());
                stale.removeAll(baseline.entries().keySet());
                gaps.add(new LocaleGap(member.path(), member.locale(),
                        new ArrayList<>(missing), new ArrayList<>(stale)));
            }
            families.add(new FamilyCoverage(e.getKey(), baseline.path(), baseline.locale(), gaps));
        }
        return new Report(families);
    }

    /**
     * Chooses the baseline file of a family: prefer an exact match of the
     * configured baseline locale, then a language-only match, then the implicit
     * default (blank-locale) file, then the lexicographically first path so the
     * result is deterministic.
     */
    static I18nFileParser.LocaleFile pickBaseline(List<I18nFileParser.LocaleFile> members,
                                                  String wantedBaseline) {
        if (wantedBaseline != null && !wantedBaseline.isBlank()) {
            for (I18nFileParser.LocaleFile m : members) {
                if (m.locale().equalsIgnoreCase(wantedBaseline)) {
                    return m;
                }
            }
            String wantedLang = wantedBaseline.split("_")[0];
            for (I18nFileParser.LocaleFile m : members) {
                if (m.locale().split("_")[0].equalsIgnoreCase(wantedLang)) {
                    return m;
                }
            }
        }
        for (I18nFileParser.LocaleFile m : members) {
            if (m.locale().isEmpty()) {
                return m;
            }
        }
        return members.stream()
                .min(Comparator.comparing(I18nFileParser.LocaleFile::path))
                .orElse(members.getFirst());
    }

    /**
     * Walks the checkout and returns the workspace-relative paths of every
     * existing file that is in the configured i18n scope, skipping {@code .git}.
     */
    public static List<String> findInScopeFiles(Path workspace, List<String> includePatterns) {
        List<String> result = new ArrayList<>();
        if (workspace == null) {
            return result;
        }
        Path root = workspace.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String rel = root.relativize(p.toAbsolutePath().normalize())
                                .toString().replace('\\', '/');
                        if (rel.startsWith(".git/") || rel.equals(".git")) {
                            return;
                        }
                        if (I18nPathGuard.isAllowedI18nPath(includePatterns, rel)) {
                            result.add(rel);
                        }
                    });
        } catch (IOException e) {
            log.warn("i18n-coverage: could not scan workspace for in-scope files: {}", e.getMessage());
        }
        result.sort(String::compareTo);
        return result;
    }
}
