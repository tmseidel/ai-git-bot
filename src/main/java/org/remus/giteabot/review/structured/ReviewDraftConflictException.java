package org.remus.giteabot.review.structured;

import lombok.Getter;

/**
 * The draft changed under the caller: a stale revision was passed, the review is no longer
 * a draft, or the expected finding count does not match. Carries the current revision so
 * the caller can re-read the draft and retry deliberately instead of blindly.
 */
@Getter
public class ReviewDraftConflictException extends RuntimeException {

    private final int currentDraftRevision;

    public ReviewDraftConflictException(String message, int currentDraftRevision) {
        super(message);
        this.currentDraftRevision = currentDraftRevision;
    }
}
