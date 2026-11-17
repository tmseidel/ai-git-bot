package org.remus.giteabot.review.structured;

/**
 * Delivery state of one publication attempt, tracked separately from the review's own lifecycle:
 * a finalized review can exist without ever reaching the pull request.
 */
public enum ReviewPublicationState {

    /**
     * Intent persisted before the Git call: a crash mid-delivery stays visible. While a delivery is
     * pending — still running, or orphaned by a crash after the Git call — no other delivery of the
     * review starts until a person confirms it did not arrive.
     */
    PENDING,

    /** The provider accepted the review (or its plain-comment fallback). */
    PUBLISHED,

    /** The provider answered with a definite refusal; retrying the same body is safe. */
    FAILED,

    /**
     * The outcome is unknown (timeout, dropped connection, server error). A retry may duplicate the
     * comment, so only a person who checked the pull request can release it.
     */
    UNCERTAIN,

    /** The review was deliberately not published: the run was cancelled or its head moved. */
    SUPPRESSED
}
