package org.remus.giteabot.prworkflow.readmesync;

import org.remus.giteabot.prworkflow.WorkflowParamName;

/**
 * Compile-time-safe identifiers for the {@link ReadmeSyncWorkflow} parameter
 * keys, mirroring {@code E2eTestParam} / {@code UnitTestParam}. The
 * {@link #key()} value is what hits both the persisted
 * {@code workflow_selection_params.name} column and the JSON form payload — no
 * database migration is needed to add these because params live as JSON.
 */
public enum ReadmeSyncParam implements WorkflowParamName {

    /**
     * Newline / comma separated glob patterns that define BOTH the
     * documentation input scope and the allowed output scope (see
     * {@link DocPathGuard}). Example: {@code README.md}, {@code README.*.md},
     * {@code doc/**\/*.md}.
     */
    INCLUDED_FILE_PATTERNS("includedFilePatterns"),

    /** Upper bound on the agent's explore/write tool rounds. */
    MAX_TOOL_ROUNDS("maxToolRounds"),

    /** Post-run handling of the generated documentation changes — see {@link SuiteLifecycleMode}. */
    SUITE_LIFECYCLE("suiteLifecycle");

    private final String key;

    ReadmeSyncParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
