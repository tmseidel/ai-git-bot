package org.remus.giteabot.learning.feedback;

/**
 * Delivery state of the bot's confirmation for one feedback event, tracked separately from the
 * feedback itself: a lost confirmation never discards an assessment.
 */
public enum FeedbackAcknowledgement {

    /** Not attempted yet, or attempted by a delivery that did not report back. */
    PENDING,

    POSTED,

    /** The provider refused the comment; posting it again cannot duplicate it. */
    FAILED,

    /**
     * The outcome is unknown (timeout, dropped connection, server error). The comment may be on the
     * pull request already, so it is never posted again automatically.
     */
    UNCERTAIN
}
