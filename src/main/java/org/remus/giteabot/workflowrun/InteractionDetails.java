package org.remus.giteabot.workflowrun;

import org.remus.giteabot.aiusage.AiToolCall;
import org.remus.giteabot.aiusage.InteractionRow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What the interaction details modal shows about one request to the AI provider
 * ({@code workflow-runs/interaction.html}). The raw payloads are loaded separately
 * ({@code /workflow-runs/interactions/{id}/raw}).
 *
 * @param row         the interaction, without its raw payloads
 * @param stackTrace  the stack trace of a failed interaction, or {@code null}
 * @param run         the run it belongs to
 * @param roundsInRun the number of rounds of the run ("Round n of m")
 * @param triggerUrl  the pull request or issue in the Git provider, or {@code null}
 * @param toolCalls   the tool calls the model requested in the response, in order
 */
public record InteractionDetails(InteractionRow row,
                                 String stackTrace,
                                 WorkflowRun run,
                                 long roundsInRun,
                                 String triggerUrl,
                                 List<AiToolCall> toolCalls) {

    public boolean failed() {
        return row.status() == RunStatus.ERROR;
    }

    /** The request to the provider is still in flight: no duration, response or tool calls yet. */
    public boolean running() {
        return row.status() == RunStatus.RUNNING;
    }

    public long toolTimeMs() {
        return toolCalls.stream().mapToLong(AiToolCall::getDurationMs).sum();
    }

    public long failedToolCalls() {
        return toolCalls.stream().filter(call -> !call.isSuccess()).count();
    }

    /** The provider request; 0 while unknown. */
    public long modelTimeMs() {
        return row.durationMs() != null ? row.durationMs() : 0;
    }

    /** The whole round: the provider request and, unless it failed, the tool calls it requested. */
    public long totalTimeMs() {
        return failed() ? modelTimeMs() : modelTimeMs() + toolTimeMs();
    }

    /** The round's duration; for a running interaction, the time so far. */
    public long durationMs(Instant now) {
        return running() ? Math.max(0, Duration.between(row.startedAt(), now).toMillis()) : totalTimeMs();
    }

    /** When the round ended; only for a finished interaction. */
    public Instant endedAt() {
        return row.startedAt().plusMillis(totalTimeMs());
    }

    /** The model's share of the round in percent, for the model/tools bar. */
    public long modelSharePercent() {
        long total = totalTimeMs();
        return total > 0 ? Math.round(100.0 * modelTimeMs() / total) : 100;
    }

    /** Cache read ÷ input (the input includes the cached part), or {@code null} without input. */
    public Double cacheHitRate() {
        return row.inputTokens() > 0 ? (double) row.cacheReadInputTokens() / row.inputTokens() : null;
    }
}
