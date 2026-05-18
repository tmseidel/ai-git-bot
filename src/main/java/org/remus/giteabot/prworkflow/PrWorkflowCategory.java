package org.remus.giteabot.prworkflow;

/**
 * Coarse category for a {@link PrWorkflow}. Used by UI grouping and by the
 * default workflow-configuration initializer to decide which newly registered
 * workflows are automatically enabled (only {@link #REVIEW} ones are; all
 * other categories require explicit operator opt-in).
 *
 * <p>The taxonomy is intentionally small — new categories should be added
 * sparingly and with cross-team agreement.</p>
 */
public enum PrWorkflowCategory {
    /** Reviewing the diff and posting human-readable feedback. */
    REVIEW,
    /** Generating, deploying and executing tests (E2E, smoke, perf). */
    TESTING,
    /** Static analysis, SCA, secret scanning, license checks. */
    SECURITY,
    /** Documentation diffing, release-notes drafts, changelog suggestions. */
    DOCS,
    /** Workflow that does not fit any of the above. */
    CUSTOM
}

