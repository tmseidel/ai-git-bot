package org.remus.giteabot.aiusage;

import java.time.Instant;

/**
 * One row of the Usage table: an {@link AiUsageLog} without its raw payloads. Raw payloads can be
 * large (whole conversations), so the list query never loads them; the Details modal fetches them
 * per entry from {@code /usage/{id}/raw}.
 */
public record AiUsageRow(
        Long id,
        Instant timestamp,
        String aiIntegrationName,
        String sessionId,
        Integer round,
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens) {
}
