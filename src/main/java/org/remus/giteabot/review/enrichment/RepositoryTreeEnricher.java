package org.remus.giteabot.review.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.List;
import java.util.Map;

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
            List<Map<String, Object>> tree = repositoryClient.getRepositoryTree(
                    context.owner(), context.repo(), context.headRef());
            if (tree == null || tree.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("**Repository structure:**\n```\n");
            int count = 0;
            for (Map<String, Object> entry : tree) {
                if (count >= config.getMaxTreeFiles()) {
                    sb.append("... (").append(tree.size() - count).append(" more files)\n");
                    break;
                }
                String type = (String) entry.getOrDefault("type", "blob");
                if ("blob".equals(type)) {
                    String path = (String) entry.getOrDefault("path", "");
                    sb.append("  ").append(path).append("\n");
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

