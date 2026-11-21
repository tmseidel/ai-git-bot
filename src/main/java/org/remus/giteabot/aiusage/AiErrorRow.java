package org.remus.giteabot.aiusage;

import java.time.Instant;

/** One row of the error table: a failed {@link AiInteraction} without its raw payloads. */
public record AiErrorRow(
        Long id,
        Instant timestamp,
        String aiIntegrationName,
        String sessionId,
        String errorMessage,
        String stackTrace) {
}
