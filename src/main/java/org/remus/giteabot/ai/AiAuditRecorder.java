package org.remus.giteabot.ai;

/**
 * Callback used by AI clients to report each request to the provider (an attempt): its start,
 * its token usage and its outcome. Implementations persist the data so it can be shown
 * on the admin "Workflow runs" page.
 *
 * <p>The recorder is attached to a client by the {@code AiClientFactory} after
 * construction; clients must tolerate a missing recorder (no-op).</p>
 */
public interface AiAuditRecorder {

    /**
     * Records the token usage of a single successful AI interaction together
     * with the raw request/response payloads sent to and received from the
     * provider.
     *
     * <p>The audit log has no counter-availability fields: unknown
     * usage is stored as 0, which cannot be distinguished from a reported zero.
     * A stored zero is therefore not proof that the interaction used no tokens.
     * {@link ChatTurn}'s reported flags remain the separate in-memory contract;
     * persisting those flags would require a schema and recorder migration.</p>
     *
     * @param inputTokens              total prompt/input tokens processed, including any
     *                                 cached prefix (0 when unknown)
     * @param outputTokens             number of completion/output tokens (0 when unknown)
     * @param cacheCreationInputTokens tokens written to the provider's prompt cache
     * @param cacheReadInputTokens     tokens served from the provider's prompt cache
     * @param rawRequest               raw request body as sent to the provider, or {@code null}
     * @param rawResponse              raw response body as received from the provider, or {@code null}
     */
    void recordUsage(long inputTokens, long outputTokens,
                     long cacheCreationInputTokens, long cacheReadInputTokens,
                     String rawRequest, String rawResponse);

    /**
     * A request to the provider is about to be sent. Called by {@link RetryAiClient}
     * before every attempt, so the interaction's start time and duration are known.
     */
    default void attemptStarted() {
    }

    /**
     * The current attempt returned normally.
     *
     * @param stopReason why the model stopped, or {@code null} for text-only calls that do not
     *                   expose it
     */
    default void attemptSucceeded(StopReason stopReason) {
    }

    /** The current attempt threw {@code error} (e.g. an HTTP 401 response). */
    default void attemptFailed(Throwable error) {
    }
}
