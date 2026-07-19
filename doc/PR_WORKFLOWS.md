# PR Workflows

A **PR workflow** is an automated task the bot runs on a pull request. Each
workflow solves one recurring engineering chore — reviewing the diff, writing
tests, keeping docs current — so it happens on every PR without anyone
remembering to do it.

This guide is problem-first: find the chore you want to automate, read what the
workflow does, then follow the shared setup steps at the bottom. You do **not**
need to understand the bot's internals to use any of them.

---

## Which problem do you want to solve?

| I want to… | Use the workflow | Key | Default? |
|---|---|---|---|
| Get every PR reviewed with inline feedback | **[PR Review](PR_WORKFLOWS_REVIEW.md)** | `review` | ✅ On |
| Get a deeper review where the bot reads the surrounding code first | **[Agentic PR Review](PR_WORKFLOWS_AGENTIC_REVIEW.md)** | `agentic-review` | Opt-in |
| Automatically add unit tests for the changed code | **[Unit Tests](PR_WORKFLOWS_UNIT_TEST.md)** | `unit-test-author` | Opt-in |
| Generate and run end-to-end browser tests against a live preview | **[Full-Stack QA](PR_WORKFLOWS_E2E.md)** | `e2e-test` | Opt-in |
| Keep the README and docs in sync with the code | **[README Sync](PR_WORKFLOWS_README_SYNC.md)** | `readme-sync` | Opt-in |
| Keep translations in sync across locale files | **[i18n Coverage](PR_WORKFLOWS_I18N_COVERAGE.md)** | `i18n-coverage` | Opt-in |

Every workflow posts its result as a comment on the pull request. Only
**PR Review** is on by default; the rest are opt-in per bot.

---

## Setting up and running a workflow

The setup is the same for every workflow. You do it once per bot in the admin
web UI.

### 1. Choose a workflow configuration

A **workflow configuration** is a reusable, named set of workflows plus their
per-workflow settings. You assign one configuration to a bot; many bots can
share the same configuration.

Go to **System settings → Workflow configurations**. You can:

- use the seeded **`Default`** configuration (runs only **PR Review**),
- clone it and tick the extra workflows you want, or
- create a new configuration from scratch.

On the **Workflows for «name»** page, tick each workflow you want and fill in
its settings. Each workflow renders as a collapsible section with its own
fields; the defaults are sensible, so you can enable a workflow without
changing anything.

### 2. Assign the configuration to a bot

Go to **Bots → New / Edit bot → Workflow Configuration** and pick your
configuration (or leave **(use default)** to inherit `Default`).

<a id="trigger-conditions"></a>
### 3. Decide when it should run

On the bot form, two settings control automatic triggering on a new pull
request:

| Setting | Default | Effect |
|---|---|---|
| Bot is requested as reviewer | Always on | Runs when a developer requests the bot as a reviewer on the PR. |
| **Run workflow when PR is opened** | Off | Runs on every new or reopened PR, no reviewer request needed. |
| **Run workflow when PR head is updated** | Off | Runs configured workflows on every `synchronized` event (new commits pushed to the PR branch). GitHub and Gitea only. |

Turn on **Run workflow when PR is opened** if you want a workflow to run for
every PR unconditionally.

### 4. (Optional) trigger it by hand

Some workflows also respond to a comment command on the PR:

| Command | Workflow |
|---|---|
| `@bot generate-tests` / `@bot rerun-unit-tests` | Unit Tests |
| `@bot regenerate-tests [feedback]` / `@bot rerun-tests` | Full-Stack QA |
| `@bot regenerate-readme [instruction]` | README Sync |
| `@bot regenerate-i18n [instruction]` | i18n Coverage |

The exact commands are documented on each workflow's own page.

---

## What you see while it runs

- The bot reacts to a triggering command with a 👀 and posts a short "starting"
  comment, so you know it picked up the work.
- When finished, it posts a result comment (review findings, a test-run table,
  a documentation-change summary, …).
- If a new push updates the PR while an older run of the same workflow is still
  going, the older run is cancelled so its comments never race against stale
  code.

Run status and steps are also visible in the admin UI where supported, and in
the application logs.

---

## Upgrade behaviour

Existing installations are migrated to a `Default` workflow configuration that
contains only **PR Review**, preserving previous behaviour. Review-category
workflows may be added to `Default` automatically on upgrade; all testing and
documentation workflows are opt-in. Bots with no explicit configuration inherit
`Default`.

---

## Per-workflow guides

- [PR Review](PR_WORKFLOWS_REVIEW.md) — the default one-shot review.
- [Agentic PR Review](PR_WORKFLOWS_AGENTIC_REVIEW.md) — review after the bot
  explores the codebase.
- [Unit Tests](PR_WORKFLOWS_UNIT_TEST.md) — generate and run unit tests.
- [Full-Stack QA](PR_WORKFLOWS_E2E.md) — generate and run E2E browser tests.
- [README Sync](PR_WORKFLOWS_README_SYNC.md) — keep documentation current.

---

## Audit trail

Every workflow run produces a tamper-evident audit trail. The bot records
lifecycle events, tool calls the AI agent makes, step-level progress, and
review decisions in a hash-chained append-only log. You can use this to
reconstruct exactly what happened during a workflow run, verify that no events
were inserted or deleted, and correlate AI interactions with token usage.

### What gets recorded

| Event | Emitted when |
|---|---|
| `PR_WORKFLOW_RUN_STARTED` | A workflow starts for a pull request. |
| `PR_WORKFLOW_STEP_APPENDED` | A workflow records a named step (e.g. `fetch-diff`). |
| `TOOL_CALL_EXECUTED` | The AI agent calls a tool during an agentic workflow. Stores the tool name, arguments (truncated to 2 KB), result excerpt (1 KB), success flag, round number, duration, and token counts. |
| `PR_WORKFLOW_RUN_COMPLETED` | A workflow finishes (SUCCESS, FAILED, or CANCELLED). |
| `REVIEW_COMPLETED` | A review-category workflow posts its review to the PR. |

Every event carries the bot ID, repository, PR number, run ID, and a UTC
timestamp. Tool-call events also include `ai_session_id` and
`ai_usage_session_id` for cross-referencing with the AI usage log and session
tables.

### Hash chain integrity

Events are linked by a SHA-256 hash chain scoped to each workflow run.
The first event in a run has `previous_hash = NULL`; every subsequent event's
hash is computed from the previous event's hash, the event type, timestamp,
and the canonical JSON payload:

```
hash = SHA-256(event_type | timestamp_ms | payload_json | previous_hash)
```

To verify integrity, recompute each event's hash from its predecessor.
A break in the chain means an event was inserted, deleted, or altered.

### Querying audit events

Audit events are stored in the `pr_audit_events` table. From a SQL client:

```sql
-- All events for a specific workflow run
SELECT * FROM pr_audit_events WHERE run_id = 42 ORDER BY id;

-- All events for a bot/repo/PR combination
SELECT * FROM pr_audit_events
WHERE bot_id = 1 AND repo_owner = 'acme' AND repo_name = 'api' AND pr_number = 27
ORDER BY id;

-- All tool calls made during a run
SELECT * FROM pr_audit_events
WHERE run_id = 42 AND event_type = 'TOOL_CALL_EXECUTED'
ORDER BY id;
```

The event's `event_payload_json` column contains type-specific details —
arguments, status, summaries — as a flat JSON object.

### Retention policy

Audit events accumulate over time. A nightly garbage collector removes events
older than the configured retention window. By default, events are kept for
**90 days** and the collector runs at **04:23 UTC**.

Configure these in `application.properties` or via environment variables:

| Property | Env var | Default | Description |
|---|---|---|---|
| `audit.retention` | `AUDIT_RETENTION` | `P90D` | ISO-8601 duration to keep events (e.g. `P180D`, `P365D`). |
| `audit.gc-cron` | `AUDIT_GC_CRON` | `0 23 4 * * *` | Cron expression for the retention cleanup job. |

In Docker Compose, set them under the `app` service's `environment`:

```yaml
environment:
  AUDIT_RETENTION: P180D
  AUDIT_GC_CRON: "0 0 3 * * *"
```

To disable retention entirely (keep events forever), set `audit.retention` to
a very large value such as `P36500D`. There is no off switch — the collector
always runs but won't delete anything if the cutoff never passes.

> **Advanced topics (being reworked).** Full-Stack QA needs a live preview
> environment, and some workflows can call external tools. The setup for
> preview/execution environments, MCP servers, and tool-calling is documented
> separately and is currently being redesigned — it is intentionally left out
> of this workflow guide so the workflows themselves stay easy to understand.
