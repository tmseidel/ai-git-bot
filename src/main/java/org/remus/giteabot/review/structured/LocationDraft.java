package org.remus.giteabot.review.structured;

/**
 * Model-supplied source location supporting a finding. Paths are repository-relative and
 * line bounds are optional; the service validates both before persisting.
 */
public record LocationDraft(
        String path,
        ReviewSide side,
        Integer startLine,
        Integer endLine,
        String description) {
}
