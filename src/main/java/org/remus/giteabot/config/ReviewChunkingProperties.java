package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for PR diff chunking during code review.
 * These values were previously stored on {@code AiIntegration} and are now
 * workflow-level configuration with Spring-managed defaults.
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "review.chunking")
public class ReviewChunkingProperties {

    /** Maximum characters per diff chunk before splitting (default 120000). */
    private int maxDiffCharsPerChunk = 120_000;

    /** Maximum number of diff chunks to process (default 8). */
    private int maxDiffChunks = 8;

    /** Characters to truncate a chunk to on retry after a prompt-too-long error (default 60000). */
    private int retryTruncatedChunkChars = 60_000;
}
