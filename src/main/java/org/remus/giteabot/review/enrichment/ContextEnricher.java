package org.remus.giteabot.review.enrichment;

/**
 * Strategy interface for enriching pull request review context.
 * Each implementation gathers a specific type of additional context
 * (e.g. repository tree, file contents, commit messages, referenced issues)
 * and returns it as a formatted string section.
 *
 * @see RepositoryTreeEnricher
 * @see ChangedFileContentsEnricher
 * @see CommitMessagesEnricher
 * @see ReferencedIssuesEnricher
 */
public interface ContextEnricher {

    /**
     * Enriches the PR review context with additional information.
     *
     * @param context the enrichment context containing PR metadata
     * @return a formatted string section, or empty string if no context could be gathered
     */
    String enrich(EnrichmentContext context);
}

