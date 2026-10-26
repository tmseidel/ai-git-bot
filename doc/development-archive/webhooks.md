# Outgoing Webhooks — Implementation Plan

> **Goal:** Emit signed, asynchronous outgoing webhooks to admin-configured endpoints when PR-workflow and issue-assignment events occur, with per-endpoint event subscriptions, retries with backoff, and an admin UI for endpoint and delivery management.

**Architecture:** A new `eventhook` feature package holds the domain model (endpoint + delivery entities), an event publisher called from the workflow/agent code paths, an `@Async` delivery worker that POSTs HMAC-signed JSON via Spring `RestClient`, and a `@Scheduled` sweeper that retries failed deliveries with exponential backoff. Delivery rows are persisted synchronously (cheap) so event publication is durable and non-blocking; the actual HTTP fan-out happens off the calling thread. An MVC controller + Thymeleaf templates provide the admin UI, following the existing `system-settings` / `DeploymentTarget` CRUD pattern.

**Tech Stack:** Spring Boot 4.0.5, Java 21, Spring Data JPA, Flyway (H2 + PostgreSQL), Spring `RestClient` over Apache HttpClient 5 (both already on the classpath), Thymeleaf, JUnit 5 + Mockito, ArchUnit.

---

## ADR-1: New `eventhook` package instead of extending `webhook`

**Status:** Proposed

**Context**
The existing `org.remus.giteabot.webhook` package (`UnifiedWebhookController`) handles *incoming* Git webhooks. `ArchitectureTest.webhook_package_is_top_layer` forbids any class outside `webhook` from depending on classes inside it. The outgoing-webhook publisher must be callable from `prworkflow` and `agent` code, so it cannot live in `webhook`. The plural name `webhooks` is also unsafe: ArchUnit package predicates match by prefix, so `org.remus.giteabot.webhooks` risks falling under the same rule.

**Decision**
Create a new feature package `org.remus.giteabot.eventhook` containing entities, repositories, services, and the admin `@Controller`. The controller remains an entry point (nothing depends on it), satisfying `controllers_are_not_depended_upon`.

**Consequences**
- No changes to `ArchitectureTest` are needed.
- Incoming and outgoing webhook code stay cleanly separated.

## ADR-2: Durable DB-backed delivery queue vs. in-memory dispatch

**Status:** Proposed

**Context**
Delivery must be async, non-blocking, retried with backoff, and failures must be visible to admins.

**Options Considered**
1. **Pure `@Async` fire-and-forget with in-memory retry state**
   - ✅ Pros: Simple; no schema change.
   - ❌ Cons: Retries lost on restart; no admin visibility into delivery status without a second mechanism.
2. **Persist a delivery row per (endpoint, event) synchronously, dispatch via `@Async`, retry via `@Scheduled` sweeper**
   - ✅ Pros: Durable across restarts; delivery history doubles as the admin "recent delivery status" view; retry scheduling is trivially queryable (`next_attempt_at <= now`); the publish path is a single fast INSERT batch.
   - ❌ Cons: One extra table write per event/endpoint; needs a retention policy (covered by Task 11).

**Decision**
Choose **Option 2**, mirroring the existing `PrAuditEventService` philosophy (persist events, never let failures propagate into the workflow).

**Consequences**
- `EventHookPublisher.publish(...)` runs inside the caller's transaction and never throws.
- A sweeper (`fixedDelayString` from config) picks up `PENDING` and due `RETRYING` deliveries and hands them to the `@Async` worker, so retries survive restarts.
- Terminal (`SUCCESS` and `FAILED`) rows are pruned by a count-based retention GC — keep the newest N deliveries per endpoint across all statuses, N configurable (Task 11) — following the `AuditLogGarbageCollector` pattern. `PENDING`/`RETRYING` rows are never pruned: they are in-flight work (and self-limiting, since the worker exhausts retries into `FAILED` after `max-attempts`).

## ADR-3: No new dependencies

**Status:** Proposed

**Context**
`pom.xml` enforces a dependency whitelist (Maven enforcer, see the `httpclient5` include at pom.xml:244). Adding libraries (e.g. WireMock, a webhook SDK) means whitelist churn.

**Decision**
Use only what is present: Spring `RestClient` (used by `GiteaApiClient`) built on Apache HttpClient 5 for outbound POSTs, Jackson for payload serialization, `javax.crypto.Mac` for HMAC-SHA256. Tests use the JDK's `com.sun.net.httpserver.HttpServer` as a lightweight receiver instead of WireMock.

**Consequences**
- Zero `pom.xml` changes.

## ADR-4: Structured findings in `prworkflow.agentreview.finding.detected`

**Status:** Proposed

**Context**
Today `AgentReviewService` parses a trailing severity-classification JSON block containing *counts* per severity class (`SeverityThresholds`/`blocker, medium, low`, AgentReviewService.java:64-87). The issue asks for per-finding events with severity/category and CWE/OWASP mapping "where applicable".

**Options Considered**
1. **Emit one aggregate event with severity counts only**
   - ✅ Pros: No prompt/parsing changes.
   - ❌ Cons: Misses the point for SIEM/ticketing use cases (no actionable finding detail).
2. **Extend the classification JSON schema with an optional `findings[]` array** (`{severity, category, title, cwe?, owasp?}`), parse it leniently, and emit one `finding.detected` event per finding; fall back to a single aggregate event when the model emits counts only.
   - ✅ Pros: Rich payloads when available; backward tolerant; prompts already ask the model for severity classification, so extending the block is a small prompt change.
   - ❌ Cons: Finding quality depends on model output; schema must tolerate missing fields.

**Decision**
Choose **Option 2**. All `findings[]` fields except `severity` and `title` are optional in payload schema v1; unknown/missing values are serialized as `null` rather than failing the event.

**Consequences**
- `DEFAULT_FORMAL_REVIEW_DECISION_PROMPT` (AgentReviewWorkflow.java:28-44) gets an extended example block; the parser stays backward compatible with counts-only output.

---

## Event Types and Payload Schema (v1)

Enum `EventHookEventType` (wire value = JSON `eventType` string):

| Enum | Wire value | Emitted from |
|---|---|---|
| `PR_WORKFLOW_STARTED` | `prworkflow.started` | `PrWorkflowOrchestrator.run` after `runService.start` |
| `PR_WORKFLOW_COMPLETED` | `prworkflow.completed` | `PrWorkflowOrchestrator.run` after `runService.complete` (any terminal status) |
| `PR_WORKFLOW_FAILED` | `prworkflow.failed` | `PrWorkflowOrchestrator.run` catch block / terminal `FAILED` |
| `AGENT_REVIEW_FINDING_DETECTED` | `prworkflow.agentreview.finding.detected` | `AgentReviewService` after parsing classification |
| `ISSUE_ASSIGNMENT_STARTED` | `issueassignment.started` | `BotWebhookService.handleIssueAssigned` |
| `ISSUE_ASSIGNMENT_COMPLETED` | `issueassignment.completed` | same, on success |
| `ISSUE_ASSIGNMENT_FAILED` | `issueassignment.failed` | same, on exception |

Envelope (all events, `schemaVersion: 1`):

```json
{
  "schemaVersion": 1,
  "id": "01JXZ...",
  "eventType": "prworkflow.agentreview.finding.detected",
  "timestamp": "2026-07-25T10:15:30.123Z",
  "actor": { "type": "BOT", "id": "review-bot" },
  "integration": { "botId": 3, "botName": "review-bot", "platform": "gitea" },
  "repository": { "owner": "acme", "name": "shop", "pullRequest": 42, "issue": null },
  "data": { }
}
```

`data` per event type:
- `prworkflow.started`: `{ "workflowKey": "agent-review", "runId": 128, "trigger": "webhook" }`
- `prworkflow.completed`: `{ "workflowKey": "...", "runId": 128, "status": "SUCCESS", "durationMs": 83400, "summary": "..." }`
- `prworkflow.failed`: `{ "workflowKey": "...", "runId": 128, "error": "..." }`
- `prworkflow.agentreview.finding.detected`: `{ "runId": 128, "finding": { "severity": "BLOCKER", "category": "security", "title": "Hard-coded secret", "cwe": "CWE-798", "owasp": "A07:2021", "file": "src/...", "line": 42 } }` (`category`, `cwe`, `owasp`, `file`, `line` nullable). Fallback aggregate form: `{ "runId": 128, "findingCounts": { "blocker": 1, "medium": 2, "low": 0 } }`
- `issueassignment.*`: `{ "issueNumber": 17, "issueTitle": "...", "branch": "ai/issue-17-...", "error": null }`

HTTP delivery headers:
- `Content-Type: application/json`
- `X-EventHook-Event: prworkflow.completed`
- `X-EventHook-Delivery: <delivery-uuid>`
- `X-EventHook-Signature-256: sha256=<hex HMAC-SHA256 of raw body with endpoint secret>` — **only sent when the endpoint has a secret configured**; the secret is optional. Endpoints without a secret send unsigned payloads (acceptable for trusted internal sinks, e.g. localhost log collectors) and consumers must be documented to treat the header as optional per-endpoint, not per-deployment. (GitHub-style; the incoming side already verifies Gitea/GitHub signatures the same way, see `WebhookTriggerStrategy`)
- `Authorization: <value>` — **only sent when configured on the endpoint**; a static, free-form value (e.g. `Bearer token123456`, `Basic YWxhZGRpbjpvcGVuc2VzYW1l`). Stored encrypted like the secret. Applied *after* custom headers so the dedicated field wins on conflict.
- Plus any admin-defined custom headers.

Success = any 2xx response. Everything else (non-2xx, connect timeout, read timeout, TLS handshake failure, unknown host) = failed attempt → retry.

Per-endpoint TLS: an endpoint can opt out of HTTPS certificate validation (`skipTlsVerify`) for self-signed/internal PKI targets. Off by default; the admin UI must label it clearly as insecure and the worker logs a WARN the first time it delivers to such an endpoint.

---

## Configuration Properties

New `@ConfigurationProperties` bean `EventHookProperties`, prefix `eventhook`:

```properties
eventhook.enabled=true
eventhook.connect-timeout=5s
eventhook.read-timeout=10s
eventhook.retry.max-attempts=5
eventhook.retry.initial-backoff=30s
eventhook.retry.backoff-multiplier=2.0
eventhook.retry.max-backoff=30m
eventhook.sweeper-interval=30s
eventhook.max-payload-bytes=65536
eventhook.retention.keep-last=10
eventhook.retention.gc-cron=0 41 4 * * *
```

All env-overridable (`EVENTHOOK_ENABLED`, `EVENTHOOK_RETRY_MAX_ATTEMPTS`, `EVENTHOOK_RETENTION_KEEP_LAST`, ...) per existing conventions.

`retention.keep-last` is a per-endpoint count applied to the newest N delivery rows of any status: after each GC pass, at most the newest N deliveries survive per endpoint — successful and failed alike, so the deliveries view always shows a bounded, most-recent history. `PENDING`/`RETRYING` rows are exempt (in-flight retries must not be deleted out from under the sweeper; they are self-limiting because attempts cap at `retry.max-attempts`). `0` deletes all terminal deliveries on every pass (payloads are transient, audit lives elsewhere). `retention.gc-cron` defaults to 04:41 server time, offset from the audit GC (04:23) and promoted-suite GC (03:17) windows.

---

## Tasks

### Task 1: Flyway migration V36 — endpoint and delivery tables

**Objective:** Persist webhook endpoint configuration and delivery attempts.

**Files:**
- Create: `src/main/resources/db/migration/h2/V36__outgoing_webhooks.sql`
- Create: `src/main/resources/db/migration/postgresql/V36__outgoing_webhooks.sql`

**H2 / PostgreSQL (adjust only identity column syntax per dialect — match the style of `V34__pr_audit_events.sql` in each directory):**

Idempotent per house convention (`CREATE ... IF NOT EXISTS`, as in V34) so a re-applied or manually pre-created schema never fails the migration:

```sql
CREATE TABLE IF NOT EXISTS event_hook_endpoint (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,  -- postgres: BIGSERIAL / IDENTITY per V34 style
    name            VARCHAR(200) NOT NULL,
    url             VARCHAR(1024) NOT NULL,
    secret          VARCHAR(1000),                           -- OPTIONAL; AES-GCM ciphertext (Base64), like ai_integration.api_key
    authorization_header VARCHAR(1000),                      -- OPTIONAL static Authorization header value, encrypted like secret
    skip_tls_verify BOOLEAN NOT NULL DEFAULT FALSE,          -- opt-out of HTTPS certificate validation (self-signed targets)
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    event_types     VARCHAR(1024) NOT NULL,           -- comma-separated EventHookEventType names
    custom_headers  CLOB,                              -- JSON object of extra headers
    bot_id          BIGINT,                            -- optional scope: integration
    repo_owner      VARCHAR(255),                      -- optional scope: repository
    repo_name       VARCHAR(255),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS event_hook_delivery (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    delivery_uuid   VARCHAR(36) NOT NULL,
    endpoint_id     BIGINT NOT NULL REFERENCES event_hook_endpoint(id) ON DELETE CASCADE,
    event_type      VARCHAR(80) NOT NULL,
    payload_json    CLOB NOT NULL,
    status          VARCHAR(20) NOT NULL,              -- PENDING, SUCCESS, RETRYING, FAILED
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_response_code INT,
    last_error      VARCHAR(2000),
    created_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_event_hook_delivery_due ON event_hook_delivery (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_event_hook_delivery_endpoint ON event_hook_delivery (endpoint_id, id);
```

Note: `IF NOT EXISTS` guards the common re-run/pre-created cases. It deliberately does not reconcile drifted column definitions — that remains the job of a new, higher-numbered migration, matching how the project handles schema evolution elsewhere.

**Verification:** `mvn -q compile` then run `AiGitBotApplicationTests` (context boot runs Flyway against H2): `mvn test -Dtest=AiGitBotApplicationTests`. Expected: PASS, migration applied.

### Task 2: Event type enum and endpoint/delivery status enums

**Objective:** Typed event catalog used by publisher, filtering, and UI checkboxes.

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookEventType.java`
- Create: `src/main/java/org/remus/giteabot/eventhook/DeliveryStatus.java`

```java
package org.remus.giteabot.eventhook;

public enum EventHookEventType {
    PR_WORKFLOW_STARTED("prworkflow.started"),
    PR_WORKFLOW_COMPLETED("prworkflow.completed"),
    PR_WORKFLOW_FAILED("prworkflow.failed"),
    AGENT_REVIEW_FINDING_DETECTED("prworkflow.agentreview.finding.detected"),
    ISSUE_ASSIGNMENT_STARTED("issueassignment.started"),
    ISSUE_ASSIGNMENT_COMPLETED("issueassignment.completed"),
    ISSUE_ASSIGNMENT_FAILED("issueassignment.failed");

    private final String wireValue;

    EventHookEventType(String wireValue) { this.wireValue = wireValue; }

    public String wireValue() { return wireValue; }

    public static EventHookEventType fromWireValue(String value) {
        for (EventHookEventType t : values()) {
            if (t.wireValue.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown event type: " + value);
    }
}
```

```java
package org.remus.giteabot.eventhook;

public enum DeliveryStatus { PENDING, SUCCESS, RETRYING, FAILED }
```

**Test:** `src/test/java/org/remus/giteabot/eventhook/EventHookEventTypeTest.java` — wire-value round trip; `fromWireValue` throws on garbage. Expected: PASS.

### Task 3: `EventHookEndpoint` entity + repository + service

**Objective:** JPA mapping for endpoint config, with helper for subscription parsing; the secret is encrypted at rest exactly like `AiIntegration#apiKey`.

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookEndpoint.java`
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookEndpointRepository.java`
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookEndpointService.java`

Entity: Lombok `@Getter @Setter`, `@Entity`, `@Table(name = "event_hook_endpoint")`, fields matching V36 columns. The `secret` and `authorizationHeader` fields hold **encrypted** values (ciphertext) — never plaintext — and both are nullable. Helpers:

```java
public Set<EventHookEventType> subscribedEventTypes() {
    if (eventTypes == null || eventTypes.isBlank()) return Set.of();
    return Arrays.stream(eventTypes.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(EventHookEventType::valueOf)
            .collect(Collectors.toUnmodifiableSet());
}

public boolean isSubscribedTo(EventHookEventType type) {
    return enabled && subscribedEventTypes().contains(type);
}

/** null/blank scope columns mean "global". */
public boolean matchesScope(Long botId, String repoOwner, String repoName) {
    if (this.botId != null && !this.botId.equals(botId)) return false;
    if (this.repoOwner != null && !this.repoOwner.equalsIgnoreCase(repoOwner)) return false;
    if (this.repoName != null && !this.repoName.equalsIgnoreCase(repoName)) return false;
    return true;
}

public Map<String, String> parsedCustomHeaders(ObjectMapper mapper) { /* JSON -> Map, empty on error */ }
```

Repository:

```java
public interface EventHookEndpointRepository extends JpaRepository<EventHookEndpoint, Long> {
    List<EventHookEndpoint> findByEnabledTrue();
}
```

Service — handles the secret exactly like `AiIntegrationService` handles `apiKey` (admin/AiIntegrationService.java:30-44): encrypt on save, decrypt on read, via the existing `org.remus.giteabot.admin.EncryptionService` (AES-GCM, keyed by `APP_ENCRYPTION_KEY`; transparent plaintext passthrough when no key is configured). Depending on `admin.EncryptionService` from `eventhook` is ArchUnit-safe — `prworkflow` already depends on `admin.Bot` the same way.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class EventHookEndpointService {

    private final EventHookEndpointRepository endpointRepository;
    private final EncryptionService encryptionService;

    @Transactional
    public EventHookEndpoint save(EventHookEndpoint endpoint,
                                String plainSecret, String plainAuthorizationHeader) {
        // Blank on edit = keep current (mirrors the ai-integrations form contract).
        // Both credentials are optional: an endpoint may have neither, either, or both.
        if (plainSecret != null && !plainSecret.isBlank()) {
            endpoint.setSecret(encryptionService.encrypt(plainSecret));
        }
        if (plainAuthorizationHeader != null && !plainAuthorizationHeader.isBlank()) {
            endpoint.setAuthorizationHeader(encryptionService.encrypt(plainAuthorizationHeader));
        }
        return endpointRepository.save(endpoint);
    }

    /** Plaintext secret for HMAC signing, or null when the endpoint signs nothing. */
    public String decryptSecret(EventHookEndpoint endpoint) {
        String secret = endpoint.getSecret();
        return (secret == null || secret.isBlank()) ? null : encryptionService.decrypt(secret);
    }

    /** Plaintext static Authorization header value (e.g. "Bearer token123456"), or null when unset. */
    public String decryptAuthorizationHeader(EventHookEndpoint endpoint) {
        String header = endpoint.getAuthorizationHeader();
        return (header == null || header.isBlank()) ? null : encryptionService.decrypt(header);
    }
}
```

Rules that follow from this and apply to all later tasks:
- Publisher, worker, and controller never call `endpoint.getSecret()` / `getAuthorizationHeader()` for signing, sending, or display — they go through the `decrypt*` methods (delivery) or never touch the fields at all (display).
- The entity getters returning ciphertext is intentional; do not add "convenience" plaintext getters to the entity.
- Both credentials are optional and independent: unsigned+unauthenticated (fully open), signed-only, auth-header-only, or both are all valid configurations.
- Delivery rows (`payload_json`) contain the event payload only — credentials are never copied into deliveries or logs. The worker must not log the signature or Authorization headers at INFO+ levels (DEBUG is acceptable, matching how the project treats credentials elsewhere).

**Test:** `EventHookEndpointTest` — subscription parsing, scope matching matrix (global / bot-only / repo-only / mismatch). `EventHookEndpointServiceTest` — with a real `EncryptionService` initialized from a test key: saved credentials are not plaintext and round-trip through the `decrypt*` methods; blank values on re-save leave existing ciphertext untouched; null credentials decrypt to null (not empty string, not exception). Expected: PASS.

### Task 4: `EventHookDelivery` entity + repository

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookDelivery.java`
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookDeliveryRepository.java`

Entity fields per V36. Repository:

```java
public interface EventHookDeliveryRepository extends JpaRepository<EventHookDelivery, Long> {

    @Query("SELECT d FROM EventHookDelivery d WHERE d.status = 'PENDING' " +
           "OR (d.status = 'RETRYING' AND d.nextAttemptAt <= :now) ORDER BY d.id ASC")
    List<EventHookDelivery> findDueDeliveries(@Param("now") Instant now, Pageable pageable);

    List<EventHookDelivery> findTop50ByEndpointIdOrderByIdDesc(Long endpointId);
}
```

**Verification:** context test passes with new entities (`mvn test -Dtest=AiGitBotApplicationTests`).

### Task 5: `EventHookProperties` configuration

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookProperties.java`
- Modify: `src/main/java/org/remus/giteabot/GiteaBotApplication.java` (add `@ConfigurationPropertiesScan` if not present — check how existing properties like `AgentConfigProperties` are registered and follow that pattern; do NOT introduce a second registration mechanism)

```java
@ConfigurationProperties(prefix = "eventhook")
@Getter @Setter
public class EventHookProperties {
    private boolean enabled = true;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private Duration sweeperInterval = Duration.ofSeconds(30);
    private int maxPayloadBytes = 64 * 1024;
    private Retry retry = new Retry();
    private Retention retention = new Retention();

    @Getter @Setter
    public static class Retention {
        /** Newest deliveries kept per endpoint (any status); 0 = prune all terminal rows. */
        private int keepLast = 10;
        /** GC schedule, cron expression (server time). */
        private String gcCron = "0 41 4 * * *";
    }

    @Getter @Setter
    public static class Retry {
        private int maxAttempts = 5;
        private Duration initialBackoff = Duration.ofSeconds(30);
        private double backoffMultiplier = 2.0;
        private Duration maxBackoff = Duration.ofMinutes(30);
    }

    public Duration backoffForAttempt(int attempt) { // attempt is 1-based
        double seconds = initialBackoff.toSeconds() * Math.pow(backoffMultiplier, attempt - 1);
        return Duration.ofSeconds((long) Math.min(seconds, maxBackoff.toSeconds()));
    }
}
```

**Test:** `EventHookPropertiesTest` — backoff curve 30s, 60s, 120s, capped at max. Expected: PASS.

### Task 6: Payload model and serialization

**Objective:** Versioned envelope DTO serialized with Jackson.

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookPayload.java`

Use nested records so Jackson produces the envelope from the schema section:

```java
public record EventHookPayload(
        int schemaVersion,
        String id,
        String eventType,
        Instant timestamp,
        Actor actor,
        Integration integration,
        RepositoryRef repository,
        Map<String, Object> data) {

    public record Actor(String type, String id) {}
    public record Integration(Long botId, String botName, String platform) {}
    public record RepositoryRef(String owner, String name, Long pullRequest, Long issue) {}

    public static EventHookPayload of(String deliveryUuid, EventHookEventType type,
                                      Bot bot, String owner, String repo,
                                      Long prNumber, Long issueNumber,
                                      Map<String, Object> data) {
        return new EventHookPayload(1, deliveryUuid, type.wireValue(), Instant.now(),
                new Actor("BOT", bot.getName()),
                new Integration(bot.getId(), bot.getName(), bot.getPlatform() /* verify actual getter on Bot */),
                new RepositoryRef(owner, repo, prNumber, issueNumber),
                data == null ? Map.of() : data);
    }
}
```

(Trace the `Bot` entity — `org.remus.giteabot.admin.Bot` — for the exact platform/type getter before writing this; do not guess.)

**Test:** `EventHookPayloadTest` — serialize with a shared `ObjectMapper` (ISO-8601 timestamps via `JavaTimeModule`), assert `schemaVersion`, `eventType` wire value, null `pullRequest` omitted-or-null per Jackson config of the project. Expected: PASS.

### Task 7: HMAC signature service

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookSignatureService.java`

```java
@Service
public class EventHookSignatureService {

    public String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
```

Header name constant: `X-EventHook-Signature-256`.

**Test:** `EventHookSignatureServiceTest` — RFC 4231 HMAC-SHA256 test vector (key `"Jefe"`, data `"what do ya want for nothing?"`) asserting the hex digest matches `5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843` with the `sha256=` prefix. Expected: PASS.

### Task 8: `EventHookPublisher` — non-blocking publication

**Objective:** Single entry point called by workflow/agent code; persists delivery rows and triggers async dispatch; never throws into the caller.

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookPublisher.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class EventHookPublisher {

    private final EventHookProperties properties;
    private final EventHookEndpointRepository endpointRepository;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookDeliveryWorker deliveryWorker;
    private final ObjectMapper objectMapper;

    public void publish(EventHookEventType type, Bot bot, String repoOwner, String repoName,
                        Long prNumber, Long issueNumber, Map<String, Object> data) {
        if (!properties.isEnabled()) return;
        try {
            for (EventHookEndpoint endpoint : endpointRepository.findByEnabledTrue()) {
                if (!endpoint.isSubscribedTo(type)
                        || !endpoint.matchesScope(bot.getId(), repoOwner, repoName)) {
                    continue;
                }
                String uuid = UUID.randomUUID().toString();
                EventHookPayload payload = EventHookPayload.of(uuid, type, bot, repoOwner,
                        repoName, prNumber, issueNumber, data);
                String json = objectMapper.writeValueAsString(payload);
                if (json.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) {
                    log.warn("Skipping oversized webhook payload ({} bytes) for {}", json.length(), type);
                    continue;
                }
                EventHookDelivery delivery = new EventHookDelivery();
                delivery.setDeliveryUuid(uuid);
                delivery.setEndpoint(endpoint);
                delivery.setEventType(type.wireValue());
                delivery.setPayloadJson(json);
                delivery.setStatus(DeliveryStatus.PENDING);
                delivery.setAttempts(0);
                delivery.setCreatedAt(Instant.now());
                delivery = deliveryRepository.save(delivery);
                deliveryWorker.deliverAsync(delivery.getId());   // @Async, returns immediately
            }
        } catch (Exception e) {
            log.error("Failed to publish event-hook event {}: {}", type, e.getMessage(), e);
        }
    }
}
```

Note: `deliverAsync` must be on a *different* bean than the publisher (self-invocation of `@Async` does not work). The worker re-loads the delivery by id inside its own transaction — same pattern the codebase already documents for `@Async` dispatch (`PrWorkflowRun` javadoc, PrWorkflowRun.java:34).

**Tests:** `EventHookPublisherTest` (Mockito): (a) disabled property → no repo interaction; (b) endpoint subscribed → delivery row saved + worker invoked; (c) endpoint subscribed to different type → skipped; (d) scope mismatch → skipped; (e) repository throws → no propagation. Expected: PASS.

### Task 9: `EventHookDeliveryWorker` — HTTP POST with signature

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookDeliveryWorker.java`

```java
@Slf4j
@Service
public class EventHookDeliveryWorker {

    private final RestClient defaultClient;    // standard TLS verification
    private final RestClient insecureClient;   // trust-all, for endpoints with skipTlsVerify=true
    // Constructor builds both from EventHookProperties timeouts over httpclient5 — mirror the
    // request-factory pattern in RepositoryApiClientFactory. The insecure client uses a trust-all
    // SSLContext (org.apache.hc.core5.ssl.SSLContextBuilder + TrustAllStrategy) and
    // NoopHostnameVerifier; verify exact httpclient5 API against the existing factory code.

    private RestClient clientFor(EventHookEndpoint endpoint) {
        if (endpoint.isSkipTlsVerify()) {
            log.warn("Delivering webhook {} with TLS certificate verification DISABLED (endpoint '{}')",
                    endpoint.getUrl(), endpoint.getName());
            return insecureClient;
        }
        return defaultClient;
    }

    @Async
    @Transactional
    public void deliverAsync(Long deliveryId) {
        EventHookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() == DeliveryStatus.SUCCESS) return;
        EventHookEndpoint endpoint = delivery.getEndpoint();
        byte[] body = delivery.getPayloadJson().getBytes(StandardCharsets.UTF_8);
        delivery.setAttempts(delivery.getAttempts() + 1);
        try {
            RestClient.RequestBodySpec request = clientFor(endpoint).post()
                    .uri(URI.create(endpoint.getUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-EventHook-Event", delivery.getEventType())
                    .header("X-EventHook-Delivery", delivery.getDeliveryUuid())
                    .headers(h -> endpoint.parsedCustomHeaders(objectMapper).forEach(h::add))
                    .body(body);
            // Optional HMAC signature — skipped entirely when the endpoint has no secret.
            String secret = endpointService.decryptSecret(endpoint);
            if (secret != null) {
                request = request.header("X-EventHook-Signature-256", signatureService.sign(body, secret));
            }
            // Optional static Authorization header — applied last so it wins over custom headers.
            String authorization = endpointService.decryptAuthorizationHeader(endpoint);
            if (authorization != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            ResponseEntity<Void> response = request.retrieve().toBodilessEntity();
            delivery.setStatus(DeliveryStatus.SUCCESS);
            delivery.setLastResponseCode(response.getStatusCode().value());
            delivery.setCompletedAt(Instant.now());
        } catch (Exception e) {
            Integer code = (e instanceof RestClientResponseException rce)
                    ? rce.getStatusCode().value() : null;
            markFailure(delivery, code, e.getMessage());
        }
        deliveryRepository.save(delivery);
    }

    private void markFailure(EventHookDelivery d, Integer code, String message) {
        d.setLastResponseCode(code);
        d.setLastError(message == null ? "unknown" :
                message.substring(0, Math.min(message.length(), 2000)));
        if (d.getAttempts() >= properties.getRetry().getMaxAttempts()) {
            d.setStatus(DeliveryStatus.FAILED);
            d.setCompletedAt(Instant.now());
        } else {
            d.setStatus(DeliveryStatus.RETRYING);
            d.setNextAttemptAt(Instant.now()
                    .plus(properties.backoffForAttempt(d.getAttempts())));
        }
    }
}
```

**Tests:** `EventHookDeliveryWorkerTest` using JDK `HttpServer` on an ephemeral port:
(a) 200 → SUCCESS, signature header verifiable with the same secret;
(b) endpoint without secret → request arrives with NO `X-EventHook-Signature-256` header;
(c) endpoint with authorization header → exact `Authorization` value received, and it overrides a conflicting custom header;
(d) 500 → RETRYING with `nextAttemptAt ≈ now + initialBackoff`;
(e) attempts == maxAttempts → FAILED;
(f) connection refused (closed port) → RETRYING, `lastError` set, no exception propagated;
(g) `skipTlsVerify` endpoint routes through the insecure client (spy/mock `clientFor` or factor the client map for injection) — a full self-signed TLS round-trip test is optional, the routing decision is the important part.
Expected: PASS.

### Task 10: Retry sweeper

**Objective:** Recover PENDING rows stranded by a crash and drive backoff retries.

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookRetrySweeper.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class EventHookRetrySweeper {

    private final EventHookProperties properties;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookDeliveryWorker worker;

    @Scheduled(fixedDelayString = "#{@eventHookProperties.sweeperInterval.toMillis()}",
               initialDelayString = "15000")
    public void sweep() {
        if (!properties.isEnabled()) return;
        List<EventHookDelivery> due = deliveryRepository.findDueDeliveries(
                Instant.now(), PageRequest.of(0, 100));
        for (EventHookDelivery d : due) {
            worker.deliverAsync(d.getId());
        }
    }
}
```

(`@EnableScheduling` already exists on `AiGitBotApplication`.) Batching at 100 keeps the sweep cheap; leftover rows are picked next tick. Accept the small risk of double-dispatch when a PENDING row's async attempt is still in flight — guarded by the worker's status re-check; document it in the class javadoc.

**Test:** `EventHookRetrySweeperTest` (Mockito) — due rows are dispatched, none when disabled. Expected: PASS.

### Task 11: Retention GC for webhook deliveries

**Objective:** Keep only the newest `eventhook.retention.keep-last` (default 10) deliveries per endpoint — successful and failed alike; mirrors the `AuditLogGarbageCollector` cron + test-seam pattern (audit/AuditLogGarbageCollector.java).

**Files:**
- Modify: `src/main/java/org/remus/giteabot/eventhook/EventHookDeliveryRepository.java` (from Task 4 — add two queries)
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookDeliveryGarbageCollector.java`
- Test: `src/test/java/org/remus/giteabot/eventhook/EventHookDeliveryGarbageCollectorTest.java`

Repository additions:

```java
/** Newest deliveries for an endpoint regardless of status; use PageRequest.of(0, keep) to find the keep-cutoff. */
List<EventHookDelivery> findByEndpointIdOrderByIdDesc(Long endpointId, Pageable pageable);

/** Deletes terminal rows (SUCCESS/FAILED) older than the keep-cutoff. PENDING/RETRYING are never touched. */
@Modifying
@Query("DELETE FROM EventHookDelivery d WHERE d.endpoint.id = :endpointId " +
       "AND d.status IN ('SUCCESS', 'FAILED') AND d.id < :cutoffId")
int deleteTerminalBefore(@Param("endpointId") Long endpointId, @Param("cutoffId") Long cutoffId);
```

Collector (id-based cutoff avoids any timestamp-tie ambiguity and keeps the delete a single bounded statement):

```java
@Slf4j
@Component
public class EventHookDeliveryGarbageCollector {

    private final EventHookEndpointRepository endpointRepository;
    private final EventHookDeliveryRepository deliveryRepository;
    private final EventHookProperties properties;

    public EventHookDeliveryGarbageCollector(EventHookEndpointRepository endpointRepository,
                                             EventHookDeliveryRepository deliveryRepository,
                                             EventHookProperties properties) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
    }

    /** Cron-fired entry point; default 04:41 server time, tunable via eventhook.retention.gc-cron. */
    @Scheduled(cron = "${eventhook.retention.gc-cron:0 41 4 * * *}")
    public void runGarbageCollection() {
        int deleted = collectOnce();
        if (deleted > 0) {
            log.info("EventHookDeliveryGarbageCollector: pruned {} webhook deliver(ies), keepLast={}",
                    deleted, properties.getRetention().getKeepLast());
        }
    }

    /** Single GC pass over all endpoints; returns rows deleted. Package-private for deterministic tests. */
    @Transactional
    int collectOnce() {
        int keep = Math.max(0, properties.getRetention().getKeepLast());
        int deleted = 0;
        for (EventHookEndpoint endpoint : endpointRepository.findAll()) {
            List<EventHookDelivery> newest = deliveryRepository.findByEndpointIdOrderByIdDesc(
                    endpoint.getId(), PageRequest.of(0, Math.max(keep, 1)));
            if (keep == 0) {
                if (!newest.isEmpty()) {
                    deleted += deliveryRepository.deleteTerminalBefore(
                            endpoint.getId(), newest.getLast().getId() + 1);
                }
            } else if (newest.size() == keep) {
                // Page full: there may be older rows beyond it — delete terminal rows
                // older than the Nth-newest. Short page means <= keep exist; skip.
                deleted += deliveryRepository.deleteTerminalBefore(endpoint.getId(), newest.getLast().getId());
            }
        }
        return deleted;
    }
}
```

Semantics: the `keep`-sized page over **all** statuses yields the id of the Nth-newest delivery; terminal rows (`SUCCESS`, `FAILED`) older than that cutoff are deleted in one bounded statement. When fewer than `keep` deliveries exist the page is short and the delete is skipped. `PENDING`/`RETRYING` rows are never deleted — deleting an in-flight retry would strand the sweeper — but they still *count* toward the newest-N window, which is correct: a delivery currently retrying is part of the recent history an admin wants to see. They are self-limiting (the worker marks them `FAILED` after `retry.max-attempts`), after which the GC prunes them like any other terminal row.

**Tests:** `EventHookDeliveryGarbageCollectorTest` (Spring Data JPA slice test on H2, or repository mocks): (a) 15 terminal deliveries (mixed SUCCESS/FAILED), keep=10 → 5 oldest deleted, newest 10 survive regardless of status; (b) exactly 10 → nothing deleted; (c) keep=0 → all SUCCESS and FAILED deleted; (d) PENDING/RETRYING rows older than the cutoff survive; (e) old RETRYING row counts toward the newest-N window; (f) two endpoints pruned independently. Run: `mvn test -Dtest=EventHookDeliveryGarbageCollectorTest`. Expected: PASS.

### Task 12: Emit PR-workflow events from the orchestrator

**Files:**
- Modify: `src/main/java/org/remus/giteabot/prworkflow/PrWorkflowOrchestrator.java` (inject `EventHookPublisher` via constructor — `@RequiredArgsConstructor` already present)

Three publication points inside `run(...)`:
1. After the existing `PR_WORKFLOW_RUN_STARTED` audit record (≈ line 93): publish `PR_WORKFLOW_STARTED` with `data = {workflowKey, runId, trigger: "webhook"}`.
2. After `runService.complete(...)` and the completion audit record (≈ line 143): publish `PR_WORKFLOW_COMPLETED` with `data = {workflowKey, runId, status, durationMs, summary}`.
3. In the failure path (the `catch` around `workflow.run(context)` — read the tail of the method, lines 161-258, and place it where the run is marked FAILED): publish `PR_WORKFLOW_FAILED` with `data = {workflowKey, runId, error}`.

**Tests:** extend/imitate the existing orchestrator test in `src/test/java/org/remus/giteabot/prworkflow/` — verify publisher called with expected types on the happy and failing paths (mock publisher). Expected: PASS.

### Task 13: Emit `finding.detected` from the agent review

**Files:**
- Modify: `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewWorkflow.java` (extend `DEFAULT_FORMAL_REVIEW_DECISION_PROMPT` with optional `findings` array in the JSON block)
- Modify: `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java` (parse optional `findings[]`; call publisher)

Prompt addition (append to the JSON contract text):

```
Optionally include a "findings" array next to the counts:
{"blocker": 1, "medium": 0, "low": 0,
 "findings": [{"severity": "blocker", "category": "security", "title": "...",
               "file": "src/Foo.java", "line": 42, "cwe": "CWE-798", "owasp": "A07:2021"}]}
Omit unknown optional fields instead of guessing.
```

Parser: lenient Jackson `JsonNode` walk — missing/non-array `findings` → empty list. After classification is parsed and the review outcome is known:
- one `AGENT_REVIEW_FINDING_DETECTED` event per parsed finding (`data.finding = {...}`), or
- if no structured findings but counts > 0: one aggregate event with `data.findingCounts`.

The service is created per-bot by `AgentReviewServiceFactory`; pass `EventHookPublisher` in from the factory (a Spring bean) — check the factory's constructor wiring and mirror how other collaborators are threaded through. Emitting from inside `AgentReviewService` keeps the orchestrator untouched for this event.

**Tests:** extend the existing `AgentReviewServiceTest`: (a) classification with findings → N events with correct severity/category/cwe; (b) counts-only classification → 1 aggregate event; (c) malformed findings array → aggregate fallback, no exception. Expected: PASS.

### Task 14: Emit issue-assignment events from `BotWebhookService`

**Files:**
- Modify: `src/main/java/org/remus/giteabot/admin/BotWebhookService.java` (`handleIssueAssigned`, ≈ line 394)

`BotWebhookService` is a Spring bean, so inject `EventHookPublisher` directly. In `handleIssueAssigned`:
1. After the `isCallerAllowed` check passes, before delegating to `IssueImplementationService.handleIssueAssigned(payload)`: publish `ISSUE_ASSIGNMENT_STARTED` with `data = {issueNumber, issueTitle}` (resolve from `WebhookPayload` — reuse the same extraction the method already does).
2. On successful return of the delegation: publish `ISSUE_ASSIGNMENT_COMPLETED` with `data = {issueNumber, branch}` (branch if exposed by the result; otherwise omit — YAGNI).
3. Wrap the delegation in try/catch (it is `@Async`, so an exception would otherwise vanish into the executor): on exception publish `ISSUE_ASSIGNMENT_FAILED` with `data = {issueNumber, error}`, then rethrow/log as today.

Issue events carry `repository.owner/name` and `issue` in the envelope; `pullRequest` stays null.

**Tests:** extend the existing `BotWebhookService` test: started+completed on success; started+failed on exception; nothing when caller not allowed. Expected: PASS.

### Task 15: Admin UI — controller, templates, navigation

**Objective:** CRUD for endpoints, enable/disable toggle, event-type selection, recent delivery status. Follow the `system-settings` DeploymentTarget pattern exactly.

**Files:**
- Create: `src/main/java/org/remus/giteabot/eventhook/EventHookController.java`
- Create: `src/main/resources/templates/event-hooks/list.html`
- Create: `src/main/resources/templates/event-hooks/form.html`
- Create: `src/main/resources/templates/event-hooks/deliveries.html`
- Modify: the navigation/layout template that links to system-settings sections (find it via `search_files("deployment-targets", path="src/main/resources/templates")` and add the sibling link)

Controller sketch (`@Controller`, `@RequestMapping("/admin/event-hooks")` — copy URL prefix, security expectations, and `Model` attribute style from `DeploymentTargetController`):

- `GET ""` → list: all endpoints with enabled flag, subscribed types, URL; link to deliveries.
- `GET /new`, `GET /{id}/edit` → form: name, URL, checkboxes for every `EventHookEventType` (label = wire value), custom headers textarea (`Key: Value` per line or JSON — pick one and validate), optional scope fields (bot select, repo owner/name), plus the three security-related fields below. All credential inputs follow the ai-integrations form contract exactly (templates/ai-integrations/form.html:44-50): `type="password"`, never pre-filled (stored values are ciphertext anyway), with a "Leave blank to keep current value" hint on edit. The controller passes raw form values to `EventHookEndpointService.save(endpoint, plainSecret, plainAuthorizationHeader)` — encryption happens only there.
  - **Secret** (optional) — HMAC signing key; help text: "Optional. When set, deliveries are signed with X-EventHook-Signature-256."
  - **Authorization header** (optional) — static value, help text with examples: `Bearer token123456`, `Basic YWxhZGRpbjpvcGVuc2VzYW1l`.
  - **Skip TLS certificate verification** (checkbox, default off) — must carry a visible warning label, e.g. "Insecure: disables HTTPS certificate validation. Use only for self-signed/internal endpoints."
- `POST /save` → validate URL is http(s), at least one event type selected; secret and authorization header are optional in all cases (blank on edit = keep current); persist via `EventHookEndpointService.save`.
- `POST /{id}/toggle` → flip `enabled`.
- `POST /{id}/delete` → delete (deliveries cascade).
- `GET /{id}/deliveries` → `findTop50ByEndpointIdOrderByIdDesc`, showing status, attempts, response code, error, timestamps.
- `POST /deliveries/{deliveryId}/retry` → reset a FAILED delivery to PENDING and dispatch (nice-to-have, cheap; include only if it stays under ~15 lines).

Check `SecurityConfig` (org.remus.giteabot.admin.SecurityConfig) for the path rules covering `/admin/**` and confirm the new routes inherit them; add an explicit rule only if the existing pattern doesn't match.

**Tests:** `EventHookControllerTest` (`@WebMvcTest` or the MVC test style used by existing controller tests — check `src/test/java/org/remus/giteabot/admin/`): list renders, save validates (no event types rejected, non-http URL rejected), save succeeds with neither credential set, toggle flips flag, delete removes. Expected: PASS.

### Task 16: Payload schema documentation

**Files:**
- Create: `doc/OUTGOING_WEBHOOKS.md`

Contents: intro, configuration reference (all `eventhook.*` properties + env vars, including the retention policy semantics — newest N deliveries per endpoint survive regardless of status, only in-flight PENDING/RETRYING rows are exempt, `0` = prune all terminal rows), endpoint setup walkthrough (including: secret optional — unsigned deliveries when absent; static Authorization header examples `Bearer token123456` / `Basic YWxhZGRpbjpvcGVuc2VzYW1l`; the `skipTlsVerify` option with a security warning), signature verification with a copy-pasteable verification snippet (Python + Java), the full schema-v1 section from this plan (envelope + per-event `data`), retry/delivery semantics (attempt counts, backoff defaults, 2xx = success), and a versioning policy statement: additive fields within v1; breaking changes → `schemaVersion: 2` while v1 senders remain configurable per endpoint.

Also link the new doc from `doc/DEPLOYMENT.md` or the README's documentation index, wherever the Git-platform setup docs are listed.

### Task 17: End-to-end verification

1. `mvn -q verify` — full suite green, including `ArchitectureTest` (confirms the new package doesn't violate layering).
2. Manual smoke (H2 profile, `mvn spring-boot:run`): start a throwaway receiver — `python3 -m http.server 9000` is enough to see POSTs arrive, or a 10-line `HttpServer` script that verifies the signature — register it as an endpoint subscribed to all types in the admin UI, trigger a review on a test PR (systemtest/gitea setup), and confirm: `prworkflow.started`, `finding.detected`, `prworkflow.completed` arrive signed; kill the receiver, retrigger, watch the deliveries page show RETRYING → FAILED with growing backoff.

---

## Acceptance Criteria Mapping

| Criterion | Covered by |
|---|---|
| Register endpoint with URL, optional secret, optional Authorization header, event types | Tasks 1, 3, 15 |
| Async signed POST on selected events | Tasks 6-9, 12-14 |
| Retries with configurable backoff; failures logged/visible | Tasks 5, 9, 10, 15 (deliveries view) |
| Delivered-webhook retention (keep last N of any status, configurable) | Tasks 5, 11 |
| Documented, versioned payload schema | Task 16 |
| Delivery never blocks the workflow | ADR-2: sync INSERT + `@Async` dispatch; publisher swallows all exceptions (Task 8) |

## Risks / Open Questions

- **Structured findings depend on model compliance.** The `findings[]` extension is best-effort; the aggregate-count fallback keeps the event useful either way (ADR-4). If richer findings become mandatory, that is a prompt-engineering follow-up, not a webhook change.
- **In-flight rows are exempt from retention, and that is safe.** A pathological endpoint could briefly hold more than `keep-last` rows while retries are in flight, but `RETRYING` rows exhaust into `FAILED` after `retry.max-attempts` and crash-stranded `PENDING` rows are re-driven by the sweeper — so the table stays bounded without ever deleting work the sweeper still needs.
- **Secrets at rest.** Endpoint secrets and Authorization header values are encrypted with the existing `EncryptionService` (AES-GCM, `APP_ENCRYPTION_KEY`), same as `AiIntegration#apiKey` — including its caveat: with no key configured, values fall back to plaintext storage (acceptable for dev, warned at startup). The consumer-facing docs (Task 16) must tell operators to set `APP_ENCRYPTION_KEY` in production, same as the AI-integration docs do.
- **Double-dispatch window.** A PENDING row whose async attempt is in flight can be picked up by the sweeper after a slow attempt; the worker's status re-check makes the second attempt a no-op in practice, but at-least-once (not exactly-once) delivery is the contract — document it in `OUTGOING_WEBHOOKS.md`.
- **Outbound SSRF surface.** Admins can point endpoints at arbitrary URLs, and `skipTlsVerify` additionally weakens the channel to those URLs (MITM-able by design). Admin-only configuration plus the prominent UI warning mitigates this; optionally add a `eventhook.allowed-hosts` allowlist and/or forbid `skipTlsVerify` for non-private IP ranges later if the threat model requires it.
- **Bot platform getter.** Task 6 assumes `Bot` exposes the Git platform; verify against `org.remus.giteabot.admin.Bot` before writing `EventHookPayload.of`.
