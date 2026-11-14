package org.remus.giteabot.review.structured;

/** Overall conclusion of an agentic review, supplied by the model and validated against the active findings. */
public enum ReviewConclusion {

    /** The review reports at least one finding. */
    FINDINGS_PRESENT,

    /** The review reports no findings. */
    NO_FINDINGS,

    /** The review could not reach a conclusion, usually because coverage was incomplete. */
    INCONCLUSIVE
}
