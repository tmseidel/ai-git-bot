# Tamper-Evident PR Workflow Audit Trail — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add a structured, append-only audit trail with hash-chaining for PR workflow execution and review actions.

**Architecture:** New `pr_audit_events` table with per-run SHA-256 hash chains, emitted from existing orchestrator/service call sites. Event-specific details stored as structured JSON payload; AI correlation via `ai_session_id` / `ai_usage_session_id` references.

**Tech Stack:** Spring Boot 4.0.5, JPA/Hibernate, Jackson 3, Flyway (H2 + PostgreSQL), SHA-256 hashing, Java 21+.

**Decisions made from open questions:**

| Question | Decision |
|----------|----------|
| Scope for v1 | PR workflow lifecycle events + ReviewCompleted (agentic + legacy). GateOverridden, FindingSuppressed, PullRequestMerged are deferred until the underlying product actions exist. |
| AI payloads in audit? | References only: store `ai_session_id` (foreign key to `agent_sessions.id`) and `ai_usage_session_id` (the session ID from `ai_usage_log`). No prompt/response duplication. |
| Actor model | `actor_type` enum: `SYSTEM`, `BOT`, `WEBHOOK`, `HUMAN`. Human actors not yet implemented — `HUMAN` is deferred. |
| Hash verification | Backend-only for v1. Developers can query to verify; no admin API surface yet. |
| GateOverridden / FindingSuppressed | Not yet in codebase. Audit event types are defined but marked **deferred** in the enum; no emission site is created for them. |
| Hash chain scope | **Per workflow run** — each `PrWorkflowRun` gets its own independent chain. The first event in a run has `previous_hash = NULL`. |
| Tool-call tracing | **Yes** — emit `TOOL_CALL_EXECUTED` events for each tool invocation by the LLM agent during a workflow. Store tool name, arguments (sanitized), result summary, and execution duration. Not full prompts, but enough to reconstruct the agent's decision path. |
| Execution time + token usage | **Yes** — every audit event carries optional `duration_ms`, `input_tokens`, and `output_tokens`. Set on tool-call events (duration) and on LLM-driven events (tokens). Also stored in `event_payload_json` for type-specific events. |

---

## Current State / Assumptions

- `pr_workflow_runs` (V13) — run-level lifecycle: `bot_id`, `repo_owner`, `repo_name`, `pr_number`, `workflow_key`, `status`, `started_at`, `finished_at`.
- `pr_workflow_steps` (V13) — append-only steps: `run_id`, `step_order`, `name`, `status`, `log_excerpt`, `created_at`.
- `ai_usage_log` (V26) — token usage with `session_id` field.
- `ai_error_log` (V26) — failed AI call details with `session_id` field.
- `agent_sessions` — agent coding sessions with `id` (PK).
- `review_sessions` — legacy review conversation sessions with `id` (PK).

Key orchestration points:
- `PrWorkflowOrchestrator.run()` — creates runs, calls workflows, handles completion/cancellation/failure.
- `PrWorkflowRunService.start()` / `appendStep()` / `complete()` — transactional lifecycle methods.
- `AgentReviewService.reviewPullRequest()` — posts review comment + optional formal decision.
- `CodeReviewService.reviewPullRequest()` — legacy review posts comment + optional approval.
- `AiUsageService.recordUsage()` — best-practice reference: catches persistence failures, logs warning, never propagates.

---

## Design

### Hash Chain — per-run

Each `pr_workflow_run` row gets its own independent chain.

```
Event 1 (genesis):   previous_hash = NULL,    hash = SHA-256("RUN_STARTED|{ts}|{payload}")
Event 2:              previous_hash = hash(1), hash = SHA-256("STEP_APPENDED|{ts}|{payload}|{prev_hash}")
Event 3:              previous_hash = hash(2), hash = SHA-256("RUN_COMPLETED|{ts}|{payload}|{prev_hash}")
```

**Hash input** (deterministic, ordered): `{event_type}|{timestamp_ms}|{canonical_payload_json}|{previous_hash}`

Notes:
- `event_type` is the `AuditEventType` enum name (e.g. `PR_WORKFLOW_RUN_STARTED`).
- `timestamp_ms` is epoch millis as a string.
- `canonical_payload_json` is `event_payload_json` serialized with sorted keys (Jackson `ObjectMapper` configured with `ORDER_MAP_ENTRIES_BY_KEYS`).
- `previous_hash` is the hex string of the previous event's hash, or the literal string `"NULL"` for genesis events.
- The event's own auto-increment `id` is **not** included in the hash input (unknown pre-insert).

### Event Types

```java
public enum AuditEventType {
    // Workflow lifecycle (implemented in v1)
    PR_WORKFLOW_RUN_STARTED,
    PR_WORKFLOW_STEP_APPENDED,       // covers INFO + ERROR steps
    PR_WORKFLOW_RUN_COMPLETED,       // covers SUCCESS / FAILED / CANCELLED (status in payload)

    // Agent tool-call tracing (implemented in v1)
    TOOL_CALL_EXECUTED,              // one event per LLM tool invocation — tool name, args, result, duration

    // Review actions (implemented where underlying feature exists)
    REVIEW_COMPLETED,                 // agentic review + legacy review

    // Deferred (defined, documented, no emission site)
    GATE_OVERRIDDEN,                  // not yet in product
    FINDING_SUPPRESSED,               // not yet in product
    PULL_REQUEST_MERGED               // not yet in product
}
```

### Entity Diagram

```
pr_audit_events
├── id              BIGINT PK (auto-increment)
├── event_type      VARCHAR(64) NOT NULL        -- AuditEventType name
├── event_timestamp TIMESTAMP NOT NULL          -- when event occurred
├── hash            CHAR(64) NOT NULL           -- SHA-256 hex
├── previous_hash   CHAR(64)                    -- NULL for genesis events
├── bot_id          BIGINT NOT NULL             -- FK to bots(id)
├── repo_owner      VARCHAR(255) NOT NULL
├── repo_name       VARCHAR(255) NOT NULL
├── pr_number       BIGINT NOT NULL
├── run_id          BIGINT                      -- FK to pr_workflow_runs(id), NULL for non-workflow events
├── step_index      INTEGER                     -- ordinal within run (0-based)
├── step_name       VARCHAR(255)                -- for STEP_APPENDED events
├── step_status     VARCHAR(32)                 -- INFO / ERROR
├── actor_type      VARCHAR(32) NOT NULL        -- SYSTEM, BOT, WEBHOOK
├── actor_id        VARCHAR(255)                -- bot name or system identifier
├── ai_session_id   BIGINT                      -- FK to agent_sessions(id) or review_sessions(id) (nullable)
├── ai_usage_session_id VARCHAR(255)            -- matches ai_usage_log.session_id
├── correlation_id  VARCHAR(255)                -- groups related events
├── duration_ms     BIGINT                      -- execution time in milliseconds (nullable)
├── input_tokens    BIGINT                      -- LLM input tokens for this step (nullable)
├── output_tokens   BIGINT                      -- LLM output tokens for this step (nullable)
├── event_payload_json TEXT                     -- structured, type-specific details
├── created_at      TIMESTAMP NOT NULL          -- DB insert time
│
INDEXES:
  idx_audit_run         ON (run_id)
  idx_audit_bot_repo_pr ON (bot_id, repo_owner, repo_name, pr_number)
  idx_audit_bot         ON (bot_id)
  idx_audit_type        ON (event_type)
```

### Payload JSON — per event type

Each event type carries a flat JSON object with type-specific fields:

- **PR_WORKFLOW_RUN_STARTED**: `{"workflow_key":"review","run_id":42,"trigger":"webhook","webhook_action":"opened"}`
- **PR_WORKFLOW_STEP_APPENDED**: `{"run_id":42,"step_order":3,"name":"fetch-diff","status":"INFO","log_excerpt":"..."}`
- **PR_WORKFLOW_RUN_COMPLETED**: `{"run_id":42,"workflow_key":"review","run_status":"SUCCESS","summary":"...","duration_ms":12345}`
- **TOOL_CALL_EXECUTED**: `{"tool_name":"cat","arguments":"{\"path\":\"README.md\",\"offset\":1,\"limit\":50}","result_excerpt":"# Project README\n\n...","success":true,"round":3}` — tool calls include the round number so LLM decision paths can be reconstructed sequentially. `result_excerpt` is truncated to ~1 KB.
- **REVIEW_COMPLETED**: `{"decision":"APPROVE","review_length_chars":5400,"run_id":99}`

The payload is supplementary — core dimensions (bot, repo, pr, run, step) are normalized columns for querying.

### AI Correlation

When an audit event is caused by AI output:
- `ai_session_id` → references `agent_sessions.id` (for agentic workflows) or `review_sessions.id` (for legacy reviews). Nullable since not every event is AI-driven.
- `ai_usage_session_id` → references `ai_usage_log.session_id` for token-usage correlation.

Both are set by the emission site when available from the running context.

### Failure Safety

Audit writes follow the `AiUsageService` pattern:
```java
@Transactional
public void emit(...) {
    try {
        PrAuditEvent event = buildEvent(...);
        event.setHash(computeHash(event, previousHash));
        auditRepository.save(event);
    } catch (Exception e) {
        log.error("Failed to persist audit event type={} for run={}: {}",
                eventType, runId, e.getMessage(), e);
        // Never propagate — audit failures must not corrupt workflow state
    }
}
```

This means audit records are **best-effort** for v1. If compliance requirements later demand fail-closed behavior, that's a separate configuration flag.

---

## Implementation Tasks

### Phase 1: Data Model & Migration (Tasks 1–3)

#### Task 1: Create `AuditEventType` enum

**Objective:** Define all event types including deferred ones.

**Files:**
- Create: `src/main/java/org/remus/giteabot/audit/AuditEventType.java`

**Content:**
```java
package org.remus.giteabot.audit;

/**
 * All known audit event types. Types whose underlying product action does not
 * yet exist are documented as deferred here; their audit events will be emitted
 * when the feature is implemented.
 */
public enum AuditEventType {
    /** Workflow lifecycle — implemented. */
    PR_WORKFLOW_RUN_STARTED,
    PR_WORKFLOW_STEP_APPENDED,
    PR_WORKFLOW_RUN_COMPLETED,

    /** Review — implemented. */
    REVIEW_COMPLETED,

    /** Deferred: not yet in product. */
    @Deprecated GATE_OVERRIDDEN,
    @Deprecated FINDING_SUPPRESSED,
    @Deprecated PULL_REQUEST_MERGED,

    ;

    /** True when this event type has a corresponding emission site in the codebase. */
    public boolean isImplemented() {
        return switch (this) {
            case PR_WORKFLOW_RUN_STARTED, PR_WORKFLOW_STEP_APPENDED,
                 PR_WORKFLOW_RUN_COMPLETED, TOOL_CALL_EXECUTED, REVIEW_COMPLETED -> true;
            default -> false;
        };
    }
}
```

**Verification:** Compiles cleanly.

---

#### Task 2: Create `PrAuditEvent` entity + repository

**Objective:** Define the JPA entity and Spring Data repository.

**Files:**
- Create: `src/main/java/org/remus/giteabot/audit/PrAuditEvent.java`
- Create: `src/main/java/org/remus/giteabot/audit/PrAuditEventRepository.java`

**PrAuditEvent.java:**
```java
package org.remus.giteabot.audit;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "pr_audit_events")
public class PrAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private AuditEventType eventType;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(nullable = false, length = 64)
    private String hash;

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "bot_id", nullable = false)
    private Long botId;

    @Column(name = "repo_owner", nullable = false)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(name = "pr_number", nullable = false)
    private Long prNumber;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "step_index")
    private Integer stepIndex;

    @Column(name = "step_name")
    private String stepName;

    @Column(name = "step_status", length = 32)
    private String stepStatus;

    @Column(name = "actor_type", nullable = false, length = 32)
    private String actorType;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "ai_session_id")
    private Long aiSessionId;

    @Column(name = "ai_usage_session_id")
    private String aiUsageSessionId;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "event_payload_json", columnDefinition = "TEXT")
    private String eventPayloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
```

**PrAuditEventRepository.java:**
```java
package org.remus.giteabot.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrAuditEventRepository extends JpaRepository<PrAuditEvent, Long> {

    List<PrAuditEvent> findByRunIdOrderByIdAsc(Long runId);

    List<PrAuditEvent> findByBotIdAndRepoOwnerAndRepoNameAndPrNumberOrderByIdAsc(
            Long botId, String repoOwner, String repoName, Long prNumber);

    List<PrAuditEvent> findByBotIdOrderByIdAsc(Long botId);

    /** Returns the most recent event for a run (for previous-hash lookup). */
    PrAuditEvent findTopByRunIdOrderByIdDesc(Long runId);
}
```

**Verification:** `mvn -q -Denforcer.skip=true compile`

---

#### Task 3: Create Flyway migration V34

**Objective:** Add `pr_audit_events` table to both H2 and PostgreSQL.

**Files:**
- Create: `src/main/resources/db/migration/h2/V34__pr_audit_events.sql`
- Create: `src/main/resources/db/migration/postgresql/V34__pr_audit_events.sql`

**V34__pr_audit_events.sql** (both variants — identical SQL in this case):
```sql
-- V34: Tamper-evident audit trail for PR workflow and review actions.
-- Each run gets its own hash chain; genesis events have previous_hash=NULL.
-- Application code must only INSERT — never UPDATE or DELETE audit rows.

CREATE TABLE IF NOT EXISTS pr_audit_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    hash CHAR(64) NOT NULL,
    previous_hash CHAR(64),
    bot_id BIGINT NOT NULL,
    repo_owner VARCHAR(255) NOT NULL,
    repo_name VARCHAR(255) NOT NULL,
    pr_number BIGINT NOT NULL,
    run_id BIGINT,
    step_index INTEGER,
    step_name VARCHAR(255),
    step_status VARCHAR(32),
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(255),
    ai_session_id BIGINT,
    ai_usage_session_id VARCHAR(255),
    correlation_id VARCHAR(255),
    duration_ms BIGINT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    event_payload_json TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_pr_audit_event_run
        FOREIGN KEY (run_id) REFERENCES pr_workflow_runs(id)
        ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_run ON pr_audit_events (run_id);
CREATE INDEX IF NOT EXISTS idx_audit_bot_repo_pr ON pr_audit_events (bot_id, repo_owner, repo_name, pr_number);
CREATE INDEX IF NOT EXISTS idx_audit_bot ON pr_audit_events (bot_id);
CREATE INDEX IF NOT EXISTS idx_audit_type ON pr_audit_events (event_type);
```

**Verification:** `mvn -q -Denforcer.skip=true compile` (application starts with H2 in-memory DB).

---

### Phase 2: Service Layer (Tasks 4–5)

#### Task 4: Create `PrAuditEventService` — emission

**Objective:** Service that builds, hashes, and persists audit events. Follows the `AiUsageService` failure-safety pattern.

**Files:**
- Create: `src/main/java/org/remus/giteabot/audit/PrAuditEventService.java`

**PrAuditEventService.java:**
```java
package org.remus.giteabot.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrAuditEventService {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PrAuditEventRepository auditRepository;

    /**
     * Emits an audit event. Failures are logged and never propagated —
     * audit must not corrupt workflow state.
     */
    @Transactional
    public void emit(AuditEventType eventType,
                     Instant eventTimestamp,
                     Long botId,
                     String repoOwner,
                     String repoName,
                     Long prNumber,
                     Long runId,
                     String actorType,
                     String actorId,
                     Long aiSessionId,
                     String aiUsageSessionId,
                     Map<String, Object> payload,
                     Long durationMs,
                     Long inputTokens,
                     Long outputTokens) {
        try {
            String previousHash = resolvePreviousHash(runId);
            String payloadJson = payload != null && !payload.isEmpty()
                    ? CANONICAL_MAPPER.writeValueAsString(payload) : null;

            PrAuditEvent event = new PrAuditEvent();
            event.setEventType(eventType);
            event.setEventTimestamp(eventTimestamp != null ? eventTimestamp : Instant.now());
            event.setBotId(botId);
            event.setRepoOwner(repoOwner);
            event.setRepoName(repoName);
            event.setPrNumber(prNumber);
            event.setRunId(runId);
            event.setActorType(actorType);
            event.setActorId(actorId);
            event.setAiSessionId(aiSessionId);
            event.setAiUsageSessionId(aiUsageSessionId);
            event.setPreviousHash(previousHash);
            event.setEventPayloadJson(payloadJson);
            event.setDurationMs(durationMs);
            event.setInputTokens(inputTokens);
            event.setOutputTokens(outputTokens);

            String hash = computeHash(eventType.name(), event.getEventTimestamp(),
                    payloadJson, previousHash);
            event.setHash(hash);

            auditRepository.save(event);
            log.debug("Audit event persisted: type={} runId={} id={}", eventType, runId, event.getId());
        } catch (Exception e) {
            log.error("Failed to persist audit event type={} runId={}: {}", eventType, runId, e.getMessage(), e);
        }
    }

    /**
     * Convenience overload: emit a step-level event with step metadata on the entity.
     */
    @Transactional
    public void emitStep(AuditEventType eventType,
                         Instant eventTimestamp,
                         Long botId,
                         String repoOwner,
                         String repoName,
                         Long prNumber,
                         Long runId,
                         String actorType,
                         String actorId,
                         Long aiSessionId,
                         String aiUsageSessionId,
                         Map<String, Object> payload,
                         int stepIndex,
                         String stepName,
                         String stepStatus,
                         Long durationMs,
                         Long inputTokens,
                         Long outputTokens) {
        try {
            String previousHash = resolvePreviousHash(runId);
            String payloadJson = payload != null && !payload.isEmpty()
                    ? CANONICAL_MAPPER.writeValueAsString(payload) : null;

            PrAuditEvent event = new PrAuditEvent();
            event.setEventType(eventType);
            event.setEventTimestamp(eventTimestamp != null ? eventTimestamp : Instant.now());
            event.setBotId(botId);
            event.setRepoOwner(repoOwner);
            event.setRepoName(repoName);
            event.setPrNumber(prNumber);
            event.setRunId(runId);
            event.setActorType(actorType);
            event.setActorId(actorId);
            event.setAiSessionId(aiSessionId);
            event.setAiUsageSessionId(aiUsageSessionId);
            event.setPreviousHash(previousHash);
            event.setEventPayloadJson(payloadJson);
            event.setStepIndex(stepIndex);
            event.setStepName(stepName);
            event.setStepStatus(stepStatus);
            event.setDurationMs(durationMs);
            event.setInputTokens(inputTokens);
            event.setOutputTokens(outputTokens);

            String hash = computeHash(eventType.name(), event.getEventTimestamp(),
                    payloadJson, previousHash);
            event.setHash(hash);

            auditRepository.save(event);
            log.debug("Audit step event persisted: type={} runId={} step={} id={}",
                    eventType, runId, stepName, event.getId());
        } catch (Exception e) {
            log.error("Failed to persist audit step event type={} runId={}: {}",
                    eventType, runId, e.getMessage(), e);
        }
    }

    /**
     * Convenience overload: emit a tool-call event with round, tool name, arguments, result,
     * duration, and token usage. Arguments and result are truncated to safe limits.
     */
    @Transactional
    public void emitToolCall(Long botId,
                             String repoOwner,
                             String repoName,
                             Long prNumber,
                             Long runId,
                             Long aiSessionId,
                             String aiUsageSessionId,
                             int round,
                             String toolName,
                             String arguments,
                             String resultExcerpt,
                             boolean success,
                             long durationMs,
                             Long inputTokens,
                             Long outputTokens) {
        String args = arguments != null && arguments.length() > 2000
                ? arguments.substring(0, 1997) + "..." : arguments;
        String result = resultExcerpt != null && resultExcerpt.length() > 1024
                ? resultExcerpt.substring(0, 1021) + "..." : resultExcerpt;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("tool_name", toolName);
        payload.put("arguments", args);
        payload.put("result_excerpt", result);
        payload.put("success", success);
        payload.put("round", round);
        emit(AuditEventType.TOOL_CALL_EXECUTED, Instant.now(),
                botId, repoOwner, repoName, prNumber, runId,
                ActorType.BOT, "agent",
                aiSessionId, aiUsageSessionId,
                payload,
                durationMs, inputTokens, outputTokens);
    }

    @Transactional(readOnly = true)
    public List<PrAuditEvent> findByRunId(Long runId) {
        return auditRepository.findByRunIdOrderByIdAsc(runId);
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

    private String resolvePreviousHash(Long runId) {
        if (runId == null) return null;
        PrAuditEvent previous = auditRepository.findTopByRunIdOrderByIdDesc(runId);
        return previous != null ? previous.getHash() : null;
    }

    static String computeHash(String eventType, Instant timestamp,
                              String payloadJson, String previousHash) {
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
```

**Verification:** `mvn -q -Denforcer.skip=true compile`

---

#### Task 5: Add `actorType` constants class

**Objective:** Avoid magic strings for actor types.

**Files:**
- Create: `src/main/java/org/remus/giteabot/audit/ActorType.java`

```java
package org.remus.giteabot.audit;

/** Standard actor types for audit events. */
public final class ActorType {
    private ActorType() {}

    public static final String SYSTEM = "SYSTEM";
    public static final String BOT = "BOT";
    public static final String WEBHOOK = "WEBHOOK";
    // Deferred until human action tracking exists:
    // public static final String HUMAN = "HUMAN";
}
```

**Verification:** `mvn -q -Denforcer.skip=true compile`

---

### Phase 3: Integration Points (Tasks 6–9)

#### Task 6: Wire audit into `PrWorkflowOrchestrator.run()`

**Objective:** Emit events for run start, completion, cancellation, and failure.

**Files:**
- Modify: `src/main/java/org/remus/giteabot/prworkflow/PrWorkflowOrchestrator.java`

**Changes:**

1. Add `PrAuditEventService` as a constructor dependency.

2. Emit `PR_WORKFLOW_RUN_STARTED` after `runService.start()` returns (line ~136):
```java
auditService.emit(
    AuditEventType.PR_WORKFLOW_RUN_STARTED,
    run.getStartedAt(),
    bot.getId(), owner, repoName, prNumber,
    run.getId(),
    ActorType.SYSTEM, "orchestrator",
    null, null,
    Map.of("workflow_key", workflow.key(), "trigger", "webhook"),
    null, null, null);
```

3. Emit `PR_WORKFLOW_RUN_COMPLETED` in the success path (line ~158, after `complete()`):
```java
auditService.emit(
    AuditEventType.PR_WORKFLOW_RUN_COMPLETED,
    Instant.now(),
    bot.getId(), owner, repoName, prNumber,
    run.getId(),
    ActorType.SYSTEM, "orchestrator",
    null, null,
    Map.of("run_status", effective.name(), "workflow_key", workflow.key(),
           "summary", completed.getSummary() != null ? completed.getSummary() : ""),
    Duration.between(startInstant, Instant.now()).toMillis(), null, null);
```

4. Emit `PR_WORKFLOW_RUN_COMPLETED` in the cancellation path (line ~172):
```java
auditService.emit(
    AuditEventType.PR_WORKFLOW_RUN_COMPLETED,
    Instant.now(),
    bot.getId(), owner, repoName, prNumber,
    run.getId(),
    ActorType.SYSTEM, "orchestrator",
    null, null,
    Map.of("run_status", "CANCELLED", "workflow_key", workflow.key(),
           "reason", cancelled.getMessage()),
    null, null, null);
```

5. Emit `PR_WORKFLOW_RUN_COMPLETED` in the failure path (line ~186, after `complete()` call):
```java
auditService.emit(
    AuditEventType.PR_WORKFLOW_RUN_COMPLETED,
    Instant.now(),
    bot.getId(), owner, repoName, prNumber,
    context.runId(),
    ActorType.SYSTEM, "orchestrator",
    null, null,
    Map.of("run_status", "FAILED", "workflow_key", workflow.key(),
           "error", truncateForSummary(e.getMessage())),
    null, null, null);
```
**Verification:** `mvn -q -Denforcer.skip=true compile`

---

#### Task 7: Wire audit into `PrWorkflowRunService.appendStep()`

**Objective:** Emit `PR_WORKFLOW_STEP_APPENDED` for each step.

**Files:**
- Modify: `src/main/java/org/remus/giteabot/prworkflow/PrWorkflowRunService.java`

**Changes:**

1. Add `PrAuditEventService` as a constructor dependency.

2. After saving the step (line ~96, after `stepRepository.save(step)`), emit:
```java
auditService.emitStep(
    AuditEventType.PR_WORKFLOW_STEP_APPENDED,
    step.getCreatedAt(),
    run.getBotId(), run.getRepoOwner(), run.getRepoName(), run.getPrNumber(),
    run.getId(),
    ActorType.SYSTEM, "workflow-step",
    null, null,
    Map.of("run_id", run.getId(), "step_order", step.getStepOrder()),
    step.getStepOrder(), step.getName(), step.getStatus(),
    null, null, null);
```

**Verification:** `mvn -q -Denforcer.skip=true compile`

---

#### Task 8: Wire audit into review completion paths

**Objective:** Emit `REVIEW_COMPLETED` from both agentic and legacy review.

**Files:**
- Modify: `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java`
- Modify: `src/main/java/org/remus/giteabot/review/CodeReviewService.java`

**AgentReviewService.reviewPullRequest():**

After successfully posting the review (line ~202), before returning:
```java
// Inside the try block, after postReview/postReviewComment succeeded:
if (context.auditService() != null) {
    context.auditService().emit(
        AuditEventType.REVIEW_COMPLETED,
        Instant.now(),
        context.bot().getId(),
        owner, repo, prNumber,
        context.runId(),
        ActorType.BOT, context.bot().getName(),
        session.getId(),      // ai_session_id → agent_sessions.id
        null,                 // ai_usage_session_id could be threaded if available
        Map.of("decision", action.name(),
               "review_length_chars", reviewBody.length()),
        null, null, null);    // no duration/token data at service level
}
```

Note: `AgentReviewContext` needs an `auditService()` and `bot()` accessor. Check existing fields — if `bot` is available via context, use it; otherwise add.

**CodeReviewService.reviewPullRequest():**

After successfully posting the review (find the successful return point), add:
```java
// When the existing ReviewSession is available:
if (auditService != null && reviewSession != null) {
    auditService.emit(
        AuditEventType.REVIEW_COMPLETED,
        Instant.now(),
        botId, owner, repo, prNumber,
        runId,  // needs to be threaded from the orchestrator
        ActorType.BOT, botUsername,
        reviewSession.getId(),  // ai_session_id → review_sessions.id
        null,
        Map.of("prompt_name", promptName),
        null, null, null);
}
```

For the legacy review path, the `botId` and `runId` need to be available. Check how `CodeReviewService` receives bot identity — it likely gets it through its constructor or call parameters. If `runId` is not currently available, pass it as a parameter or store it.

**Verification:** `mvn -q -Denforcer.skip=true compile`

---

#### Task 9: Wire audit into agent tool-call execution path

**Objective:** Emit `TOOL_CALL_EXECUTED` for each tool invocation by the LLM agent. This gives a sequential record of every tool the agent called — enough to reconstruct the agent's decision path without storing full prompts.

**Files:**
- Modify: `src/main/java/org/remus/giteabot/agent/loop/AgentLoop.java`

**Approach:** Extend `AgentRunContext` with an optional `auditToolCallConsumer` callback. The `AgentLoop` calls it after each successful tool execution. Workflow services that have audit context (and a `runId`) set the callback; services without audit context leave it null.

**AgentRunContext changes:**
```java
// Add to AgentRunContext:
@Setter
private Consumer<ToolCallRecord> auditToolCallConsumer;

public record ToolCallRecord(
    String toolName,
    String arguments,       // raw JSON (truncated to 2 KB by the service)
    String resultExcerpt,   // first 1 KB of tool result
    boolean success,
    long durationMs,
    Long inputTokens,       // from this specific LLM round
    Long outputTokens,
    int round               // agent loop round number
) {}
```

**AgentLoop changes:**

In the loop, after executing a tool call and getting its result:
```java
// After successful tool execution (inside the round loop):
if (ctx.auditToolCallConsumer() != null) {
    long duration = System.nanoTime() - toolStartNanos;
    ctx.auditToolCallConsumer().accept(new AgentRunContext.ToolCallRecord(
        toolName, args, resultSummary, true,
        Duration.ofNanos(duration).toMillis(),
        currentRoundInputTokens, currentRoundOutputTokens, round));
}
```

**Wiring from workflow services:**

In `AgentReviewService.runReviewLoop()` (and similarly in `ReadmeSyncService`, `I18nCoverageService`, etc.), before running the agent loop:
```java
ctx.setAuditToolCallConsumer(record -> 
    auditService.emitToolCall(
        context.bot().getId(), owner, repo, prNumber,
        context.runId(),
        session.getId(),     // ai_session_id → agent_sessions.id
        null,                 // ai_usage_session_id from TokenUsageTracker if available
        record.round(),
        record.toolName(),
        record.arguments(),
        record.resultExcerpt(),
        record.success(),
        record.durationMs(),
        record.inputTokens(),
        record.outputTokens()));
```

**Scope for v1:** Wire into `AgentReviewService` (agentic review) + `ReadmeSyncService` + `I18nCoverageService`. The legacy `CodeReviewService` does not use the agent loop and is out of scope.

**Verification:** `mvn -q -Denforcer.skip=true compile`

---

### Phase 4: Tests (Tasks 10–14)

#### Task 10: Test `PrAuditEventService` — deterministic hash + persistence

**Files:**
- Create: `src/test/java/org/remus/giteabot/audit/PrAuditEventServiceTest.java`

**Test cases:**
```java
@SpringBootTest
@Transactional
class PrAuditEventServiceTest {

    @Autowired PrAuditEventService auditService;
    @Autowired PrAuditEventRepository auditRepository;
    @Autowired PrWorkflowRunService runService;
    @Autowired TestBotFactory botFactory;  // or use existing test infrastructure

    @Test
    void shouldEmitAndPersistAuditEvent() {
        // given: a workflow run
        PrWorkflowRun run = runService.start(1L, "owner", "repo", 1L, "review");

        // when
        auditService.emit(AuditEventType.PR_WORKFLOW_RUN_STARTED,
                Instant.now(), 1L, "owner", "repo", 1L, run.getId(),
                "SYSTEM", "test", null, null,
                Map.of("workflow_key", "review"));

        // then
        List<PrAuditEvent> events = auditService.findByRunId(run.getId());
        assertThat(events).hasSize(1);
        PrAuditEvent event = events.get(0);
        assertThat(event.getEventType()).isEqualTo(AuditEventType.PR_WORKFLOW_RUN_STARTED);
        assertThat(event.getHash()).isNotNull().hasSize(64);
        assertThat(event.getPreviousHash()).isNull(); // genesis
    }

    @Test
    void shouldProduceDeterministicHash() {
        String hash1 = PrAuditEventService.computeHash("TEST", Instant.EPOCH,
                "{\"a\":\"1\"}", null);
        String hash2 = PrAuditEventService.computeHash("TEST", Instant.EPOCH,
                "{\"a\":\"1\"}", null);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void shouldChainHashesAcrossEvents() {
        PrWorkflowRun run = runService.start(1L, "owner", "repo", 1L, "review");

        auditService.emit(AuditEventType.PR_WORKFLOW_RUN_STARTED,
                Instant.now(), 1L, "owner", "repo", 1L, run.getId(),
                "SYSTEM", "test", null, null, Map.of());
        auditService.emit(AuditEventType.PR_WORKFLOW_STEP_APPENDED,
                Instant.now(), 1L, "owner", "repo", 1L, run.getId(),
                "SYSTEM", "test", null, null, Map.of("step", 1));

        List<PrAuditEvent> events = auditService.findByRunId(run.getId());
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getPreviousHash()).isEqualTo(events.get(0).getHash());
    }

    @Test
    void shouldNotCorruptWorkflowOnAuditFailure() {
        // Simulate a failure scenario (e.g., mock a repository exception)
        // and verify the calling code doesn't throw
    }

    @Test
    void shouldQueryByBotRepoPr() {
        // emit events for different PRs, verify filtering
    }

    @Test
    void shouldQueryByBot() {
        // emit events for different bots, verify filtering
    }
}
```

**Verification:** `mvn -Denforcer.skip=true -Dtest='PrAuditEventServiceTest' test`

---

#### Task 11: Test append-only behavior (application layer)

**Objective:** Verify that `PrAuditEventRepository` only exposes insert/query methods — no `delete` or `save` (update).

**Files:**
- Create: `src/test/java/org/remus/giteabot/audit/PrAuditEventRepositoryTest.java`

This is mostly a design test: the repository interface intentionally does not extend any delete-capable interface. The test verifies this by reflection — or more practically, by confirming no `delete` or explicit `save` (merge) is called in the service layer.

**Verification:** `mvn -Denforcer.skip=true -Dtest='PrAuditEventRepositoryTest' test`

---

#### Task 12: Test orchestration integration

**Objective:** Verify that `PrWorkflowOrchestrator` emits lifecycle events.

**Files:**
- Create or extend: `src/test/java/org/remus/giteabot/prworkflow/PrWorkflowOrchestratorAuditTest.java`

**Test cases:**
- Run a workflow → verify `PR_WORKFLOW_RUN_STARTED` + `PR_WORKFLOW_RUN_COMPLETED` emitted
- Run a workflow that fails → verify `PR_WORKFLOW_RUN_COMPLETED` with `run_status=FAILED`
- Run a workflow with steps → verify `PR_WORKFLOW_STEP_APPENDED` events ordered correctly
- Cancel a run → verify `PR_WORKFLOW_RUN_COMPLETED` with `run_status=CANCELLED`
- Run completed events → verify `duration_ms` is non-null and positive
- Verify that `emit()` is called with `null, null, null` for trailing params on non-timed events (run start)
- Mock-based tests: use `ArgumentCaptor` to inspect the `durationMs`, `inputTokens`, `outputTokens` passed to `emit()`

Use Spring Boot test with `@MockBean` for `PrAuditEventService` and verify calls with `Mockito.verify()`.

**Verification:** `mvn -Denforcer.skip=true -Dtest='PrWorkflowOrchestratorAuditTest' test`

---

#### Task 13: Test tool-call audit events + timing / tokens

**Objective:** Verify tool-call event emission, proper truncation, and that duration/token fields are correctly persisted.

**Files:**
- Create: `src/test/java/org/remus/giteabot/audit/PrAuditEventToolCallTest.java`

**Test cases:**
```java
@SpringBootTest
@Transactional
class PrAuditEventToolCallTest {

    @Autowired PrAuditEventService auditService;

    @Test
    void shouldEmitToolCallEventWithAllFields() {
        auditService.emitToolCall(
            1L, "owner", "repo", 1L, 42L,       // bot, repo, pr, run
            100L, "session-abc",                  // ai_session, ai_usage_session
            3,                                    // round
            "cat",
            "{\"path\":\"/tmp/test.txt\"}",
            "# Hello World\n\nSome content...",
            true,                                 // success
            250,                                  // duration
            1200L, 300L);                         // tokens

        List<PrAuditEvent> events = auditService.findByRunId(42L);
        assertThat(events).hasSize(1);
        PrAuditEvent e = events.get(0);
        assertThat(e.getEventType()).isEqualTo(AuditEventType.TOOL_CALL_EXECUTED);
        assertThat(e.getDurationMs()).isEqualTo(250);
        assertThat(e.getInputTokens()).isEqualTo(1200);
        assertThat(e.getOutputTokens()).isEqualTo(300);
    }

    @Test
    void shouldTruncateArgumentsAndResult() {
        String hugeArgs = "x".repeat(5000);
        String hugeResult = "y".repeat(5000);

        auditService.emitToolCall(
            1L, "owner", "repo", 1L, 42L,
            null, null, 1, "cat", hugeArgs, hugeResult,
            true, 100, null, null);

        List<PrAuditEvent> events = auditService.findByRunId(42L);
        String payload = events.get(0).getEventPayloadJson();

        // Arguments truncated to 2000, result to 1024
        assertThat(payload).contains("...");  // truncation marker
        assertThat(payload.length()).isLessThan(5000);  // well within limits
    }

    @Test
    void shouldChainToolCallsInRun() {
        auditService.emit(AuditEventType.PR_WORKFLOW_RUN_STARTED,
            Instant.now(), 1L, "o", "r", 1L, 99L,
            "SYSTEM", "test", null, null, Map.of(),
            null, null, null);

        auditService.emitToolCall(1L, "o", "r", 1L, 99L,
            null, null, 1, "read", "{}", "content", true, 10, 50L, 10L);

        auditService.emitToolCall(1L, "o", "r", 1L, 99L,
            null, null, 2, "write", "{}", "OK", true, 20, 50L, 10L);

        List<PrAuditEvent> events = auditService.findByRunId(99L);
        assertThat(events).hasSize(3);
        // Chain integrity
        assertThat(events.get(1).getPreviousHash()).isEqualTo(events.get(0).getHash());
        assertThat(events.get(2).getPreviousHash()).isEqualTo(events.get(1).getHash());
    }

    @Test
    void shouldStoreRoundNumberInPayload() throws Exception {
        auditService.emitToolCall(1L, "o", "r", 1L, 42L,
            null, null, 5, "cat", "{}", "ok", true, 0, null, null);

        List<PrAuditEvent> events = auditService.findByRunId(42L);
        String payload = events.get(0).getEventPayloadJson();
        assertThat(payload).contains("\"round\":5");
        assertThat(payload).contains("\"tool_name\":\"cat\"");
        assertThat(payload).contains("\"success\":true");
    }
}
```

**Verification:** `mvn -Denforcer.skip=true -Dtest='PrAuditEventToolCallTest' test`

---

#### Task 14: Run full test suite

**Expected:** All existing tests pass + new audit tests pass. Run in background with `notify_on_complete=true`.

---

## Files Changed Summary

| Action | File |
|--------|------|
| Create | `src/main/java/org/remus/giteabot/audit/AuditEventType.java` |
| Create | `src/main/java/org/remus/giteabot/audit/PrAuditEvent.java` |
| Create | `src/main/java/org/remus/giteabot/audit/PrAuditEventRepository.java` |
| Create | `src/main/java/org/remus/giteabot/audit/PrAuditEventService.java` |
| Create | `src/main/java/org/remus/giteabot/audit/ActorType.java` |
| Create | `src/main/resources/db/migration/h2/V34__pr_audit_events.sql` |
| Create | `src/main/resources/db/migration/postgresql/V34__pr_audit_events.sql` |
| Modify | `src/main/java/org/remus/giteabot/prworkflow/PrWorkflowOrchestrator.java` |
| Modify | `src/main/java/org/remus/giteabot/prworkflow/PrWorkflowRunService.java` |
| Modify | `src/main/java/org/remus/giteabot/agent/loop/AgentRunContext.java` (add `ToolCallRecord`, `auditToolCallConsumer`) |
| Modify | `src/main/java/org/remus/giteabot/agent/loop/AgentLoop.java` (emit tool-call records) |
| Modify | `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java` (wire audit + tool-call consumer) |
| Modify | `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewContext.java` (add auditService/bot accessors if needed) |
| Modify | `src/main/java/org/remus/giteabot/review/CodeReviewService.java` (wire audit + trailing params) |
| Modify | `src/main/java/org/remus/giteabot/prworkflow/readmesync/ReadmeSyncService.java` (wire tool-call consumer, v1 scope) |
| Modify | `src/main/java/org/remus/giteabot/prworkflow/i18n/I18nCoverageService.java` (wire tool-call consumer, v1 scope) |
| Create | `src/test/java/org/remus/giteabot/audit/PrAuditEventServiceTest.java` |
| Create | `src/test/java/org/remus/giteabot/audit/PrAuditEventRepositoryTest.java` |
| Create | `src/test/java/org/remus/giteabot/audit/PrAuditEventToolCallTest.java` |
| Create | `src/test/java/org/remus/giteabot/prworkflow/PrWorkflowOrchestratorAuditTest.java` |

## Risks, Tradeoffs, and Open Items

1. **Transaction boundaries**: The `emit()` method is `@Transactional`. When called from within `PrWorkflowRunService.appendStep()` (also `@Transactional`), it inherits the outer transaction — meaning the audit record is committed/rolled-back with the workflow step. This is correct for consistency. The inner `try/catch` prevents audit failures from propagating.

2. **Hash computation ordering guarantee**: Ordering relies on auto-increment IDs within a run. Since `emit()` flushes immediately (via `save()`), the DB-assigned ID is deterministic for the chain. However, if two concurrent requests emit to the same run, the chain order could interleave. This is acceptable because:
   - `PrWorkflowRunLockManager.withLock()` serializes runs per (bot, repo, pr, workflow) tuple.
   - Steps are appended sequentially by the single workflow thread.

3. **`step_index` population**: Currently derived from `run.getSteps().size()` in `PrWorkflowRunService.appendStep()`. The audit event picks this up. Ensure `step_index` is set on the entity before `emitStep()` is called.

4. **Legacy CodeReviewService runId**: The legacy review path may not have a `runId` readily available. Need to trace the call chain and either thread it through or use `null` (which means `previousHash` will also be `null` — a singleton genesis event per review).

5. **AgentReviewContext changes**: Need to add `auditService()` and `bot()` accessors. `bot` is likely already available; `auditService` needs to be threaded through the factory.

6. **Deferred events**: `GATE_OVERRIDDEN`, `FINDING_SUPPRESSED`, `PULL_REQUEST_MERGED` are defined on the enum with `@Deprecated` annotation and `isImplemented() → false`. No emission sites exist. This documents the intent cleanly.

7. **No verification API**: Hash chain verification is a developer tool for now. A future issue can add `GET /api/audit/verify/{runId}` that recomputes hashes and reports tampering.

8. **Tool-call result sanitization**: `emitToolCall()` truncates arguments to 2 KB and results to 1 KB. Very large tool results (e.g., whole-file reads of multi-MB files) will be severely truncated in the audit trail. This is intentional for v1 — the full tool result is already visible in application logs. A future improvement could store a hash of the full result alongside the excerpt.

9. **Token tracking granularity**: `input_tokens` / `output_tokens` are set per-event, but the actual token usage is tracked per-LLM-round by the `AgentLoop`. The tool-call event captures the round's cumulative input/output tokens — not the incremental tokens for just that specific tool invocation. This is a known simplification; the `ai_usage_log` remains the authoritative source for per-call token accounting.

10. **Legacy review path**: `CodeReviewService` does not use the `AgentLoop`, so tool-call tracing is not applicable there. Only `REVIEW_COMPLETED` events are emitted, without tool-call detail. Upgrading legacy review to use the agent loop is a separate effort.
