package org.remus.giteabot.prworkflow.e2e;

import org.remus.giteabot.prworkflow.WorkflowParamName;

/**
 * Compile-time-safe identifiers for the {@link E2ETestWorkflow}
 * parameter keys. Use these enum values everywhere a key would
 * otherwise be a magic string literal — schema declaration, persisted
 * params lookup, the {@link E2eTestPrCloseHandler} promotion-threshold
 * check, etc. — so renaming a key only requires touching this enum
 * and the {@code key()} value (the persisted database column stays
 * untouched).
 */
public enum E2eTestParam implements WorkflowParamName {

    /** Test framework key — see {@link E2eTestFramework}. */
    FRAMEWORK("framework"),

    /** Per-test retry budget (clamped 0..5). */
    MAX_RETRIES("maxRetries"),

    /** Hard cap on the number of generated test cases per suite. */
    MAX_TEST_CASES("maxTestCases"),

    /** Post-run suite handling — see {@link SuiteLifecycleMode}. */
    SUITE_LIFECYCLE("suiteLifecycle"),

    /**
     * Minimum percentage of executed test cases that must pass for the
     * suite to be promoted. Honoured by both the inline
     * {@link E2ETestWorkflow} promotion call (OFFER_AS_PR /
     * COMMIT_TO_PR) and by the
     * {@link E2eTestPrCloseHandler} PROMOTE_ON_MERGE path.
     */
    PROMOTION_THRESHOLD_PERCENT("promotionThresholdPercent");

    private final String key;

    E2eTestParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}

