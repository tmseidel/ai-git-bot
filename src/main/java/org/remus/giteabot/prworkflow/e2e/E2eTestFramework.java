package org.remus.giteabot.prworkflow.e2e;

/**
 * Test runner frameworks the {@code E2ETestWorkflow} knows how to scaffold
 * and execute. The string {@link #key()} is persisted in
 * {@code pr_test_suites.framework}; renaming a value is a breaking schema
 * change and must come with a Flyway migration.
 *
 * <p>Only {@link #PLAYWRIGHT} ships with the M4 MVP. The other values exist
 * so the schema is forward-compatible — they unblock follow-up milestones
 * without re-touching the DB.</p>
 */
public enum E2eTestFramework {

    /** Microsoft Playwright (Node.js); the MVP default. */
    PLAYWRIGHT("playwright"),

    /** pytest (Python). */
    PYTEST("pytest"),

    /** Grafana k6 (load testing). */
    K6("k6"),

    /** Cypress (Node.js). */
    CYPRESS("cypress");

    private final String key;

    E2eTestFramework(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static E2eTestFramework fromKey(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Framework key must not be null");
        }
        for (E2eTestFramework f : values()) {
            if (f.key.equalsIgnoreCase(value) || f.name().equalsIgnoreCase(value)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown E2E test framework: " + value);
    }
}
