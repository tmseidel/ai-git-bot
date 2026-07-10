# Migration Guide: AI-Git-Bot 1.14 → 1.15

> **Target audience:** operators upgrading an existing 1.14.x deployment to
> the 1.15.0 release, which introduces compact diff handling for the agentic
> PR review workflow to reduce token usage and prevent context overflow.

This release introduces a new agent-invokable tool — `pr-diff` — that extracts
per-file diff hunks from a parsed diff summary. The agentic PR review workflow
now uses a compact file-list summary instead of embedding the full diff, and
calls this tool on demand to inspect specific file changes.

If you are upgrading from 1.13, also read
[`MIGRATION_1.13_TO_1.14.md`](./MIGRATION_1.13_TO_1.14.md) first.

---

## TL;DR — for impatient operators

1. **Replace the Docker image.** No runtime dependency changes are needed.
2. **Flyway applies `V30` on first boot.** It automatically enables the
   `pr-diff` tool for all bots that have the `agentic-review` workflow
   enabled in their workflow configuration.
3. **No manual action required.** The migration is selective and only
   affects bots using agentic review. Other tool configurations are not
   modified.

---

## 1. What changed

| Area | 1.14.x | 1.15.0 |
|---|---|---|
| Agentic review behavior | Embeds full diff (up to 60K chars) in initial prompt | Uses compact file-list summary (~100-1000 chars), calls `pr-diff` tool on demand |
| Context tools available | `rg`, `find`, `cat`, `git-log`, `git-blame`, `tree`, `branch-switcher`, `ctags-signatures`, `ctags-deps` | Same as 1.14 + `pr-diff` |
| Tool auto-enablement | V29 added ctags tools to default config | V30 adds `pr-diff` only to tool configs used by bots with agentic-review workflow |
| Token usage | High (full diff in prompt) | Low (compact summary + on-demand file inspection) |
| Application config | No new properties | No new properties required |

### New tool

| Tool | Kind | Roles | Description |
|---|---|---|---|
| `pr-diff` | CONTEXT | CODING, WRITER | Extract diff hunks for a specific file from the parsed PR diff summary. Used by the agentic review workflow to inspect changes on demand. |

The tool is **silent** (output is never posted as a public comment) and
requires a single argument: the repository-relative file path.

---

## 2. Why this migration is selective

Unlike V29 which added `ctags-signatures` and `ctags-deps` to the **default**
tool configuration (making them available to all bots), V30 takes a different
approach:

**The `pr-diff` tool is only added to tool configurations that are actively
used by bots with the `agentic-review` workflow enabled.**

This design decision reflects the tool's purpose:
- It is specifically designed for the agentic review workflow
- It requires a parsed diff summary in the agent context (set by the workflow)
- Bots without agentic review would never have the context needed to use it
- Avoids cluttering tool configurations that don't need it

---

## 3. Automatic migration behavior

### 3.1 How V30 works

The migration executes this query:

```sql
INSERT INTO bot_tool_selections (configuration_id, tool_name, tool_kind)
SELECT DISTINCT btc.id, 'pr-diff', 'CONTEXT'
FROM bot_tool_configurations btc
INNER JOIN bots b ON b.bot_tool_configuration_id = btc.id
INNER JOIN workflow_configurations wc ON wc.id = b.workflow_configuration_id
INNER JOIN workflow_selections ws ON ws.workflow_configuration_id = wc.id
WHERE ws.workflow_key = 'agentic-review'
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = btc.id AND s.tool_name = 'pr-diff'
  );
```

**What it does:**
1. Finds all `BotToolConfiguration` entities referenced by bots
2. Joins to their `WorkflowConfiguration`
3. Checks if any `WorkflowSelection` has `workflow_key = 'agentic-review'`
4. Inserts `pr-diff` into the tool selections (if not already present)

**What it doesn't do:**
- Does NOT add `pr-diff` to the default tool configuration
- Does NOT add `pr-diff` to custom configs used by bots without agentic review
- Does NOT modify any existing tool selections

### 3.2 Verification

After upgrade, you can verify the migration worked by checking which tool
configurations now include `pr-diff`:

```sql
-- PostgreSQL
SELECT btc.name AS tool_config,
       COUNT(DISTINCT b.id) AS bots_using_agentic_review,
       COUNT(s.tool_name) FILTER (WHERE s.tool_name = 'pr-diff') AS has_pr_diff
FROM bot_tool_configurations btc
INNER JOIN bots b ON b.bot_tool_configuration_id = btc.id
INNER JOIN workflow_configurations wc ON wc.id = b.workflow_configuration_id
INNER JOIN workflow_selections ws ON ws.workflow_configuration_id = wc.id
LEFT JOIN bot_tool_selections s ON s.configuration_id = btc.id AND s.tool_name = 'pr-diff'
WHERE ws.workflow_key = 'agentic-review'
GROUP BY btc.id, btc.name
ORDER BY btc.name;
```

```sql
-- H2
SELECT btc.name AS tool_config,
       COUNT(DISTINCT b.id) AS bots_using_agentic_review,
       COUNT(CASE WHEN s.tool_name = 'pr-diff' THEN 1 END) AS has_pr_diff
FROM bot_tool_configurations btc
INNER JOIN bots b ON b.bot_tool_configuration_id = btc.id
INNER JOIN workflow_configurations wc ON wc.id = b.workflow_configuration_id
INNER JOIN workflow_selections ws ON ws.workflow_configuration_id = wc.id
LEFT JOIN bot_tool_selections s ON s.configuration_id = btc.id AND s.tool_name = 'pr-diff'
WHERE ws.workflow_key = 'agentic-review'
GROUP BY btc.id, btc.name
ORDER BY btc.name;
```

All configurations with `bots_using_agentic_review > 0` should have
`has_pr_diff > 0`.

---

## 4. Manual intervention (if needed)

### 4.1 When manual action is required

Manual intervention is only needed if:
- You enabled `agentic-review` for a bot **after** V30 ran (i.e., you
  modified a workflow configuration post-upgrade)
- The bot's tool configuration doesn't already have `pr-diff`

### 4.2 How to enable manually

1. Open **System settings → Tool configurations**
2. Select the tool configuration used by your bot
3. In the **CONTEXT tools** section, check the box for `pr-diff`
4. Save

The tool becomes available immediately (no restart required).

### 4.3 Finding affected configurations

Run this query to find tool configurations that should have `pr-diff` but don't:

```sql
-- PostgreSQL
SELECT DISTINCT btc.name AS tool_config
FROM bot_tool_configurations btc
INNER JOIN bots b ON b.bot_tool_configuration_id = btc.id
INNER JOIN workflow_configurations wc ON wc.id = b.workflow_configuration_id
INNER JOIN workflow_selections ws ON ws.workflow_configuration_id = wc.id
WHERE ws.workflow_key = 'agentic-review'
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = btc.id AND s.tool_name = 'pr-diff'
  );
```

If this query returns results, enable `pr-diff` for those configurations
manually using the steps in § 4.2.

---

## 5. Behavior changes in agentic review

### 5.1 Before (1.14.x)

The agentic review workflow embedded the full unified diff (up to 60,000
characters) in the initial prompt:

```
Unified diff:
```diff
diff --git a/file1.java b/file1.java
... (thousands of lines)
```
```

**Problems:**
- Large PRs could exceed the context window
- Token costs were high even for simple reviews
- The agent had to process irrelevant changes

### 5.2 After (1.15.0)

The workflow now:
1. Parses the diff into a compact file-list summary (~100-1000 chars)
2. Shows only: file paths, change counts, and total stats
3. Provides the `pr-diff` tool for on-demand inspection

**Example initial prompt:**

```
Changed files (12 files, 345 insertions, 89 deletions):

| File | Changes |
|------|---------|
| src/main/java/com/example/UserService.java | +45 / -12 |
| src/test/java/com/example/UserServiceTest.java | +120 / -0 |
| ... | ... |

Use `pr-diff <path>` to see the diff for a specific file.
Use `cat <path>` to read the full file content.
```

**Benefits:**
- Reduces initial token usage by 95-99%
- Agent can focus on important files first
- Handles PRs with hundreds of files gracefully
- Prevents context overflow errors

### 5.3 How the agent uses pr-diff

During the review, the agent might call:

```json
{
  "tool": "pr-diff",
  "args": ["src/main/java/com/example/UserService.java"]
}
```

The tool returns the diff hunks for that specific file, allowing the agent
to inspect changes without loading the entire PR diff.

---

## 6. Database migration details

### 6.1 Migration V30

**Files:**
- `src/main/resources/db/migration/h2/V30__enable_pr_diff_for_agentic_review.sql`
- `src/main/resources/db/migration/postgresql/V30__enable_pr_diff_for_agentic_review.sql`

**What it modifies:**
- `bot_tool_selections` table (inserts rows)

**What it doesn't modify:**
- No schema changes
- No column additions
- No constraint changes
- No other tables affected

### 6.2 Idempotency

The migration is idempotent — running it multiple times (e.g., after a
`flyway repair`) is safe. The `NOT EXISTS` clause prevents duplicate inserts.

### 6.3 Rollback

If you roll back the application binary after V30 has executed, the new
`pr-diff` rows will remain in `bot_tool_selections`. The 1.14.x application's
`ToolCatalog` does not register this tool name, so it will appear as
`ToolKind.UNKNOWN` and be rejected by the whitelist enforcement in
`AgentToolRouter`. No runtime errors occur, but the rows are harmless dead
data. You can delete them manually if desired:

```sql
DELETE FROM bot_tool_selections WHERE tool_name = 'pr-diff';
```

---

## 7. Implementation details

### 7.1 New classes

- `org.remus.giteabot.prworkflow.agentreview.DiffSummary` — parses unified
  diffs into compact file-list summaries and provides per-file extraction

### 7.2 Modified classes

- `AgentRunContext` — added `diffSummary` field
- `ToolCallContext` — added `diffSummary` field
- `ToolCatalog` — registered `pr-diff` tool
- `AgentToolRouter` — added `executePrDiffTool()` handler
- `ReviewAgentStrategy` — passes `diffSummary` when creating `ToolCallContext`
- `AgentReviewService` — parses diff, generates compact summary, stores
  `DiffSummary` in context, removed `MAX_DIFF_CHARS_FOR_CONTEXT` constant

### 7.3 System prompt update

The system prompt for agentic review was updated to guide the agent on how
to use the compact summary and `pr-diff` tool effectively.

---

## 8. See also

- [`MIGRATION_1.13_TO_1.14.md`](./MIGRATION_1.13_TO_1.14.md) — previous
  migration guide (ctags tools).
- [`PR_WORKFLOWS_AGENTIC_REVIEW.md`](./PR_WORKFLOWS_AGENTIC_REVIEW.md) —
  updated workflow documentation.
- [`development-archive/smaller-diff-review-agent.md`](./development-archive/smaller-diff-review-agent.md) —
  implementation plan for compact diff handling.
