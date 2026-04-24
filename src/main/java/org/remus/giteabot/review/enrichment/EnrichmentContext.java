package org.remus.giteabot.review.enrichment;

/**
 * Holds all context parameters needed by {@link ContextEnricher} implementations
 * to gather additional information for a pull request review.
 *
 * @param owner    repository owner
 * @param repo     repository name
 * @param prNumber pull request number
 * @param diff     the pull request diff (may be null)
 * @param headRef  the head branch/ref of the PR for fetching file contents (may be null)
 * @param prBody   the pull request description for extracting issue references (may be null)
 */
public record EnrichmentContext(
        String owner,
        String repo,
        Long prNumber,
        String diff,
        String headRef,
        String prBody
) {
}

