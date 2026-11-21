package org.remus.giteabot.review.structured;

/**
 * Lifecycle of one persisted agentic review.
 *
 * <p>Only {@link #DRAFT} accepts content mutations. {@link #FINALIZED} content is
 * immutable; the remaining states record why a draft never reached publication so a
 * later attempt cannot append to it.</p>
 */
public enum ReviewState {
    DRAFT, FINALIZED, FAILED, CANCELLED, ABANDONED
}
