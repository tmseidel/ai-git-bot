package org.remus.giteabot.review.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;

/**
 * Orchestrates all {@link ContextEnricher} implementations to build
 * enriched context for AI-powered pull request reviews.
 * <p>
 * Each enricher gathers a different type of context (repository tree,
 * file contents, commit messages, referenced issues). Results are
 * concatenated in order. Enrichers that return empty strings are skipped.
 */
@Slf4j
public class PrContextEnricher {

    private final List<ContextEnricher> enrichers;

    public PrContextEnricher(RepositoryApiClient repositoryClient, ReviewConfigProperties config) {
        this.enrichers = List.of(
                new RepositoryTreeEnricher(repositoryClient, config),
                new ChangedFileContentsEnricher(repositoryClient, config),
                new CommitMessagesEnricher(repositoryClient, config),
                new ReferencedIssuesEnricher(repositoryClient)
        );
    }

    /**
     * Creates a PrContextEnricher with a custom list of enrichers.
     * Useful for testing or when only specific enrichment types are needed.
     */
    public PrContextEnricher(List<ContextEnricher> enrichers) {
        this.enrichers = enrichers;
    }

    /**
     * Builds enriched context for a pull request review by running all enrichers.
     *
     * @param owner    repository owner
     * @param repo     repository name
     * @param prNumber pull request number
     * @param diff     the pull request diff
     * @param headRef  the head branch/ref of the PR (for fetching file contents)
     * @param prBody   the pull request description (for extracting issue references)
     * @return formatted additional context string, or empty string if no context could be gathered
     */
    public String buildEnrichedContext(String owner, String repo, Long prNumber,
                                       String diff, String headRef, String prBody) {
        var context = new EnrichmentContext(owner, repo, prNumber, diff, headRef, prBody);
        StringBuilder result = new StringBuilder();

        for (ContextEnricher enricher : enrichers) {
            try {
                String section = enricher.enrich(context);
                if (section != null && !section.isEmpty()) {
                    result.append(section).append("\n");
                }
            } catch (Exception e) {
                log.warn("Enricher {} failed: {}", enricher.getClass().getSimpleName(), e.getMessage());
            }
        }

        return result.toString();
    }
}
