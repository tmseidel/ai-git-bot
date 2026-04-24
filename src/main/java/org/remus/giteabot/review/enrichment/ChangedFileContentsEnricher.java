package org.remus.giteabot.review.enrichment;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches PR context with the complete content of changed files.
 * Parses the diff to extract file paths, fetches each file's content from the
 * repository at the PR's head ref, and truncates per
 * {@link ReviewConfigProperties#getMaxSingleFileChars()} /
 * {@link ReviewConfigProperties#getMaxFileContentChars()}.
 */
@Slf4j
public class ChangedFileContentsEnricher implements ContextEnricher {

    private static final Pattern DIFF_FILE_PATTERN = Pattern.compile(
            "^diff --git a/(.+?) b/(.+?)$", Pattern.MULTILINE
    );

    private final RepositoryApiClient repositoryClient;
    private final ReviewConfigProperties config;

    public ChangedFileContentsEnricher(RepositoryApiClient repositoryClient, ReviewConfigProperties config) {
        this.repositoryClient = repositoryClient;
        this.config = config;
    }

    @Override
    public String enrich(EnrichmentContext context) {
        if (context.diff() == null || context.diff().isBlank()
                || context.headRef() == null || context.headRef().isBlank()) {
            return "";
        }

        List<String> changedFiles = extractChangedFilePaths(context.diff());
        if (changedFiles.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("**Changed files (complete content):**\n");
        int totalChars = 0;

        for (String filePath : changedFiles) {
            if (totalChars >= config.getMaxFileContentChars()) {
                sb.append("\n... (remaining changed files omitted due to size limits)\n");
                break;
            }

            try {
                String content = repositoryClient.getFileContent(
                        context.owner(), context.repo(), filePath, context.headRef());
                if (content != null && !content.isEmpty()) {
                    if (content.length() > config.getMaxSingleFileChars()) {
                        content = content.substring(0, config.getMaxSingleFileChars()) + "\n... (truncated)";
                    }
                    sb.append("\n--- ").append(filePath).append(" ---\n");
                    sb.append("```\n").append(content).append("\n```\n");
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch content for {}: {}", filePath, e.getMessage());
            }
        }

        return sb.toString();
    }

    /**
     * Extracts file paths from a unified diff format.
     * Parses lines like "diff --git a/path/to/file b/path/to/file".
     */
    List<String> extractChangedFilePaths(String diff) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = DIFF_FILE_PATTERN.matcher(diff);
        while (matcher.find()) {
            String bPath = matcher.group(2);
            if (bPath != null && !bPath.isBlank() && !"/dev/null".equals(bPath)) {
                paths.add(bPath);
            }
        }
        return new ArrayList<>(paths);
    }
}

