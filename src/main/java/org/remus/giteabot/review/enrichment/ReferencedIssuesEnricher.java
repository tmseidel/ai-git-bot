package org.remus.giteabot.review.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches PR context with details of issues referenced in the PR description.
 * Parses the PR body for issue references (e.g. "#123", "fixes #456") and
 * fetches their title and body from the repository API.
 */
@Slf4j
public class ReferencedIssuesEnricher implements ContextEnricher {

    private static final Pattern ISSUE_REF_PATTERN = Pattern.compile(
            "(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+#(\\d+)|#(\\d+)",
            Pattern.CASE_INSENSITIVE
    );

    private final RepositoryApiClient repositoryClient;

    public ReferencedIssuesEnricher(RepositoryApiClient repositoryClient) {
        this.repositoryClient = repositoryClient;
    }

    @Override
    public String enrich(EnrichmentContext context) {
        if (context.prBody() == null || context.prBody().isBlank()) {
            return "";
        }

        Set<Long> issueNumbers = extractIssueReferences(context.prBody());
        if (issueNumbers.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("**Referenced issues:**\n");
        for (Long issueNumber : issueNumbers) {
            try {
                Map<String, Object> issue = repositoryClient.getIssueDetails(
                        context.owner(), context.repo(), issueNumber);
                if (issue != null && !issue.isEmpty()) {
                    String title = (String) issue.getOrDefault("title", "");
                    String body = (String) issue.getOrDefault("body", "");
                    sb.append("\n#").append(issueNumber);
                    if (title != null && !title.isBlank()) {
                        sb.append(" - ").append(title);
                    }
                    sb.append("\n");
                    if (body != null && !body.isBlank()) {
                        sb.append(body).append("\n");
                    }
                }
            } catch (Exception e) {
                log.debug("Could not fetch issue #{}: {}", issueNumber, e.getMessage());
            }
        }

        return sb.toString();
    }

    /**
     * Extracts issue number references from text.
     * Matches patterns like "#123", "fixes #123", "closes #456", "resolves #789".
     */
    Set<Long> extractIssueReferences(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<Long> issueNumbers = new LinkedHashSet<>();
        Matcher matcher = ISSUE_REF_PATTERN.matcher(text);
        while (matcher.find()) {
            String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (number != null) {
                try {
                    issueNumbers.add(Long.parseLong(number));
                } catch (NumberFormatException e) {
                    // Ignore invalid numbers
                }
            }
        }
        return issueNumbers;
    }
}

