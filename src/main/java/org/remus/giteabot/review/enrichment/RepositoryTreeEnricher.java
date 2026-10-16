package org.remus.giteabot.review.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryTreeEntry;

import java.util.List;

/**
 * Enriches PR context with the repository file tree structure.
 * Lists all files in the repository at the PR's head ref, truncated to
 * {@link ReviewConfigProperties#getMaxTreeFiles()}.
 */
@Slf4j
public class RepositoryTreeEnricher implements ContextEnricher {

    private final RepositoryApiClient repositoryClient;
    private final ReviewConfigProperties config;

    public RepositoryTreeEnricher(RepositoryApiClient repositoryClient, ReviewConfigProperties config) {
        this.repositoryClient = repositoryClient;
        this.config = config;
    }

    @Override
    public String enrich(EnrichmentContext context) {
        try {
            List<RepositoryTreeEntry> tree = repositoryClient.getRepositoryTree(
                    context.owner(), context.repo(), context.headRef());
            if (tree.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("**Repository structure:**\n```\n");
            int count = 0;
            for (RepositoryTreeEntry entry : tree) {
                if (count >= config.getMaxTreeFiles()) {
                    sb.append("... (").append(tree.size() - count).append(" more files)\n");
                    break;
                }
                if (entry.isFile()) {
                    sb.append("  ").append(entry.path()).append("\n");
                    count++;
                }
            }
            sb.append("```\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to fetch repository tree for {}/{}: {}", context.owner(), context.repo(), e.getMessage());
            return "";
        }
    }
}

