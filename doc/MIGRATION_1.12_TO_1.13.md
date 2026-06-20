# Migration Guide: AI-Git-Bot 1.12 → 1.13

> **Target audience:** operators upgrading an existing 1.12.x deployment to
> the 1.13.0 release that moves the PR-review diff-chunking parameters from the
> `AiIntegration` entity to workflow-level configuration.

This release moves three diff-chunking parameters
(`maxDiffCharsPerChunk`, `maxDiffChunks`, `retryTruncatedChunkChars`) from the
`ai_integrations` table onto the **workflow configuration** system
introduced in 1.6.0 (§ 3.2 of [`MIGRATION_1.6_TO_1.7.md`](./MIGRATION_1.6_TO_1.7.md)).

**Default behaviour is preserved automatically.** Bots that relied on the
column defaults (120 000 / 8 / 60 000) continue to work exactly as before.
Custom per-integration values are **not migrated** — operators who need them
must re-enter them in the workflow configuration UI after upgrade.

If you are upgrading from 1.0 / 1.1, also read
[`MIGRATION_1.0_TO_1.1.md`](./MIGRATION_1.0_TO_1.1.md) first.

---

## TL;DR — for impatient operators

1. **Drop in the new JAR / image.** No config change is required — the global
   defaults (120 000 / 8 / 60 000) match the old 1.12.x column defaults exactly.
2. **Flyway applies `V28` on first boot.** It drops the three `ai_integrations`
   columns if they still exist. Your data is safe — the columns are simply gone
   after migration.
3. **If you customised these values in 1.12.x,** set them via **System settings
   → Workflow configurations → Workflows → PR Review** (the workflow-ui form)
   for each bot that needs a non-default value. See § 3.1 below.

---

## 1. What changed

| Area | 1.12.x | 1.13.0 |
|---|---|---|
| Diff chunking storage | Columns on `ai_integrations` table | Workflow-level params in `workflow_configurations.params_json` |
| Default values | DB column defaults: 120000 / 8 / 60000 | Spring Boot `review.chunking.*` properties (same values) |
| Per-bot override | Edit the AI Integration row | Edit the PR Review workflow params on the bot's workflow configuration |

The three parameters are:

| Old column | New param key | Default | Description |
|---|---|---|---|
| `max_diff_chars_per_chunk` | `maxDiffCharsPerChunk` | `120000` | Maximum characters per diff chunk before splitting. |
| `max_diff_chunks` | `maxDiffChunks` | `8` | Maximum number of diff chunks to review. |
| `retry_truncated_chunk_chars` | `retryTruncatedChunkChars` | `60000` | When a chunk is too large for the model's context, truncate to this size and retry once. |

---

## 2. Database migration

Flyway migration **`V28`** drops the three columns from `ai_integrations`:

```sql
ALTER TABLE ai_integrations DROP COLUMN IF EXISTS max_diff_chars_per_chunk;
ALTER TABLE ai_integrations DROP COLUMN IF EXISTS max_diff_chunks;
ALTER TABLE ai_integrations DROP COLUMN IF EXISTS retry_truncated_chunk_chars;
```

The values stored in these columns are **not migrated** into
`workflow_configurations.params_json` — they are discarded when V28 runs.
Operators who need custom values must re-enter them in the workflow UI.

**Rollback.** If you roll back the application binary after V28 has
executed, the `ai_integrations` columns will be gone and the 1.12.x
application (which still maps them via JPA) may fail at startup or on
first database access. Restore the columns from a database backup before
rolling back the binary.

---

## 3. Preserving custom values

### 3.1 Check if you have non-default values

In your 1.12.x deployment, query the old columns to see which AI integrations
had custom values:

```sql
-- PostgreSQL
SELECT id, name,
       max_diff_chars_per_chunk,
       max_diff_chunks,
       retry_truncated_chunk_chars
FROM ai_integrations
WHERE NOT (max_diff_chars_per_chunk = 120000
       AND max_diff_chunks = 8
       AND retry_truncated_chunk_chars = 60000);
```

```bash
# H2 (admin console)
SELECT * FROM ai_integrations
WHERE NOT (max_diff_chars_per_chunk = 120000
       AND max_diff_chunks = 8
       AND retry_truncated_chunk_chars = 60000);
```

If every row matches the defaults, **no further action is needed**.

### 3.2 Migrate custom values to workflow configuration

For each AI integration with a non-default value (and every bot that uses it):

1. Open **System settings → Workflow configurations**.
2. Open the configuration assigned to the affected bot(s).
3. In the **Workflows** tab, locate **PR Review** and expand it.
4. Set the custom values in the workflow params form:
   - **Max diff chars per chunk** → your previous `maxDiffCharsPerChunk`
   - **Max diff chunks** → your previous `maxDiffChunks`
   - **Retry truncated chunk chars** → your previous `retryTruncatedChunkChars`
5. Save.

These values are now stored per-workflow-configuration in the
`workflow_configurations.params_json` column and survive across deployments.

### 3.3 Setting values via `application.properties` (global fallback)

If you prefer to set the defaults at the application level rather than in the
UI, use the `review.chunking.*` properties in `application.properties` or as
environment variables:

| Property | Env var | Default |
|---|---|---|
| `review.chunking.max-diff-chars-per-chunk` | `REVIEW_CHUNKING_MAX_DIFF_CHARS_PER_CHUNK` | `120000` |
| `review.chunking.max-diff-chunks` | `REVIEW_CHUNKING_MAX_DIFF_CHUNKS` | `8` |
| `review.chunking.retry-truncated-chunk-chars` | `REVIEW_CHUNKING_RETRY_TRUNCATED_CHUNK_CHARS` | `60000` |

These serve as the **fallback** when a workflow configuration does not specify a
per-workflow value. Workflow config values take precedence.

---

## 4. Environment variable changes

The following environment variables (available since 1.0.0 via the legacy
`AI_*` prefix) are **no longer used** for diff chunking:

- `AI_MAX_DIFF_CHARS_PER_CHUNK`
- `AI_MAX_DIFF_CHUNKS`
- `AI_RETRY_TRUNCATED_CHUNK_CHARS`

These were removed in 1.1.0 when configuration moved to the web UI, repurposed
as `ai_integrations` columns, and are now **fully retired**. The replacement
environment variables are the `REVIEW_CHUNKING_*` ones listed in § 3.3 above.

Existing environment variables referencing the old names are silently ignored by
the application.

---

## 5. Behaviour notes

- **Default behaviour is identical.** The Spring Boot defaults (120000 / 8 / 60000)
  are the same values the database used as column defaults, so bots that relied
  on defaults are unaffected.
- **Workflow config overrides the global defaults.** When a workflow configuration
  specifies values for the PR Review workflow, those values take precedence over
  the `review.chunking.*` application properties.
- **Per-bot granularity.** You can assign different workflow configurations to
  different bots, allowing fine-grained control (e.g., a small context window
  model gets a lower `maxDiffCharsPerChunk`).

---

## 6. See also

- [`MIGRATION_1.6_TO_1.7.md`](./MIGRATION_1.6_TO_1.7.md) — previous migration
  guide (workflow configurations, deployment targets).
- [`MIGRATION_1.0_TO_1.1.md`](./MIGRATION_1.0_TO_1.1.md) — legacy migration
  guide (environment variables → web UI).
- [`PR_WORKFLOWS.md`](./PR_WORKFLOWS.md) — PR workflows overview.
- [`PR_WORKFLOWS_REVIEW.md`](./PR_WORKFLOWS_REVIEW.md) — PR Review workflow
  documentation.
