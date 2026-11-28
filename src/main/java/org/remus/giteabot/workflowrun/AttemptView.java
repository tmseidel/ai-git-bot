package org.remus.giteabot.workflowrun;

import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.aiusage.InteractionRow;
import org.remus.giteabot.aiusage.ToolTotals;

import java.time.Duration;
import java.time.Instant;

/**
 * One request to the AI provider in the rounds table of an expanded run: the interaction and the
 * totals of the tool calls it requested.
 *
 * @param roundLimit {@code true} on the run's last attempt when the run reached its round limit
 */
public record AttemptView(InteractionRow row, ToolTotals tools, boolean roundLimit) {

    /** The first attempt of its round; later attempts are retries. */
    public boolean isFirstOfRound() {
        return row.attempt() == 1;
    }

    public boolean isRunning() {
        return row.status() == RunStatus.RUNNING;
    }

    public boolean isFailed() {
        return row.status() == RunStatus.ERROR;
    }

    public boolean isTruncated() {
        return StopReason.MAX_TOKENS.name().equals(row.stopReason());
    }

    public long toolCalls() {
        return tools.calls();
    }

    public long failedToolCalls() {
        return tools.failed();
    }

    public long toolTimeMs() {
        return tools.durationMs();
    }

    /** The provider request; 0 while unknown. */
    public long modelTimeMs() {
        return row.durationMs() != null ? row.durationMs() : 0;
    }

    /** The round's duration: the provider request plus the tool calls; for a running attempt, the time so far. */
    public long durationMs(Instant now) {
        if (isRunning()) {
            return Math.max(0, Duration.between(row.startedAt(), now).toMillis());
        }
        return modelTimeMs() + toolTimeMs();
    }
}
