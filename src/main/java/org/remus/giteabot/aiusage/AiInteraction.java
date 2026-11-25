package org.remus.giteabot.aiusage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.remus.giteabot.workflowrun.RunStatus;
import org.remus.giteabot.workflowrun.WorkflowRun;

import java.time.Instant;

/**
 * Audit record of one request to the AI provider (an attempt): its timing, outcome, token usage
 * and, when enabled, the raw payloads. Belongs to a
 * {@link org.remus.giteabot.workflowrun.WorkflowRun}.
 *
 * <p>A retried request keeps the {@link #roundNumber} of the request it repeats and gets the
 * next {@link #attempt}. Tool calls that the model requested in its response are attached to
 * the attempt as {@link AiToolCall}s.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "ai_interaction")
public class AiInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_run_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private WorkflowRun workflowRun;

    /** 1-based round within the run. */
    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    /** 1-based attempt within the round; greater than 1 for retries. */
    @Column(nullable = false)
    private int attempt;

    @Column(name = "ai_integration_name", nullable = false)
    private String aiIntegrationName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status = RunStatus.RUNNING;

    /** When the request was sent. */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** How long the request to the provider took; {@code null} while running, and for legacy records. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Why the model stopped ({@code END_TURN}, {@code TOOL_USE}, {@code MAX_TOKENS}, {@code OTHER}),
     * or {@code null} for failed requests and text-only calls that do not expose it.
     */
    @Column(name = "stop_reason", length = 32)
    private String stopReason;

    /**
     * Total input tokens processed by the provider — including any cached
     * prefix. For cache-capable providers this is {@code uncached input +
     * cache write + cache read}, matching the usage totals shown by the
     * provider console.
     */
    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /** Tokens written to the provider's prompt cache (0 when unsupported). */
    @Column(name = "cache_creation_input_tokens", nullable = false)
    private long cacheCreationInputTokens;

    /** Tokens read back from the provider's prompt cache (0 when unsupported). */
    @Column(name = "cache_read_input_tokens", nullable = false)
    private long cacheReadInputTokens;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    /**
     * Raw request body sent to the AI provider (JSON), for audit/debug. Unbounded {@code TEXT}
     * column. Not {@code @Lob}: on PostgreSQL that maps a String to {@code oid}.
     */
    @Column(name = "raw_request", columnDefinition = "TEXT")
    private String rawRequest;

    /** Raw response body received from the AI provider (JSON), for audit/debug. */
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;
}
