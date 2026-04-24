package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for PR code review context enrichment.
 * Controls how much additional context is gathered from the repository
 * and sent to the AI provider alongside the diff.
 */
@Data
@Component
@ConfigurationProperties(prefix = "review.context")
public class ReviewConfigProperties {

    /**
     * Maximum total characters of file content to include in the enriched context.
     * Once this limit is reached, remaining changed files are omitted.
     */
    private int maxFileContentChars = 30000;

    /**
     * Maximum characters per single file. Files exceeding this limit are truncated.
     */
    private int maxSingleFileChars = 10000;

    /**
     * Maximum number of files to list in the repository tree context.
     * Large repositories with many files will be truncated after this count.
     */
    private int maxTreeFiles = 500;

    /**
     * Maximum number of commit messages to include in the context.
     */
    private int maxCommitMessages = 50;
}

