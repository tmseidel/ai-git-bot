package org.remus.giteabot.aiusage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AuditRun;
import org.remus.giteabot.ai.AuditTrigger;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.config.AiUsageProperties;
import org.remus.giteabot.workflowrun.RunStatus;
import org.remus.giteabot.workflowrun.WorkflowRun;
import org.remus.giteabot.workflowrun.WorkflowRunRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Records and queries the audit log of AI provider interactions.
 *
 * <p>Recording: every request to the provider becomes an {@link AiInteraction} of the
 * open {@link AuditRun} on the calling thread (see {@link AiAuditContext}). The run's
 * {@link WorkflowRun} row is created with its first interaction. Only facts are stored; totals
 * are computed from the rows when they are read. Persistence problems are logged but never
 * propagated, so auditing can never break the actual AI workflow.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService implements AuditRun.Sink {

    /** Page size of the tables on the "Usage" page. */
    public static final int PAGE_SIZE = 20;

    /** Upper bound for user-requested page sizes — keeps a single page query bounded. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Error message of runs and interactions that were still running when the application stopped. */
    static final String INTERRUPTED_MESSAGE = "Interrupted by restart";

    /** Page size for streaming exports — keeps heap bounded (~10 MB worst case). */
    private static final int EXPORT_PAGE_SIZE = 100;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    private static final int MAX_STACK_TRACE_LENGTH = 100_000;

    /**
     * Tool arguments are always stored (unlike the raw payloads, they carry no conversation
     * context), but a single call can carry a whole file, so they are capped.
     */
    private static final int MAX_TOOL_ARGUMENTS_LENGTH = 65_535;

    /** Sort keys the Usage page sends, mapped to {@link AiInteraction} properties. */
    private static final Map<String, String> USAGE_SORT_COLUMNS = Map.of(
            "timestamp", "startedAt", "aiIntegrationName", "aiIntegrationName", "sessionId", "sessionId",
            "round", "roundNumber", "inputTokens", "inputTokens", "outputTokens", "outputTokens",
            "cacheCreationInputTokens", "cacheCreationInputTokens",
            "cacheReadInputTokens", "cacheReadInputTokens");
    private static final Map<String, String> ERROR_SORT_COLUMNS = Map.of(
            "timestamp", "startedAt", "aiIntegrationName", "aiIntegrationName", "sessionId", "sessionId",
            "errorMessage", "errorMessage");

    private final AiInteractionRepository interactionRepository;
    private final AiToolCallRepository toolCallRepository;
    private final WorkflowRunRepository runRepository;
    private final AiUsageProperties usageProperties;

    // ------------------------------------------------------------------ recording

    /**
     * A request to the provider is about to be sent: starts the next attempt of the open run and
     * stores it as running. Every AI call is made inside a workflow run, so a call while no run is
     * open is not recorded.
     */
    public void attemptStarted(String aiIntegrationName) {
        AuditRun run = AiAuditContext.currentRun();
        if (run == null) {
            return;
        }
        run.beginAttempt();
        try {
            ensurePersisted(run);
            AiInteraction interaction = new AiInteraction();
            interaction.setWorkflowRun(runRepository.getReferenceById(run.getRunId()));
            interaction.setRoundNumber(run.getRound());
            interaction.setAttempt(run.getAttempt());
            interaction.setAiIntegrationName(aiIntegrationName);
            interaction.setSessionId(AiAuditContext.getSessionId());
            interaction.setStatus(RunStatus.RUNNING);
            interaction.setStartedAt(run.getAttemptStartedAt());
            run.setInteractionId(interactionRepository.save(interaction).getId());
        } catch (Exception e) {
            log.warn("Failed to persist the start of an AI interaction: {}", e.getMessage());
        }
    }

    /**
     * The provider client parsed the token usage of the current attempt; it is stored when the
     * attempt ends. Every client the factory builds reports usage inside an attempt
     * ({@link org.remus.giteabot.ai.RetryAiClient}), so usage reported outside one is
     * ignored.
     *
     * <p>{@code inputTokens} is the total processed input (for cache-capable providers: uncached +
     * cache write + cache read); the two cache fields carry the breakdown.</p>
     */
    public void usageReported(long inputTokens, long outputTokens,
                              long cacheCreationInputTokens, long cacheReadInputTokens,
                              String rawRequest, String rawResponse) {
        AuditRun run = AiAuditContext.currentRun();
        if (run == null || !run.isAttemptOpen()) {
            return;
        }
        run.setPendingUsage(new AuditRun.PendingUsage(inputTokens, outputTokens,
                cacheCreationInputTokens, cacheReadInputTokens,
                truncateRawPayload(rawRequest), truncateRawPayload(rawResponse)));
    }

    /** The current attempt returned normally. */
    public void attemptSucceeded(StopReason stopReason) {
        attemptFinished(RunStatus.SUCCEEDED, stopReason, null);
    }

    /** The current attempt threw {@code error}. */
    public void attemptFailed(Throwable error) {
        attemptFinished(RunStatus.ERROR, null, error);
    }

    private void attemptFinished(RunStatus status, StopReason stopReason, Throwable error) {
        AuditRun run = AiAuditContext.currentRun();
        if (run == null || !run.isAttemptOpen()) {
            return;
        }
        run.endAttempt();
        long durationMs = run.attemptElapsedMs();
        AuditRun.PendingUsage usage = run.getPendingUsage() != null ? run.getPendingUsage()
                : new AuditRun.PendingUsage(0, 0, 0, 0, null, null);
        try {
            if (run.getInteractionId() != null) {
                interactionRepository.finish(run.getInteractionId(), status, durationMs,
                        stopReason != null ? stopReason.name() : null,
                        usage.inputTokens(), usage.outputTokens(),
                        usage.cacheCreationInputTokens(), usage.cacheReadInputTokens(),
                        error != null ? truncate(errorMessageOf(error), MAX_ERROR_MESSAGE_LENGTH) : null,
                        error != null ? truncate(stackTraceOf(error), MAX_STACK_TRACE_LENGTH) : null,
                        usage.rawRequest(), usage.rawResponse());
            }
        } catch (Exception e) {
            log.warn("Failed to persist the end of an AI interaction: {}", e.getMessage());
        }
    }

    private void ensurePersisted(AuditRun run) {
        if (run.isPersisted()) {
            return;
        }
        AuditTrigger trigger = run.getTrigger();
        WorkflowRun entity = new WorkflowRun();
        entity.setWorkflowType(run.getWorkflowType());
        entity.setBotId(trigger.botId());
        entity.setBotName(trigger.botName());
        entity.setAiIntegrationName(trigger.aiIntegrationName());
        entity.setGitIntegrationName(trigger.gitIntegrationName());
        entity.setRepoOwner(trigger.repoOwner());
        entity.setRepoName(trigger.repoName());
        entity.setTriggerKind(trigger.triggerKind());
        entity.setTriggerNumber(trigger.triggerNumber());
        entity.setSessionId(trigger.sessionId());
        entity.setState(RunStatus.RUNNING);
        entity.setStartedAt(run.getAttemptStartedAt());
        run.attach(runRepository.save(entity).getId(), this);
    }

    /**
     * Persists the end of a run. The workflow reports its own failure ({@link AuditRun#fail}).
     */
    @Override
    public void runFinished(AuditRun run) {
        String failure = run.getFailureMessage();
        try {
            runRepository.finish(run.getRunId(),
                    failure != null ? RunStatus.ERROR : RunStatus.SUCCEEDED,
                    Instant.now(), truncate(failure, MAX_ERROR_MESSAGE_LENGTH), run.isRoundLimitReached());
        } catch (Exception e) {
            log.warn("Failed to persist the end of workflow run {}: {}", run.getRunId(), e.getMessage());
        }
    }

    @Override
    public void toolCallFinished(AuditRun run, AuditRun.ToolCallRecord record) {
        Long interactionId = run.getInteractionId();
        if (interactionId == null) {
            return;
        }
        try {
            AiToolCall call = new AiToolCall();
            call.setInteraction(interactionRepository.getReferenceById(interactionId));
            call.setSequence(run.nextToolSequence());
            call.setSource(truncate(record.source(), 255));
            call.setToolName(truncate(record.toolName() != null ? record.toolName() : "unknown", 255));
            call.setDurationMs(record.durationMs());
            call.setSuccess(record.success());
            call.setErrorMessage(record.success() ? null
                    : truncate(record.errorMessage(), MAX_ERROR_MESSAGE_LENGTH));
            call.setArguments(truncate(record.arguments(), MAX_TOOL_ARGUMENTS_LENGTH));
            toolCallRepository.save(call);
        } catch (Exception e) {
            log.warn("Failed to persist tool call '{}': {}", record.toolName(), e.getMessage());
        }
    }

    /**
     * Runs and interactions still marked running belong to a previous process that stopped
     * before finishing them; they will never finish.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        try {
            int interactions = interactionRepository.failAllRunning(INTERRUPTED_MESSAGE);
            int runs = runRepository.failAllRunning(Instant.now(), INTERRUPTED_MESSAGE);
            if (runs > 0 || interactions > 0) {
                log.info("Marked {} workflow run(s) and {} AI interaction(s) interrupted by the restart as failed",
                        runs, interactions);
            }
        } catch (Exception e) {
            log.warn("Failed to mark interrupted workflow runs as failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ queries

    /** Removes every finished run with its interactions and tool calls. Running runs are kept. */
    @Transactional
    public void clearUsage() {
        toolCallRepository.deleteAllOfFinishedRuns();
        interactionRepository.deleteAllOfFinishedRuns();
        runRepository.deleteAllFinished();
    }

    /** A page of successful interactions; the raw payloads are not loaded (see {@link #findRawPayload}). */
    @Transactional(readOnly = true)
    public Page<AiUsageRow> findUsage(Instant from, Instant to, int page, int pageSize,
                                      String sortColumn, boolean ascending) {
        String column = USAGE_SORT_COLUMNS.getOrDefault(sortColumn, "startedAt");
        return interactionRepository.findUsageRows(effectiveFrom(from), effectiveTo(to),
                PageRequest.of(page, effectivePageSize(pageSize), sortOf(column, ascending)));
    }

    /**
     * The raw payloads of one interaction, or empty when it does not exist or raw payload
     * capture is disabled (stored payloads are then not shown, as the page hides the Details column).
     */
    @Transactional(readOnly = true)
    public Optional<AiUsageRawPayload> findRawPayload(long id) {
        if (!rawPayloadsEnabled()) {
            return Optional.empty();
        }
        return interactionRepository.findRawPayloadById(id);
    }

    /** A page of failed interactions. */
    @Transactional(readOnly = true)
    public Page<AiErrorRow> findErrors(Instant from, Instant to, int page, int pageSize,
                                       String sortColumn, boolean ascending) {
        String column = ERROR_SORT_COLUMNS.getOrDefault(sortColumn, "startedAt");
        return interactionRepository.findErrorRows(effectiveFrom(from), effectiveTo(to),
                PageRequest.of(page, effectivePageSize(pageSize), sortOf(column, ascending)));
    }

    /**
     * Normalizes a user-requested page size: non-positive values fall back to
     * {@link #PAGE_SIZE}, oversized values are clamped to {@link #MAX_PAGE_SIZE}
     * (the largest size the rows-per-page select offers) so a single request can
     * never force an unbounded page query.
     */
    private static int effectivePageSize(int pageSize) {
        return pageSize > 0 ? Math.min(pageSize, MAX_PAGE_SIZE) : PAGE_SIZE;
    }

    /**
     * Streams all failed interactions in the given timespan as a JSON array directly to the
     * provided output stream. Results are fetched in pages of {@value #EXPORT_PAGE_SIZE} rows so
     * that heap usage stays bounded regardless of the total number of matching rows.
     *
     * <p>This method intentionally does <em>not</em> carry a {@code @Transactional} annotation —
     * each page query opens its own short-lived read-only transaction via the Spring Data proxy,
     * which is the correct pattern for streaming responses that outlive a single transaction.</p>
     */
    public void exportErrors(Instant from, Instant to, OutputStream outputStream) throws IOException {
        Instant f = effectiveFrom(from);
        Instant t = effectiveTo(to);

        try (JsonGenerator gen = new ObjectMapper().getFactory().createGenerator(outputStream)) {
            gen.writeStartArray();
            int page = 0;
            Page<AiErrorRow> result;
            do {
                result = interactionRepository.findErrorRows(f, t,
                        PageRequest.of(page, EXPORT_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "startedAt")));
                for (AiErrorRow entry : result.getContent()) {
                    gen.writeStartObject();
                    gen.writeStringField("timestamp", entry.timestamp() != null ? entry.timestamp().toString() : null);
                    gen.writeStringField("aiIntegration", entry.aiIntegrationName());
                    gen.writeStringField("sessionId", entry.sessionId());
                    gen.writeStringField("errorMessage", entry.errorMessage());
                    gen.writeStringField("stackTrace", entry.stackTrace());
                    gen.writeEndObject();
                }
                gen.flush();
                page++;
            } while (result.hasNext());
            gen.writeEndArray();
        }
    }

    /**
     * Number of failed AI calls started after the given instant (dashboard badge): the last
     * attempt of each round that failed. A failed attempt that a retry recovered from does not
     * count, and neither does a failed call the workflow went on from.
     */
    @Transactional(readOnly = true)
    public long countErrorsSince(Instant after) {
        return interactionRepository.countFailedCallsStartedAfter(after);
    }

    /** Total input tokens consumed across all recorded AI interactions. */
    @Transactional(readOnly = true)
    public long totalInputTokens() {
        return interactionRepository.sumInputTokens();
    }

    /** Total output tokens consumed across all recorded AI interactions. */
    @Transactional(readOnly = true)
    public long totalOutputTokens() {
        return interactionRepository.sumOutputTokens();
    }

    private static Sort sortOf(String column, boolean ascending) {
        Sort sort = Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, column);
        if (!"startedAt".equals(column)) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "startedAt"));
        }
        return sort;
    }

    private static Instant effectiveFrom(Instant from) {
        return from != null ? from : Instant.EPOCH;
    }

    private static Instant effectiveTo(Instant to) {
        return to != null ? to : Instant.now().plusSeconds(60);
    }

    private boolean rawPayloadsEnabled() {
        return usageProperties != null && usageProperties.isRawPayloadsEnabled();
    }

    private static String errorMessageOf(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getName();
    }

    private static String stackTraceOf(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String truncateRawPayload(String value) {
        if (value == null || !rawPayloadsEnabled()) {
            return null;
        }
        int maxLength = usageProperties.getEffectiveMaxRawPayloadLength();
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
