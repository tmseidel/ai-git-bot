package org.remus.giteabot.ai;

/**
 * Callback used by AI clients to report token usage and errors of individual
 * provider interactions. Implementations persist the data so it can be shown
 * on the admin "Usage" page.
 *
 * <p>The recorder is attached to a client by the {@code AiClientFactory} after
 * construction; clients must tolerate a missing recorder (no-op).</p>
 */
public interface AiAuditRecorder {

    /**
     * Records the token usage of a single successful AI interaction.
     *
     * @param inputTokens  number of prompt/input tokens (0 when unknown)
     * @param outputTokens number of completion/output tokens (0 when unknown)
     */
    void recordUsage(long inputTokens, long outputTokens);

    /**
     * Records a failed AI interaction (e.g. an HTTP 401 response).
     *
     * @param error the exception raised by the provider call
     */
    void recordError(Throwable error);
}
