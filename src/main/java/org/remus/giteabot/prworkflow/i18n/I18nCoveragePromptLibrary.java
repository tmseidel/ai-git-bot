package org.remus.giteabot.prworkflow.i18n;

import org.remus.giteabot.systemsettings.SystemPrompt;

import java.util.List;

/**
 * Central location for the {@link I18nCoverageAgent} system prompt.
 *
 * <p>Follows the same two-layer split as {@code ReadmeSyncPromptLibrary}:</p>
 * <ol>
 *   <li>An <i>editable</i> role description, operator-edited via the
 *       {@code i18nCoverageSystemPrompt} column of {@link SystemPrompt}, with a
 *       hard-coded fallback here for when it is absent.</li>
 *   <li>A non-editable <i>protocol suffix</i> appended by the software that
 *       pins the configured i18n scope and the {@code i18n-write} /
 *       {@code i18n-delete} tool contract.</li>
 * </ol>
 */
public final class I18nCoveragePromptLibrary {

    private static final String SECTION_SEPARATOR = "\n\n";

    private I18nCoveragePromptLibrary() {
    }

    /** Built-in editable role description (fallback for the operator-editable column). */
    public static final String DEFAULT_EDITABLE = """
            You are I18nCoverageAgent, an automated translation-coverage maintainer
            that runs on every opened or synchronised pull request. The user message
            gives you the PR title, body, the unified diff of the code changes, the
            configured baseline locale and a per-locale coverage report listing, for
            each non-baseline locale file, the translation keys it is MISSING relative
            to the baseline and any STALE keys it still carries that the baseline no
            longer defines.

            Your job is to bring every non-baseline locale file back in sync with the
            baseline:
              * For each MISSING key, add the key with a high-quality translation of
                the baseline value into that file's target language. Infer the target
                language from the locale token (e.g. de = German, fr = French,
                ja = Japanese, ko = Korean, zh = Chinese).
              * For each STALE key (present in the locale file but deleted from the
                baseline), remove that key so the locale file matches the baseline's
                key set.

            Principles:
              * Preserve the existing file format, key ordering conventions, escaping
                and structure (Java .properties escaping, or nested vs. flat JSON).
              * Only touch translation keys that the coverage report flags — do not
                retranslate keys that are already present, and do not reformat
                unrelated entries.
              * Keep placeholders, ICU/MessageFormat arguments ({0}, {name}, %s, etc.)
                and HTML markup identical to the baseline value.
              * If a locale file has no gaps, leave it untouched.""";

    /** Non-editable protocol suffix. Pins scope, the baseline and the tools. */
    public static final String PROTOCOL_SUFFIX_TEMPLATE = """
            i18n scope (include patterns): {patterns}
            Baseline locale: {baseline}

            You may ONLY create, update or delete locale files (*.properties / *.json)
            that match those patterns:
              * Use `i18n-write` (arguments: path, content) once per file to write the
                COMPLETE updated locale file. `content` must contain every key the file
                should end up with — all previously present keys that are still valid,
                plus your added translations, minus any stale keys you removed. No
                placeholders, no TODO markers, no partial files.
              * Use `i18n-delete` (argument: path) only to remove an entire locale file
                that is genuinely obsolete.
              * Every `path` must be checkout-relative, end in `.properties` / `.json`
                and match one of the include patterns above. Writes outside this scope —
                including any production code or non-locale file — are rejected.
              * Never modify the baseline locale file; it is the reference of truth.

            When you have applied every necessary translation change (or decided none
            are needed), reply with a single final line beginning with `DONE` followed
            by a one-sentence summary of what you changed or why no change was needed.""";

    /**
     * Resolves the agent system prompt by concatenating the editable role
     * description (operator-edited via System settings if present, otherwise the
     * built-in {@link #DEFAULT_EDITABLE}) with the non-editable protocol suffix
     * rendered for the given include patterns and baseline locale.
     */
    public static String systemPrompt(SystemPrompt systemPrompt, List<String> includePatterns,
                                      String baselineLocale) {
        String editable = pick(systemPrompt == null ? null : systemPrompt.getI18nCoverageSystemPrompt(),
                DEFAULT_EDITABLE);
        return editable + SECTION_SEPARATOR + renderProtocol(includePatterns, baselineLocale);
    }

    private static String pick(String stored, String fallback) {
        return (stored == null || stored.isBlank()) ? fallback : stored;
    }

    private static String renderProtocol(List<String> includePatterns, String baselineLocale) {
        String patterns = (includePatterns == null || includePatterns.isEmpty())
                ? "(none configured)"
                : String.join(", ", includePatterns);
        String baseline = (baselineLocale == null || baselineLocale.isBlank())
                ? "(implicit default / first file per family)"
                : baselineLocale;
        return PROTOCOL_SUFFIX_TEMPLATE
                .replace("{patterns}", patterns)
                .replace("{baseline}", baseline);
    }
}
