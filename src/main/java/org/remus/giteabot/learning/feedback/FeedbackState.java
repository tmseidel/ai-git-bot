package org.remus.giteabot.learning.feedback;

/**
 * Lifecycle of one feedback record. A new assessment by the same actor on the same target
 * supersedes the previous one instead of overwriting it, so history stays readable and a
 * correction is never conflated with a deletion.
 */
public enum FeedbackState {

    EFFECTIVE,

    /** Replaced by a later assessment from the same actor on the same target. */
    SUPERSEDED,

    /** Explicitly withdrawn by its author. */
    RETRACTED
}
