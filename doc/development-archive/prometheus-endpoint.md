# Prometheus Metrics Endpoint — Implementation Plan

> **Goal:** Expose application metrics for Prometheus via Spring Boot Actuator at `/actuator/prometheus`, building on the existing Micrometer instrumentation and adding missing meters for reviews, findings, workflow runs, execution times, LLM/tool usage, and errors.

**Tech Stack:** Spring Boot 4.0.5, Java 21, Micrometer, `micrometer-registry-prometheus`, Flyway (no schema changes required), JPA/Hibernate.

---

## ADR-1: Prometheus Registry & Metric Strategy

**Status:** Proposed

**Context**
Spring Actuator is already enabled but only exposes `health` and `info`. A Prometheus-scrapable endpoint is needed. Some Micrometer meters already exist (`PrWorkflowMetrics`, `AgentMetrics`) but are not exposed because the Prometheus registry is not on the classpath. Other requested metrics (review/finding counts, token usage, errors) only exist as persisted audit rows today.

**Options Considered**

1. **Custom `@RestController` writing Prometheus text format**  
   - ✅ Pros: Full control over output; no extra dependency.  
   - ❌ Cons: Reinvents Spring Boot Actuator integration; must manually handle exposition format, content negotiation, and caching.

2. **Add `micrometer-registry-prometheus` and rely on the standard Actuator endpoint**  
   - ✅ Pros: Standard Spring Boot mechanism; automatic `/actuator/prometheus`; integrates with existing `MeterRegistry` beans; supports disabling via `management.endpoint.prometheus.enabled`.  
   - ❌ Cons: Requires adding a new direct dependency and updating the Maven enforcer whitelist.

**Decision**
Choose **Option 2**. Add `io.micrometer:micrometer-registry-prometheus` and expose the standard endpoint.

**Consequences**
- A new direct dependency must be whitelisted in `pom.xml` (critical finding for review).
- All existing and new Micrometer meters are automatically scraped.
- Operators can disable the endpoint with `PROMETHEUS_ENABLED=false`.

---

## ADR-2: Live Counters vs. Database-Derived Gauges

**Status:** Proposed

**Context**
The issue asks for review/finding counts, token usage, and error metrics. Some of this data lives only in audit tables (`pr_audit_events`, `ai_usage_log`, `ai_error_log`). The issue notes that live instrumentation is preferred for Prometheus compatibility, but also asks for gauges/derived counts from persisted data for reviews/findings.

**Options Considered**

1. **Live counters everywhere**  
   - ✅ Pros: Real-time, monotonic, Prometheus-idiomatic.  
   - ❌ Cons: Misses historical data already in the DB; restarting the process resets counters.

2. **Database-derived gauges for counts/totals; live counters/timers for runtime events**  
   - ✅ Pros: Reflects all persisted data; survives restarts; avoids high-cardinality runtime tags.  
   - ❌ Cons: Gauges can appear to move backwards if old rows are deleted (e.g., audit retention GC).

**Decision**
Use a **hybrid** approach:
- **Gauges derived from persisted data** for review/finding counts, total token usage, and total AI errors.
- **Live counters/timers** for workflow runs/durations and agent tool-call rounds/latencies (already implemented).
- Add a **live counter** for individual tool-call invocations (missing today).

**Consequences**
- New gauge components bind to existing repositories; they are read-only and never block the application path.
- Audit retention deletes old events, so gauge values can decrease over time. This is acceptable for operational visibility.

---

## ADR-3: Avoiding High-Cardinality Tags

**Status:** Proposed

**Context**
Prometheus metrics with high-cardinality labels (repository, PR number, session ID, etc.) explode memory/storage usage.

**Decision**
All metrics must avoid high-cardinality tags. Allowed low-cardinality tags:
- `workflow` — workflow key (`review`, `agentic-review`, `e2e-test`, …).
- `status` — terminal status (`success`, `failed`, `cancelled`, `waiting_deploy`).
- `provider` — AI provider/client implementation class name, normalised.
- `mode` — tool-calling mode (`native`, `legacy`).
- `integration` — configured AI integration name.
- `outcome` — critic outcome (`approve`, `iterate`, `abort`, `skipped`).

Forbidden tags: repository owner/name, PR number, issue number, session ID, branch name, raw error message, correlation ID.

---

## Current State / Assumptions

- `spring-boot-starter-actuator` is present; `management.endpoints.web.exposure.include=health,info`.
- `micrometer-core` is declared explicitly but **no** `micrometer-registry-prometheus` dependency exists.
- Existing live Micrometer meters:
  - `PrWorkflowMetrics`: `prworkflow.run_total{workflow,status}`, `prworkflow.run_duration_seconds{workflow}`.
  - `AgentMetrics`: `agent.tool_call.mode_total{mode,provider}`, `agent.tool_call.parse_failures_total{provider}`, `agent.tool_call.latency_seconds{mode,provider}`, `agent.critic.outcome_total{outcome}`.
- Persisted data sources:
  - `pr_audit_events` — workflow lifecycle, `TOOL_CALL_EXECUTED`, `REVIEW_COMPLETED`.
  - `ai_usage_log` — token usage per `ai_integration_name`.
  - `ai_error_log` — failed AI interactions per `ai_integration_name`.
- No persisted "finding" entity exists. A review comment may contain multiple findings, but the count is not stored today.
- Actuator endpoints are permit-all via `SecurityConfig` (`/actuator/**`).

---

## Metric Inventory

| Metric Name | Type | Source | Tags | Notes |
|---|---|---|---|---|
| `prworkflow.run_total` | Counter (live) | `PrWorkflowMetrics` | `workflow`, `status` | Already implemented; will be exposed once Prometheus registry is added. |
| `prworkflow.run_duration_seconds` | Timer (live) | `PrWorkflowMetrics` | `workflow` | Already implemented. |
| `agent.tool_call.mode_total` | Counter (live) | `AgentMetrics` | `mode`, `provider` | Already implemented; counts AI rounds. |
| `agent.tool_call.latency_seconds` | Timer (live) | `AgentMetrics` | `mode`, `provider` | Already implemented. |
| `agent.tool_call.parse_failures_total` | Counter (live) | `AgentMetrics` | `provider` | Already implemented. |
| `agent.critic.outcome_total` | Counter (live) | `AgentMetrics` | `outcome` | Already implemented. |
| `agent.tool_calls_total` | Counter (live) | `AgentMetrics` | `provider` | **New.** Counts individual tool-call invocations (not rounds). |
| `giteabot_reviews_total` | Gauge (DB) | `pr_audit_events` (`REVIEW_COMPLETED`) | none | **New.** Total reviews completed. |
| `giteabot_findings_total` | Gauge (DB) | `pr_audit_events` (`FINDING_POSTED`) | none | **New.** Total findings posted. Requires emitting `FINDING_POSTED` events (see below). |
| `giteabot_ai_usage_input_tokens_total` | Gauge (DB) | `ai_usage_log` | `integration` | **New.** Sum of input tokens per AI integration. |
| `giteabot_ai_usage_output_tokens_total` | Gauge (DB) | `ai_usage_log` | `integration` | **New.** Sum of output tokens per AI integration. |
| `giteabot_ai_errors_total` | Gauge (DB) | `ai_error_log` | none | **New.** Total AI errors; not split by domain. |
| `giteabot_audit_tool_calls_total` | Gauge (DB) | `pr_audit_events` (`TOOL_CALL_EXECUTED`) | none | **New.** Total individual tool calls from audit trail (cross-check with `agent.tool_calls_total`). |

---

## Design

### 1. Dependency

Add to `pom.xml`:

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

And add `io.micrometer:micrometer-registry-prometheus` to the Maven enforcer `bannedDependencies` includes list. This is a **critical finding** for code review because the POM explicitly flags any dependency change as critical.

### 2. Actuator Configuration

Update all three property files:

```properties
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.prometheus.enabled=${PROMETHEUS_ENABLED:true}
```

Files:
- `src/main/resources/application.properties`
- `src/main/resources/application-docker.properties`
- `src/test/resources/application-test.properties`

Docker environment variable: `PROMETHEUS_ENABLED` (default `true`). Setting it to `false` disables the endpoint while keeping `health` and `info` exposed.

### 3. New Gauge Registrar

Create `org.remus.giteabot.metrics.PrometheusMetricsRegistrar`:

- Constructor-inject `MeterRegistry` and the required repositories (`PrAuditEventRepository`, `AiUsageLogRepository`, `AiErrorLogRepository`).
- Use `@PostConstruct` to register `Gauge` meters.
- Each gauge supplier calls a repository query (e.g., `countByEventType`, `sumInputTokensByIntegration`, `countErrors`).
- Keep query logic in the registrar or delegate to a small package-private helper; do not pollute `AiUsageService` or `PrAuditEventService` with Micrometer code.

Example gauge registration:

```java
Gauge.builder("giteabot_reviews_total", auditRepository, r -> r.countByEventType(REVIEW_COMPLETED))
        .description("Total completed reviews")
        .register(meterRegistry);
```

For per-integration token gauges, register one gauge per integration dynamically, or use a single gauge with an `integration` tag and iterate over integration names. Since integration names are low-cardinality and admin-controlled, iterating is acceptable.

### 4. Finding Persistence

To derive `giteabot_findings_total` from persisted data, introduce a new audit event type:

- Add `FINDING_POSTED` to `AuditEventType` (marked as implemented).
- Emit the event when a review comment is posted:
  - `AgentReviewService`
  - `CodeReviewService`
- Payload: include `workflow_key` and, if available, a count of findings. If counting individual findings is not reliable, emit one event per posted review comment and document that one event equals one review output.

If the team prefers not to add finding events in this iteration, document the gap and expose only `giteabot_reviews_total`. The implementation notes should mention this decision explicitly.

### 5. Tool-Call Counter

Add to `AgentMetrics`:

```java
public void recordToolCall(String provider, boolean success) { ... }
```

Expose through `AgentMetricsHolder` and invoke it in `AgentLoop` when the audit tool-call consumer is invoked (one increment per individual tool call).

Metric: `agent.tool_calls_total{provider}`.

### 6. Security & Sensitivity

No code changes needed. `/actuator/prometheus` is already covered by the permit-all Actuator matcher. Verify that no gauge supplier exposes sensitive data (repository names, PR numbers, stack traces, API keys). All DB-derived gauges must aggregate or count only.

---

## Implementation Sequence

1. **Dependency**
   - Add `micrometer-registry-prometheus` to `pom.xml`.
   - Update Maven enforcer whitelist.

2. **Configuration**
   - Add `prometheus` to Actuator exposure in `application.properties`, `application-docker.properties`, and `application-test.properties`.
   - Add `management.endpoint.prometheus.enabled=${PROMETHEUS_ENABLED:true}`.
   - Document `PROMETHEUS_ENABLED` in Docker/deployment docs.

3. **Findings Audit Events (optional but recommended)**
   - Add `FINDING_POSTED` to `AuditEventType`.
   - Emit `FINDING_POSTED` from review posting sites.
   - Add a repository query `countByEventType(AuditEventType)` to `PrAuditEventRepository` if it does not already exist.

4. **New DB-Derived Gauges**
   - Create `PrometheusMetricsRegistrar`.
   - Register gauges for reviews, findings, token usage (per integration), AI errors, and audit tool calls.

5. **New Live Tool-Call Counter**
   - Add `recordToolCall(provider, success)` to `AgentMetrics` and `AgentMetricsHolder`.
   - Wire the counter into `AgentLoop`.
   - Update `AgentMetricsTest`.

6. **Verification**
   - Add `PrometheusEndpointIntegrationTest` verifying `/actuator/prometheus` returns Prometheus text format and contains expected metrics.
   - Add `PrometheusMetricsRegistrarTest` verifying gauges bind to repository values.
   - Run `./mvnw verify` (or `mvn verify`).

7. **Documentation**
   - Update `doc/DEPLOYMENT.md` with Prometheus scrape instructions and the `PROMETHEUS_ENABLED` toggle.
   - Update `doc/LOCAL_DEVELOPMENT.md` to list `/actuator/prometheus`.
   - Update `doc/DOCKERHUB_README.md` accordingly.

---

## Components to Create / Modify

- [ ] `pom.xml` — add `micrometer-registry-prometheus` dependency and enforcer whitelist entry.
- [ ] `src/main/resources/application.properties` — expose `prometheus`; add toggle.
- [ ] `src/main/resources/application-docker.properties` — same.
- [ ] `src/test/resources/application-test.properties` — expose `prometheus` for tests.
- [ ] `src/main/java/org/remus/giteabot/metrics/PrometheusMetricsRegistrar.java` — register DB-derived gauges.
- [ ] `src/main/java/org/remus/giteabot/audit/AuditEventType.java` — add `FINDING_POSTED` (optional).
- [ ] `src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java` — emit `FINDING_POSTED` (optional).
- [ ] `src/main/java/org/remus/giteabot/review/CodeReviewService.java` — emit `FINDING_POSTED` (optional).
- [ ] `src/main/java/org/remus/giteabot/agent/shared/AgentMetrics.java` — add `recordToolCall`.
- [ ] `src/main/java/org/remus/giteabot/agent/shared/AgentMetricsHolder.java` — add `recordToolCall` helper.
- [ ] `src/main/java/org/remus/giteabot/agent/loop/AgentLoop.java` — invoke tool-call counter.
- [ ] `src/test/java/org/remus/giteabot/agent/shared/AgentMetricsTest.java` — add tool-call test.
- [ ] `src/test/java/org/remus/giteabot/metrics/PrometheusEndpointIntegrationTest.java` — new integration test.
- [ ] `src/test/java/org/remus/giteabot/metrics/PrometheusMetricsRegistrarTest.java` — new unit test.
- [ ] `doc/DEPLOYMENT.md`, `doc/LOCAL_DEVELOPMENT.md`, `doc/DOCKERHUB_README.md` — documentation updates.

---

## Configuration Reference

| Property | Default | Env Var | Description |
|---|---|---|---|
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | — | Actuator endpoints exposed over HTTP. |
| `management.endpoint.prometheus.enabled` | `true` | `PROMETHEUS_ENABLED` | Set to `false` to disable the Prometheus scrape endpoint. |

Example Docker Compose override:

```yaml
environment:
  - PROMETHEUS_ENABLED=false
```

---

## Test Considerations

- **Endpoint integration test:** `@SpringBootTest` with `TestRestTemplate` or `MockMvc`, request `/actuator/prometheus`, assert `200 OK`, content type `text/plain`, and body contains at least `prworkflow_run_total`, `agent_tool_call_mode_total`, and `giteabot_reviews_total`.
- **Gauge registrar test:** Use `SimpleMeterRegistry` and mock repositories; assert each gauge reports the mocked value.
- **Tool-call counter test:** Extend `AgentMetricsTest` to verify `agent.tool_calls_total` increments per provider.
- **Security test:** Verify no high-cardinality tags (repo, PR, session) appear in scraped output.

---

## Documentation Updates

- `doc/LOCAL_DEVELOPMENT.md`: add `/actuator/prometheus` to the endpoint table.
- `doc/DEPLOYMENT.md`: add a "Prometheus scraping" subsection explaining the endpoint URL, the `PROMETHEUS_ENABLED` toggle, and example Prometheus job config.
- `doc/DOCKERHUB_README.md`: list `PROMETHEUS_ENABLED` in the environment-variable table.

---

## Gaps / Future Work

- **Finding granularity:** Individual findings are not currently persisted. `giteabot_findings_total` will initially equal the number of posted review comments (one `FINDING_POSTED` event per review). True per-finding counts require structured review output or parsing the posted comment.
- **Error domain split:** The first iteration exposes a single `giteabot_ai_errors_total` gauge. Per-domain error counters can be added later once error taxonomy is stable.
- **Custom business metrics:** Metrics such as " queued webhooks" or "active workflow runs" can be added as gauges once the corresponding runtime state is accessible.

---

## Review Checklist

- [ ] No manual getters/setters; Lombok used for new classes.
- [ ] Constructor injection only; no field `@Autowired`.
- [ ] Prometheus dependency added and enforcer whitelist updated.
- [ ] All three `application*.properties` files include `prometheus` in exposure.
- [ ] No high-cardinality tags in any metric.
- [ ] DB-derived gauge suppliers are read-only and never throw.
- [ ] Tests verify endpoint availability and metric presence.
- [ ] Documentation updated with endpoint URL and toggle.
