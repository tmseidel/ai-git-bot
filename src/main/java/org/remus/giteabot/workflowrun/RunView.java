package org.remus.giteabot.workflowrun;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * One row of the Workflow runs list: the run, its attempts and the totals computed from them
 * ({@link #of}).
 *
 * @param rounds     distinct round numbers; a retried round counts once
 * @param modelTimeMs sum of the provider request durations
 * @param toolTimeMs sum of the tool call durations
 * @param durationMs end minus start; for a running run, the time so far
 * @param triggerUrl the pull request or issue in the Git provider, or {@code null}
 */
public record RunView(WorkflowRun run,
                      List<AttemptView> attempts,
                      long rounds,
                      long inputTokens,
                      long outputTokens,
                      long cacheCreationInputTokens,
                      long cacheReadInputTokens,
                      long modelTimeMs,
                      long toolCalls,
                      long failedToolCalls,
                      long toolTimeMs,
                      long durationMs,
                      boolean outputTruncated,
                      String triggerUrl) {

    /** The run with its attempts (in round and attempt order) and the totals summed from them. */
    public static RunView of(WorkflowRun run, List<AttemptView> attempts, String triggerUrl, Instant now) {
        Instant end = run.getEndedAt() != null ? run.getEndedAt() : now;
        return new RunView(run, attempts,
                attempts.stream().map(attempt -> attempt.row().roundNumber()).distinct().count(),
                sum(attempts, attempt -> attempt.row().inputTokens()),
                sum(attempts, attempt -> attempt.row().outputTokens()),
                sum(attempts, attempt -> attempt.row().cacheCreationInputTokens()),
                sum(attempts, attempt -> attempt.row().cacheReadInputTokens()),
                sum(attempts, AttemptView::modelTimeMs),
                sum(attempts, AttemptView::toolCalls),
                sum(attempts, AttemptView::failedToolCalls),
                sum(attempts, AttemptView::toolTimeMs),
                Math.max(0, Duration.between(run.getStartedAt(), end).toMillis()),
                attempts.stream().anyMatch(AttemptView::isTruncated),
                triggerUrl);
    }

    private static long sum(List<AttemptView> attempts, ToLongFunction<AttemptView> value) {
        return attempts.stream().mapToLong(value).sum();
    }

    public boolean hasWarnings() {
        return run.isRoundLimitReached() || outputTruncated;
    }

    /**
     * Share of the input served from the provider's prompt cache, or {@code null} without input.
     * {@code inputTokens} already includes the cached prefix, so it is the denominator.
     */
    public Double cacheHitRate() {
        return inputTokens > 0 ? (double) cacheReadInputTokens / inputTokens : null;
    }
}
