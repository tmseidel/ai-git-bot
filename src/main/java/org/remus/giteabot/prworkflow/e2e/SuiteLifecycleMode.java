package org.remus.giteabot.prworkflow.e2e;

/**
 * What happens to a generated {@link PrTestSuite} once the PR closes.
 *
 * <p>M4 ships only {@link #EPHEMERAL}; the remaining values are reserved for
 * milestone M7 (suite promotion) so the schema is forward-compatible.
 * The chosen mode is persisted on {@link PrTestSuite#getLifecycleMode()}.</p>
 */
public enum SuiteLifecycleMode {

    /** Suite + cases are deleted on PR close. The default. */
    EPHEMERAL("ephemeral"),

    /** Bot opens a follow-up PR with the generated tests on the source repo (M7). */
    OFFER_AS_PR("offer-as-pr"),

    /** Suite is auto-merged into the source branch once the PR merges (M7). */
    PROMOTE_ON_MERGE("promote-on-merge"),

    /** Bot commits the generated tests directly onto the feature branch (M7). */
    COMMIT_TO_PR("commit-to-pr");

    private final String key;

    SuiteLifecycleMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static SuiteLifecycleMode fromKey(String value) {
        if (value == null) {
            return EPHEMERAL;
        }
        for (SuiteLifecycleMode m : values()) {
            if (m.key.equalsIgnoreCase(value) || m.name().equalsIgnoreCase(value)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown suite lifecycle mode: " + value);
    }
}
