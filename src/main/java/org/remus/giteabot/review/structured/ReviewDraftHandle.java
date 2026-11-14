package org.remus.giteabot.review.structured;

/** Current state of a review draft, returned by every mutating operation. */
public record ReviewDraftHandle(long reviewId, String outputReference, int draftRevision) {
}
