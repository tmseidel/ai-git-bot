package org.remus.giteabot.agent.loop;

import lombok.extern.slf4j.Slf4j;

/**
 * Truncates oversized tool results using a head+tail strategy that preserves both the
 * beginning (typically headers, structure) and the end (typically errors, exit codes,
 * summary lines) of the output.
 *
 * <p>This prevents individual tool outputs (build logs, file contents, tree listings)
 * from consuming the entire context budget. A single {@code cat} on a large file can
 * inject 100k+ chars that the model will never fully read.</p>
 */
@Slf4j
public final class ToolResultTruncator {

    private final int maxChars;

    public ToolResultTruncator(int maxChars) {
        this.maxChars = maxChars;
    }

    /**
     * Returns the original text if ≤ maxChars, otherwise a truncated version with a
     * marker showing how many characters were removed.
     *
     * <p>The truncation uses a head+tail strategy:</p>
     * <ul>
     *     <li>First maxChars/2 characters (preserves structure, headers)</li>
     *     <li>Truncation marker in the middle</li>
     *     <li>Last (maxChars - maxChars/2 - 64) characters (preserves errors, exit codes)</li>
     * </ul>
     *
     * @param text the tool result text
     * @return original text if small enough, otherwise truncated version
     */
    public String truncate(String text) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }

        int markerOverhead = 64; // space for "\n\n[... N characters truncated ...]\n\n"
        int headSize = maxChars / 2;
        int tailSize = maxChars - headSize - markerOverhead;

        // Guard against very small maxChars where tail would be negative
        if (tailSize <= 0) {
            headSize = maxChars - markerOverhead;
            tailSize = 0;
        }

        int removed = text.length() - headSize - tailSize;

        String truncated = text.substring(0, headSize)
                + "\n\n[... " + removed + " characters truncated ...]\n\n"
                + (tailSize > 0 ? text.substring(text.length() - tailSize) : "");

        log.debug("Truncated tool result from {} to {} chars ({} chars removed)",
                text.length(), truncated.length(), removed);

        return truncated;
    }

    public int getMaxChars() {
        return maxChars;
    }
}
