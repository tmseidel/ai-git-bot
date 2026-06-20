package org.remus.giteabot.prworkflow.review;

import org.remus.giteabot.prworkflow.WorkflowParamName;

/**
 * Compile-time-safe parameter keys for {@link ReviewWorkflow}.
 */
public enum ReviewParam implements WorkflowParamName {

    /** Maximum characters per diff chunk before splitting. */
    MAX_DIFF_CHARS_PER_CHUNK("maxDiffCharsPerChunk"),

    /** Maximum number of diff chunks to process. */
    MAX_DIFF_CHUNKS("maxDiffChunks"),

    /** Characters to truncate a chunk to on retry after a prompt-too-long error. */
    RETRY_TRUNCATED_CHUNK_CHARS("retryTruncatedChunkChars");

    private final String key;

    ReviewParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
