package org.remus.giteabot.ai;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * The thread-local state of one workflow run while it executes: which workflow and trigger it
 * belongs to, the current round and attempt, and the interaction the next tool calls attach to.
 *
 * <p>Opened with {@link AiAuditContext#openRun} and closed with try-with-resources; while open, it
 * receives the thread's AI interactions. A run is persisted lazily by the
 * audit recorder on its first AI interaction ({@link #attach}), so a workflow that never calls
 * the AI leaves no row behind. Closing a persisted run reports its outcome to the {@link Sink}.</p>
 *
 * <p>Round numbering: every logical AI call ({@link #beginCall}) starts a new round, unless the
 * caller announced it as a retry of the previous call ({@link #markRetry}). Every request sent to
 * the provider ({@link #beginAttempt}) is a new attempt within the current round, so provider
 * retries keep the round number as well.</p>
 */
@Slf4j
@Getter
public final class AuditRun implements AutoCloseable {

    /** Persists what happens to a run after its first AI interaction. Implementations never throw. */
    public interface Sink {

        /** The run was closed; persist its end time, state and flags. */
        void runFinished(AuditRun run);

        /** A tool call of the current interaction finished. */
        void toolCallFinished(AuditRun run, ToolCallRecord record);
    }

    /** One finished tool call, as reported by {@link ToolCallAudit}. */
    public record ToolCallRecord(String source,
                                 String toolName,
                                 long durationMs,
                                 boolean success,
                                 String errorMessage,
                                 String arguments) {
    }

    /** Token usage and raw payloads reported by the provider client during the current attempt. */
    public record PendingUsage(long inputTokens,
                               long outputTokens,
                               long cacheCreationInputTokens,
                               long cacheReadInputTokens,
                               String rawRequest,
                               String rawResponse) {
    }

    private final String workflowType;
    private final AuditTrigger trigger;

    private Long runId;
    private Sink sink;

    private int round;
    private int attempt;
    private boolean retryPending;
    private Long interactionId;
    private int toolSequence;
    private Instant attemptStartedAt;
    private long attemptStartNanos;
    private boolean attemptOpen;
    private PendingUsage pendingUsage;

    private boolean roundLimitReached;
    private String failureMessage;

    AuditRun(String workflowType, AuditTrigger trigger) {
        this.workflowType = workflowType;
        this.trigger = trigger;
    }

    /** Starts a logical AI call: the next round, or the same round when a retry was announced. */
    public void beginCall() {
        if (retryPending) {
            retryPending = false;
            return;
        }
        round++;
        attempt = 0;
    }

    /** Announces that the next logical AI call repeats the previous one and keeps its round. */
    public void markRetry() {
        retryPending = true;
    }

    /** Starts one request to the provider within the current round. */
    public void beginAttempt() {
        attempt++;
        attemptStartedAt = Instant.now();
        attemptStartNanos = System.nanoTime();
        attemptOpen = true;
        pendingUsage = null;
        interactionId = null;
        toolSequence = 0;
    }

    /** Milliseconds since {@link #beginAttempt}. */
    public long attemptElapsedMs() {
        return (System.nanoTime() - attemptStartNanos) / 1_000_000;
    }

    /** Ends the current attempt; the interaction stays current for the tool calls that follow. */
    public void endAttempt() {
        attemptOpen = false;
    }

    public void setPendingUsage(PendingUsage pendingUsage) {
        this.pendingUsage = pendingUsage;
    }

    public void setInteractionId(Long interactionId) {
        this.interactionId = interactionId;
    }

    /** The sequence number of the next tool call of the current interaction (1-based). */
    public int nextToolSequence() {
        return ++toolSequence;
    }

    /** The agent loop ran out of rounds before the model finished. */
    public void markRoundLimitReached() {
        this.roundLimitReached = true;
    }

    /** Marks the run as failed; the first message wins. */
    public void fail(String message) {
        if (failureMessage == null) {
            failureMessage = message != null && !message.isBlank() ? message : "Failed";
        }
    }

    /** Called once the run row exists; from now on the sink receives the run's events. */
    public void attach(Long runId, Sink sink) {
        this.runId = runId;
        this.sink = sink;
    }

    public boolean isPersisted() {
        return runId != null;
    }

    /** Leaves the run: it is no longer the thread's open run, and its outcome is persisted. */
    @Override
    public void close() {
        AiAuditContext.removeRun(this);
        if (sink != null) {
            try {
                sink.runFinished(this);
            } catch (RuntimeException e) {
                log.warn("Failed to persist the end of workflow run {}: {}", runId, e.getMessage());
            }
        }
    }
}
