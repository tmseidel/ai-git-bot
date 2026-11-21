package org.remus.giteabot.review.structured;

/**
 * Severity class of one agentic-review finding. Matches the classes the formal
 * review decision is computed from, so no mapping layer is needed.
 */
public enum ReviewSeverity {
    BLOCKER, MEDIUM, LOW
}
