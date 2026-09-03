# Outgoing Webhooks

AI Git Bot can push signed event notifications to external systems — SIEM,
ticketing, chatOps, custom dashboards — whenever something happens inside the
bot: a PR workflow starts or finishes, the review agent reports findings, or an
issue assignment is picked up. This is the reverse direction of the Git
platform webhooks the bot *receives*; the features are independent.

Highlights:

- **Durable delivery** — every event is persisted before dispatch; retries
  survive restarts.
- **At-least-once delivery** — a delivery may arrive more than once (e.g. when
  a slow attempt races the retry sweeper). Receivers should deduplicate on the
  `id` field / `X-EventHook-Delivery` header.
- **Optional HMAC-SHA256 signing** per endpoint (GitHub-style).
- **Optional static Authorization header** per endpoint.
- **Scoped subscriptions** — global, per-bot, or per-repository endpoints.
- **Admin UI** under *System settings → Outgoing webhooks*
  (`/admin/event-hooks`) with endpoint CRUD and a delivery log with retry.

---

## Configuration reference

All properties live under the `eventhook` prefix and are env-overridable
(Spring relaxed binding, durations accept `5s` / `30m` / `1h` style values).

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `eventhook.enabled` | `EVENTHOOK_ENABLED` | `true` | Master switch. When `false`, no events are recorded or dispatched. |
| `eventhook.connect-timeout` | `EVENTHOOK_CONNECT_TIMEOUT` | `5s` | HTTP connect timeout for deliveries. |
| `eventhook.read-timeout` | `EVENTHOOK_READ_TIMEOUT` | `10s` | HTTP read timeout for deliveries. |
| `eventhook.sweeper-interval` | `EVENTHOOK_SWEEPER_INTERVAL` | `30s` | How often the retry sweeper picks up due `RETRYING` rows and stranded `PENDING` rows (crash recovery). |
| `eventhook.max-payload-bytes` | `EVENTHOOK_MAX_PAYLOAD_BYTES` | `65536` | Serialized payloads larger than this are rejected (never stored). |
| `eventhook.retry.max-attempts` | `EVENTHOOK_RETRY_MAX_ATTEMPTS` | `5` | Total attempts per delivery before it is marked `FAILED`. |
| `eventhook.retry.initial-backoff` | `EVENTHOOK_RETRY_INITIAL_BACKOFF` | `30s` | Delay before the first retry. |
| `eventhook.retry.backoff-multiplier` | `EVENTHOOK_RETRY_BACKOFF_MULTIPLIER` | `2.0` | Backoff growth per attempt. |
| `eventhook.retry.max-backoff` | `EVENTHOOK_RETRY_MAX_BACKOFF` | `30m` | Backoff cap. |
| `eventhook.retention.keep-last` | `EVENTHOOK_RETENTION_KEEP_LAST` | `10` | Newest deliveries kept **per endpoint**. |
| `eventhook.retention.gc-cron` | `EVENTHOOK_RETENTION_GC_CRON` | `0 41 4 * * *` | Cron (server time) for the retention garbage collector. |

### Retention policy semantics

The garbage collector keeps the newest `keep-last` delivery rows per endpoint,
**regardless of status** — successful and failed deliveries age out alike (a
policy that only prunes `SUCCESS` rows would let `FAILED` rows grow forever).
The keep-window counts rows of *every* status, but only terminal rows
(`SUCCESS`, `FAILED`) are ever deleted: in-flight `PENDING` / `RETRYING` rows
older than the cutoff survive so the retry machinery never loses track of
them. `keep-last: 0` prunes all terminal rows after each GC run.

### Credential storage

When `APP_ENCRYPTION_KEY` is configured, endpoint secrets and Authorization
header values are encrypted at rest with the same AES-GCM `EncryptionService`
used for AI provider API keys. **Set `APP_ENCRYPTION_KEY` in production**;
without it, values fall back to plaintext storage (acceptable for development,
warned about at startup).

---

## Setting up an endpoint

<img src="screenshots/webhooks/outgoing-webhooks-overview.png" alt="Overview of all created outgoing webhooks" width="600"/>

*System settings → Outgoing webhooks → Add endpoint* (`/admin/event-hooks/new`):

1. **Name** — free-form label.
2. **URL** — must start with `http://` or `https://`. Every subscribed event
   is POSTed here as JSON; **any 2xx response counts as success**, anything
   else (including timeouts and connection errors) triggers a retry.
3. **Event types** — check at least one (see the event list below).
4. **Custom headers** — optional JSON object sent with every delivery, e.g.
   `{ "X-Source": "ai-git-bot" }`.
5. **Scope** — optionally restrict the endpoint to one bot and/or one
   repository (`owner`, `owner/name`). Empty scope = global.
6. **Secret** (optional) — when set, every delivery is signed (see below).
   When absent, deliveries are sent unsigned.
7. **Authorization header** (optional) — static value sent as the
   `Authorization` header, e.g. `Bearer token123456` or
   `Basic YWxhZGRpbjpvcGVuc2VzYW1l`. Overrides a custom `Authorization`
   header from step 4 when both are set.
8. **Skip TLS certificate verification** (checkbox, default off) — for
   self-signed/internal PKI targets. **Insecure:** this disables HTTPS
   certificate validation and makes the channel MITM-able by design. Use only
   for endpoints you control on a trusted network.

Both credentials are optional and independent — unsigned+unauthenticated,
signed-only, auth-only, and signed+authenticated are all valid configurations.
On edit, leaving a credential field blank keeps the current stored value.

### Security notes

- Endpoint configuration is admin-only (the web UI requires authentication).
- Admins can point endpoints at arbitrary URLs (outbound SSRF surface) —
  combine with network-level egress rules where the threat model requires it.
- Deliveries never block or fail the triggering workflow: publishing is
  synchronous INSERT + async dispatch, and all publisher failures are
  swallowed (logged) by design.

---

## Delivery semantics

1. An event occurs → one delivery row per matching endpoint is inserted
   (`PENDING`) and dispatched asynchronously.
2. The worker POSTs the payload with the headers below. 2xx → `SUCCESS`.
3. On any failure: `attempts` is incremented; if attempts remain, the row goes
   `RETRYING` with `next_attempt_at = now + backoff`, else `FAILED`.
4. Default backoff (5 attempts, 30s initial, ×2, capped at 30m): retries after
   **30s, 60s, 120s, 240s**.
5. A scheduled sweeper (every 30s by default) re-drives due `RETRYING` rows
   and `PENDING` rows stranded by a crash — at-least-once delivery.
6. `FAILED` deliveries can be re-queued manually from the deliveries page
   (*Retry* button).

<img src="screenshots/webhooks/deliveries-overview.png" alt="Overview of all webhook-deliveries" width="600"/>

### Request headers

| Header | Always? | Content |
|---|---|---|
| `Content-Type: application/json` | yes | Payload body (schema below). |
| `X-EventHook-Event` | yes | Event type wire value, e.g. `prworkflow.completed`. |
| `X-EventHook-Delivery` | yes | Delivery UUID — same value as the payload's `id`. Use it for deduplication. |
| `X-EventHook-Signature-256` | only when a secret is set | `sha256=<hex>` — HMAC-SHA256 of the **raw request body** with the endpoint secret. |
| `Authorization` | only when configured | Static value from the endpoint configuration. |
| *(custom headers)* | only when configured | From the endpoint's custom-headers JSON. |

---

## Verifying the signature

Compute HMAC-SHA256 over the **raw request body bytes** with the endpoint
secret, hex-encode, and compare (constant-time) against the value after the
`sha256=` prefix.

**Python:**

```python
import hashlib
import hmac

def verify_signature(raw_body: bytes, secret: str, signature_header: str) -> bool:
    expected = "sha256=" + hmac.new(
        secret.encode("utf-8"), raw_body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature_header or "")

# Flask-style usage:
# verify_signature(request.get_data(), "my-secret", request.headers.get("X-EventHook-Signature-256"))
```

**Java:**

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

static boolean verifySignature(byte[] rawBody, String secret, String signatureHeader) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
    return signatureHeader != null
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                     signatureHeader.getBytes(StandardCharsets.UTF_8));
}
```

---

## Payload schema (version 1)

Every delivery carries the same JSON envelope:

```json
{
  "schemaVersion": 1,
  "id": "8f3d2c1e-…",
  "eventType": "prworkflow.completed",
  "timestamp": "2026-07-25T12:34:56.789Z",
  "actor": { "type": "BOT", "id": "review-bot" },
  "integration": { "botId": 3, "botName": "review-bot", "platform": "gitea" },
  "repository": { "owner": "acme", "name": "payments-service", "pullRequest": 42, "issue": null },
  "data": { }
}
```

| Field | Meaning |
|---|---|
| `schemaVersion` | Envelope schema version — always `1` today. |
| `id` | Delivery UUID; identical to `X-EventHook-Delivery`. Deduplicate on this. |
| `eventType` | One of the wire values below. |
| `timestamp` | When the event was recorded (UTC). |
| `actor.type` / `actor.id` | Always `BOT` / the bot's name. |
| `integration` | Bot id, bot name, lowercase Git platform (`gitea`, `github`, …). |
| `repository` | Repo coordinates; `pullRequest` or `issue` set depending on the event, the other `null`. |
| `data` | Event-specific payload (below). |

### Event types and `data` payloads

**`prworkflow.started`** — a PR workflow run started.

```json
{ "workflowKey": "review", "runId": 9182, "trigger": "webhook" }
```

**`prworkflow.completed`** — a run finished (check `status`).

```json
{ "workflowKey": "review", "runId": 9182, "status": "SUCCESS", "durationMs": 41230, "summary": "…" }
```

`status` is one of `SUCCESS`, `FAILED`, `CANCELLED` (effective terminal
status); `summary` is present only when the workflow produced one.

<img src="screenshots/webhooks/delivery-details-prworkflow-finished.png" alt="Payload of a prworkflow.completed webhook" width="600"/>

**`prworkflow.failed`** — a run aborted with an error.

```json
{ "workflowKey": "review", "runId": 9182, "error": "AI call failed: …" }
```

**`prworkflow.agentreview.finding.detected`** — the review agent's formal
classification reported findings. Two shapes: one event **per structured
finding** when the model returned them —

```json
{ "finding": { "severity": "blocker", "category": "security", "title": "SQL injection",
               "file": "src/Foo.java", "line": 42, "cwe": "CWE-89", "owasp": "A03:2021" } }
```

(all `finding` fields optional and omitted when unknown) — or a **single
aggregate event** with the severity counts when no structured findings were
parsed:

```json
{ "findingCounts": { "blocker": 1, "medium": 0, "low": 2 } }
```

<img src="screenshots/webhooks/delivery-details-finding.png" alt="Payload of a Review-finding" width="600"/>

**`issueassignment.started`** — the bot picked up an issue assignment.

```json
{ "issueNumber": 12, "issueTitle": "Vague issue" }
```

**`issueassignment.completed`** — issue-assignment handling finished.

```json
{ "issueNumber": 12 }
```

**`issueassignment.failed`** — issue-assignment handling failed.

```json
{ "issueNumber": 12, "error": "…" }
```

### Versioning policy

Additive changes (new `data` fields, new event types) ship **within schema
v1** — receivers must tolerate unknown fields. Breaking changes (renamed or
re-typed fields, changed semantics) bump `schemaVersion` to `2`; v1 payloads
remain available and the schema version stays selectable per endpoint.
