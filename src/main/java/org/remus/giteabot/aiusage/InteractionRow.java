package org.remus.giteabot.aiusage;

import org.remus.giteabot.workflowrun.RunStatus;

import java.time.Instant;

/**
 * An {@link AiInteraction} without its raw payloads and stack trace, which can be large; the
 * details modal loads those per interaction.
 */
public record InteractionRow(
        Long id,
        Long runId,
        int roundNumber,
        int attempt,
        String aiIntegrationName,
        RunStatus status,
        Instant startedAt,
        Long durationMs,
        String stopReason,
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens,
        String errorMessage) {
}
