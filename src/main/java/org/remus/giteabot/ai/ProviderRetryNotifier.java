package org.remus.giteabot.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Receiver for the retry decisions taken by {@link RetryAiClient}. Implementations
 * surface a retry in an operator-visible place (typically a pull-request or issue
 * comment); they must never throw and must not block.
 */
public interface ProviderRetryNotifier {

    /** Called just before the wait that precedes the next attempt. */
    void retryScheduled(Event event);

    /** Called when the attempt budget is exhausted and the failure is rethrown to the caller. */
    void retriesExhausted(Event event);

    /**
     * Retry mechanics of one failed attempt.
     *
     * @param attempt       number of the attempt that just failed (1-based)
     * @param maxAttempts   configured attempt budget
     * @param delay         wait before the next attempt; {@code null} when the budget is exhausted
     * @param nextAttemptAt when the next attempt starts; {@code null} when the budget is exhausted
     * @param plannedDelays the un-jittered backoff plan, length {@code maxAttempts - 1}
     * @param error         the provider failure that triggered the retry
     */
    record Event(int attempt, int maxAttempts, Duration delay, Instant nextAttemptAt,
                 List<Duration> plannedDelays, Throwable error) {
    }
}
