package org.remus.giteabot.aiusage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Audit record of a single AI provider interaction with its token usage.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "ai_usage_log")
public class AiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recorded_at", nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String aiIntegrationName;

    private String sessionId;

    /**
     * Total input tokens processed by the provider — including any cached
     * prefix. For cache-capable providers this is {@code uncached input +
     * cache write + cache read}, matching the usage totals shown by the
     * provider console. The uncached remainder is derivable as
     * {@code inputTokens - cacheCreationInputTokens - cacheReadInputTokens}.
     */
    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long outputTokens;

    /** Tokens written to the provider's prompt cache (0 when unsupported). */
    @Column(nullable = false)
    private long cacheCreationInputTokens;

    /**
     * Tokens read back from the provider's prompt cache (0 when
     * unsupported). Billed at a steep discount (~0.1x input price), so a
     * large value here relative to {@link #inputTokens} is the signal that
     * prompt caching is working.
     */
    @Column(nullable = false)
    private long cacheReadInputTokens;

    /**
     * Agent-loop round that produced this interaction, or {@code null} for AI
     * calls outside an agent loop (single-shot reviews, classification, ...).
     */
    @Column(name = "agent_round")
    private Integer round;

    /**
     * Raw request body sent to the AI provider (JSON), for audit/debug. Unbounded {@code TEXT}
     * column (V45). Not {@code @Lob}: on PostgreSQL that maps a String to {@code oid}.
     */
    @Column(name = "raw_request", columnDefinition = "TEXT")
    private String rawRequest;

    /** Raw response body received from the AI provider (JSON), for audit/debug. */
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;
}
