package org.remus.giteabot.aiusage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Set;

/**
 * Records and queries the audit log of AI provider interactions: token usage
 * of successful calls and details (including stack traces) of failed calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService {

    /** Page size of the tables on the "Usage" page. */
    public static final int PAGE_SIZE = 20;

    /** Page size for streaming exports — keeps heap bounded (~10 MB worst case). */
    private static final int EXPORT_PAGE_SIZE = 100;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    private static final int MAX_STACK_TRACE_LENGTH = 100_000;
    private static final Set<String> USAGE_SORT_COLUMNS =
            Set.of("timestamp", "aiIntegrationName", "sessionId", "inputTokens", "outputTokens");
    private static final Set<String> ERROR_SORT_COLUMNS =
            Set.of("timestamp", "aiIntegrationName", "sessionId", "errorMessage");

    private final AiUsageLogRepository usageRepository;
    private final AiErrorLogRepository errorRepository;

    /**
     * Records the token usage of a single AI interaction. Persistence problems
     * are logged but never propagated so that auditing can never break the
     * actual AI workflow.
     */
    @Transactional
    public void recordUsage(String aiIntegrationName, String sessionId,
                            long inputTokens, long outputTokens) {
        try {
            AiUsageLog entry = new AiUsageLog();
            entry.setTimestamp(Instant.now());
            entry.setAiIntegrationName(aiIntegrationName);
            entry.setSessionId(sessionId);
            entry.setInputTokens(inputTokens);
            entry.setOutputTokens(outputTokens);
            usageRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to persist AI usage entry: {}", e.getMessage());
        }
    }

    /**
     * Records a failed AI interaction with its stack trace. Persistence
     * problems are logged but never propagated.
     */
    @Transactional
    public void recordError(String aiIntegrationName, String sessionId, Throwable error) {
        try {
            AiErrorLog entry = new AiErrorLog();
            entry.setTimestamp(Instant.now());
            entry.setAiIntegrationName(aiIntegrationName);
            entry.setSessionId(sessionId);
            entry.setErrorMessage(truncate(error.getMessage() != null
                    ? error.getMessage() : error.getClass().getName(), MAX_ERROR_MESSAGE_LENGTH));
            entry.setStackTrace(truncate(stackTraceOf(error), MAX_STACK_TRACE_LENGTH));
            errorRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to persist AI error entry: {}", e.getMessage());
        }
    }

    /**
     * Removes all recorded AI usage entries.
     */
    @Transactional
    public void clearUsage() {
        usageRepository.deleteAllInBatch();
    }

    @Transactional(readOnly = true)
    public Page<AiUsageLog> findUsage(Instant from, Instant to, int page,
                                      String sortColumn, boolean ascending) {
        String column = USAGE_SORT_COLUMNS.contains(sortColumn) ? sortColumn : "timestamp";
        return usageRepository.findByTimestampBetween(effectiveFrom(from), effectiveTo(to),
                PageRequest.of(page, PAGE_SIZE, sortOf(column, ascending)));
    }

    @Transactional(readOnly = true)
    public Page<AiErrorLog> findErrors(Instant from, Instant to, int page,
                                       String sortColumn, boolean ascending) {
        String column = ERROR_SORT_COLUMNS.contains(sortColumn) ? sortColumn : "timestamp";
        return errorRepository.findByTimestampBetween(effectiveFrom(from), effectiveTo(to),
                PageRequest.of(page, PAGE_SIZE, sortOf(column, ascending)));
    }

    /**
     * Streams all error entries in the given timespan as a JSON array directly
     * to the provided output stream.  Results are fetched in pages of
     * {@value #EXPORT_PAGE_SIZE} rows so that heap usage stays bounded
     * regardless of the total number of matching rows.
     *
     * <p>This method intentionally does <em>not</em> carry a
     * {@code @Transactional} annotation — each page query opens its own
     * short-lived read-only transaction via the Spring Data proxy, which is
     * the correct pattern for streaming responses that outlive a single
     * transaction.</p>
     */
    public void exportErrors(Instant from, Instant to, OutputStream outputStream) throws IOException {
        Instant f = effectiveFrom(from);
        Instant t = effectiveTo(to);

        try (JsonGenerator gen = new ObjectMapper().getFactory().createGenerator(outputStream)) {
            gen.writeStartArray();
            int page = 0;
            Page<AiErrorLog> result;
            do {
                result = errorRepository.findAllByTimestampBetweenOrderByTimestampDesc(
                        f, t, PageRequest.of(page, EXPORT_PAGE_SIZE));
                for (AiErrorLog entry : result.getContent()) {
                    gen.writeStartObject();
                    gen.writeObjectField("timestamp", entry.getTimestamp());
                    gen.writeStringField("aiIntegration", entry.getAiIntegrationName());
                    gen.writeStringField("sessionId", entry.getSessionId());
                    gen.writeStringField("errorMessage", entry.getErrorMessage());
                    gen.writeStringField("stackTrace", entry.getStackTrace());
                    gen.writeEndObject();
                }
                gen.flush();
                page++;
            } while (result.hasNext());
            gen.writeEndArray();
        }
    }

    /**
     * Number of AI errors recorded after the given instant (dashboard badge).
     */
    @Transactional(readOnly = true)
    public long countErrorsSince(Instant after) {
        return errorRepository.countByTimestampAfter(after);
    }

    /**
     * Total input tokens consumed across all recorded AI interactions.
     */
    @Transactional(readOnly = true)
    public long totalInputTokens() {
        return usageRepository.sumInputTokens();
    }

    /**
     * Total output tokens consumed across all recorded AI interactions.
     */
    @Transactional(readOnly = true)
    public long totalOutputTokens() {
        return usageRepository.sumOutputTokens();
    }

    private static Sort sortOf(String column, boolean ascending) {
        Sort sort = Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, column);
        if (!"timestamp".equals(column)) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "timestamp"));
        }
        return sort;
    }

    private static Instant effectiveFrom(Instant from) {
        return from != null ? from : Instant.EPOCH;
    }

    private static Instant effectiveTo(Instant to) {
        return to != null ? to : Instant.now().plusSeconds(60);
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
}
