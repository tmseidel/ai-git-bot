package org.remus.giteabot.prworkflow.i18n;

import org.remus.giteabot.prworkflow.WorkflowParamName;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;

/**
 * Compile-time-safe identifiers for the {@link I18nCoverageWorkflow} parameter
 * keys, mirroring {@code ReadmeSyncParam} / {@code E2eTestParam}. The
 * {@link #key()} value is what hits both the persisted
 * {@code workflow_selection_params.name} column and the JSON form payload — no
 * database migration is needed to add these because params live as JSON.
 */
public enum I18nCoverageParam implements WorkflowParamName {

    /**
     * Newline / comma separated glob patterns that define BOTH the i18n input
     * scope and the allowed output scope (see {@link I18nPathGuard}). Example:
     * {@code messages_*.properties}, {@code i18n/*.json}.
     */
    INCLUDED_FILE_PATTERNS("includedFilePatterns"),

    /**
     * The reference locale whose keys/values/deletions the other locale files
     * are compared against (e.g. {@code en}). Blank falls back to the implicit
     * default (suffix-less) file per bundle family.
     */
    BASELINE_LOCALE("baselineLocale"),

    /** Upper bound on the agent's explore/write tool rounds. */
    MAX_TOOL_ROUNDS("maxToolRounds"),

    /** Post-run handling of the generated translation changes — see {@link SuiteLifecycleMode}. */
    SUITE_LIFECYCLE("suiteLifecycle");

    private final String key;

    I18nCoverageParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
