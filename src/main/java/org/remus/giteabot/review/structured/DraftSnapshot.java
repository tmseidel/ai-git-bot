package org.remus.giteabot.review.structured;

import java.util.List;

/**
 * Current state of a draft as the review tools may see it: revision, active finding
 * identities and whether the assessment still covers the current finding set. Model-supplied
 * fields are summarised, not replayed verbatim.
 */
public record DraftSnapshot(
        long reviewId,
        String outputReference,
        ReviewState state,
        int draftRevision,
        List<FindingSummary> findings,
        ReviewConclusion conclusion,
        String summary,
        boolean assessmentCurrent) {

    public record FindingSummary(
            String reference,
            int ordinal,
            String clientKey,
            int revision,
            ReviewSeverity severity,
            String title) {
    }
}
