package org.remus.giteabot.review.structured;

/**
 * Model-supplied overall assessment. The conclusion must match the active findings and an
 * inconclusive assessment must say what was not verified.
 */
public record AssessmentDraft(
        String summary,
        ReviewConclusion conclusion,
        String reviewedAreas,
        String limitations) {
}
