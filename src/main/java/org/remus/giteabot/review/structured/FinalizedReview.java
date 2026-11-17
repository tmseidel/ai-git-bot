package org.remus.giteabot.review.structured;

import java.util.List;

/**
 * Immutable read model of a finalized review: everything the renderer, the formal
 * decision and later feedback/evaluation see, and nothing the model can rewrite.
 * Findings are ordered by ordinal (their stable identity); display order is the
 * renderer's decision.
 */
public record FinalizedReview(
        String reference,
        String assessmentReference,
        ReviewConclusion conclusion,
        String summary,
        String reviewedAreas,
        String limitations,
        String coverageNote,
        SeverityCounts counts,
        List<Finding> findings,
        String contentHash) {

    /** Severity totals computed from the persisted findings. */
    public record SeverityCounts(int blocker, int medium, int low) {

        public static SeverityCounts of(List<Finding> findings) {
            int blocker = 0;
            int medium = 0;
            int low = 0;
            for (Finding finding : findings) {
                switch (finding.severity()) {
                    case BLOCKER -> blocker++;
                    case MEDIUM -> medium++;
                    case LOW -> low++;
                }
            }
            return new SeverityCounts(blocker, medium, low);
        }
    }

    public record Finding(
            String reference,
            ReviewSeverity severity,
            String category,
            String title,
            String explanation,
            String impact,
            String recommendation,
            List<Location> locations) {
    }

    public record Location(
            String path,
            ReviewSide side,
            Integer startLine,
            Integer endLine,
            String description) {
    }
}
