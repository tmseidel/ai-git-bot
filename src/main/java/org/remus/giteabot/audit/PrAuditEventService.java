package org.remus.giteabot.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.PrWorkflowEventKey;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrAuditEventService {

    static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PrAuditEventRepository auditRepository;
    private final PrWorkflowRunRepository runRepository;

    /** Serializes required payloads without silently dropping serialization failures. */
    public static String toJsonRequired(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize required audit payload", e);
        }
    }

    /** Loads a retained required event; this lookup is not a durable completion receipt. */
    @Transactional(readOnly = true)
    public Optional<PrAuditEvent> findRequired(PrWorkflowEventKey key) {
        return auditRepository.findByEventKey(key.value());
    }

    /**
     * Joins the completion transaction and locks its run before checking/inserting the key.
     * Identical content reuses the stored event, timestamp and hash; conflicts and DB failures
     * abort the transaction. Callers must not catch a failed insert and continue that transaction.
     * Dedupe/conflict detection lasts only while the audit row is retained. Completion owners
     * must first call the generic completion gate in the same outer transaction and return on
     * {@code applied=false} before calling this helper for domain events.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PrAuditEvent recordRequired(PrWorkflowEventKey key, PrAuditEvent event) {
        if (key.eventType() != event.getEventType()
                || !key.runId().equals(event.getRunId())
                || (event.getEventKey() != null && !key.value().equals(event.getEventKey()))) {
            throw new IllegalArgumentException("Required audit event does not match its key");
        }
        PrWorkflowRun run = runRepository.findByIdForUpdate(key.runId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown PrWorkflowRun id=" + key.runId()));
        if (!Objects.equals(run.getBotId(), event.getBotId())
                || !Objects.equals(run.getRepoOwner(), event.getRepoOwner())
                || !Objects.equals(run.getRepoName(), event.getRepoName())
                || !Objects.equals(run.getPrNumber(), event.getPrNumber())) {
            throw new IllegalArgumentException("Required audit event does not belong to the run");
        }
        List<?> content = requiredContent(event);
        // shortcut: retained rows alone deduplicate here; domain owners must gate terminal replays before this helper.
        PrAuditEvent existing = auditRepository.findByEventKey(key.value()).orElse(null);
        if (existing != null) {
            if ((event.getId() != null && !event.getId().equals(existing.getId()))
                    || !requiredContent(existing).equals(content)) {
                throw new IllegalStateException("Conflicting required audit event: " + key.value());
            }
            return existing;
        }
        if (event.getId() != null) {
            throw new IllegalArgumentException("A new required audit must not reuse an existing row id");
        }
        event.setEventKey(key.value());
        if (event.getEventTimestamp() != null) {
            event.setEventTimestamp(event.getEventTimestamp().truncatedTo(ChronoUnit.MILLIS));
        }
        String previousHash = resolvePreviousHash(event);
        event.setPreviousHash(previousHash);
        event.setHash(computeHash(event.getEventType().name(), event.getEventTimestamp(),
                event.getEventPayloadJson(), previousHash));
        return auditRepository.saveAndFlush(event);
    }

    private static List<?> requiredContent(PrAuditEvent event) {
        try {
            return Arrays.asList(event.getEventType(), event.getBotId(), event.getRepoOwner(),
                    event.getRepoName(), event.getPrNumber(), event.getRunId(), event.getStepIndex(),
                    event.getStepName(), event.getStepStatus(), event.getActorType(), event.getActorId(),
                    event.getAiSessionId(), event.getAiUsageSessionId(), event.getCorrelationId(),
                    event.getDurationMs(), event.getInputTokens(), event.getOutputTokens(),
                    event.getEventPayloadJson() == null ? null : CANONICAL_MAPPER.readTree(event.getEventPayloadJson()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid required audit payload", e);
        }
    }

    /** Serializes a payload map to canonical JSON (sorted keys). Returns null for null/empty input. */
    public static String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Persists an audit event after computing its hash from the previous event in the chain.
     * Failures are logged and never propagated.
     */
    @Transactional
    public void record(PrAuditEvent event) {
        try {
            String previousHash = resolvePreviousHash(event);
            String payloadJson = event.getEventPayloadJson();
            String eventTypeName = event.getEventType() != null ? event.getEventType().name() : "UNKNOWN";
            event.setPreviousHash(previousHash);
            event.setHash(computeHash(eventTypeName, event.getEventTimestamp(), payloadJson, previousHash));
            auditRepository.save(event);
            log.debug("Audit event persisted: type={} runId={} id={}", event.getEventType(), event.getRunId(), event.getId());
        } catch (Exception e) {
            log.error("Failed to persist audit event type={} runId={}: {}", event.getEventType(), event.getRunId(), e.getMessage(), e);
        }
    }

    /** Convenience: emit a tool-call event. Arguments truncated to 2 KB, result to 1 KB. */
    @Transactional
    public void emitToolCall(Long botId, String repoOwner, String repoName, Long prNumber,
                             Long runId, Long aiSessionId, String aiUsageSessionId,
                             int round, String toolName, String arguments,
                             String resultExcerpt, boolean success, long durationMs,
                             Long inputTokens, Long outputTokens) {
        String args = arguments != null && arguments.length() > 2000
                ? arguments.substring(0, 1997) + "..." : arguments;
        String result = resultExcerpt != null && resultExcerpt.length() > 1024
                ? resultExcerpt.substring(0, 1021) + "..." : resultExcerpt;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolName);
        payload.put("arguments", args);
        payload.put("result_excerpt", result);
        payload.put("success", success);
        payload.put("round", round);
        try {
            String payloadJson = CANONICAL_MAPPER.writeValueAsString(payload);
            PrAuditEvent event = PrAuditEvent.builder()
                    .eventType(AuditEventType.TOOL_CALL_EXECUTED)
                    .eventTimestamp(Instant.now())
                    .botId(botId).repoOwner(repoOwner).repoName(repoName).prNumber(prNumber)
                    .runId(runId)
                    .actorType(ActorType.BOT.name()).actorId("agent")
                    .aiSessionId(aiSessionId).aiUsageSessionId(aiUsageSessionId)
                    .eventPayloadJson(payloadJson)
                    .durationMs(durationMs)
                    .inputTokens(inputTokens).outputTokens(outputTokens)
                    .build();
            record(event);
        } catch (Exception e) {
            log.error("Failed to persist tool-call audit event runId={}: {}", runId, e.getMessage(), e);
        }
    }


    @Transactional(readOnly = true)
    public List<PrAuditEvent> findByBotAndRepoAndPr(Long botId, String repoOwner,
                                                     String repoName, Long prNumber) {
        return auditRepository.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberOrderByIdAsc(
                botId, repoOwner, repoName, prNumber);
    }

    @Transactional(readOnly = true)
    public List<PrAuditEvent> findByBot(Long botId) {
        return auditRepository.findByBotIdOrderByIdAsc(botId);
    }

    private String resolvePreviousHash(PrAuditEvent event) {
        PrAuditEvent previous;
        if (event.getRunId() != null) {
            previous = auditRepository.findTopByRunIdOrderByIdDesc(event.getRunId());
        } else {
            previous = auditRepository.findTopByBotIdAndRepoOwnerAndRepoNameAndPrNumberOrderByIdDesc(
                    event.getBotId(), event.getRepoOwner(), event.getRepoName(), event.getPrNumber());
        }
        return previous != null ? previous.getHash() : null;
    }

    static String computeHash(String eventType, Instant timestamp, String payloadJson, String previousHash) {
        String input = eventType
                + "|" + (timestamp != null ? timestamp.toEpochMilli() : "0")
                + "|" + (payloadJson != null ? payloadJson : "")
                + "|" + (previousHash != null ? previousHash : "NULL");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
