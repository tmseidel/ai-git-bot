# Implementation Plan: Compact Diff for Agentic PR Review

## Problem

`AgentReviewService.reviewPullRequest()` fetches the full unified diff via
`repositoryClient.getPullRequestDiff()` and embeds it (up to `MAX_DIFF_CHARS_FOR_CONTEXT =
60,000` chars) into the kickoff user message. For large PRs this overwhelms the AI
provider's context window, causing HTTP 400 errors. Since the agentic review already runs
multiple tool rounds with read-only exploration tools (`cat`, `rg`, `ctags-signatures`,
etc.), the full diff upfront is unnecessary — the agent can load file content on demand.

## Goal

Replace the full-diff kickoff message with a **compact file-list summary** so the
agent starts with a lightweight overview and uses its existing tools to load per-file
diffs and source content as needed. This keeps token usage low on the first turn and
lets the agent focus its context budget on the files it actually needs to reason about.

---

## Key Architecture Findings

| Layer | Current behaviour |
|---|---|
| **Diff fetch** | `AgentReviewService.reviewPullRequest()` calls `repositoryClient.getPullRequestDiff(owner, repo, prNumber)` — returns the full unified diff as a `String`. |
| **Kickoff message** | `buildKickoffMessage(prTitle, prBody, diff)` embeds the (truncated) diff in a fenced `diff` block inside the user message. |
| **Clarification message** | `buildClarificationMessage(...)` does the same — full diff embedded. |
| **Available tools** | `ToolCatalog.Role.WRITER` exposes: `cat`, `rg`, `find`, `tree`, `git-log`, `git-blame`, `branch-switcher`, `ctags-signatures`, `ctags-deps`, `get-issue`, `search-issues`, plus MCP tools. |
| **Workspace** | Cloned at the PR head branch — all read-only tools can access the actual file content. |
| **AgentRunContext** | Carries `owner`, `repo`, `issueNumber`, `workspaceDir`, `baseBranch` — can be extended with additional state. |
| **Tool execution** | `ToolExecutionService.executeContextTool()` — switch on tool name, resolves workspace path, runs command or custom logic. New tools follow this pattern. |

---

## Design

### Approach: Parse the full diff locally + add a `pr-diff` tool

No new API calls to the repository provider. The full diff is still fetched once (needed
for the per-file extraction), but instead of embedding it in the prompt we:

1. **Parse it locally** into a compact `--stat`-like summary (file paths + insertion/
   deletion counts).
2. **Store the raw diff** in `AgentRunContext` so a new tool can extract per-file hunks.
3. **Register a new `pr-diff` context tool** that the agent calls to get the diff hunks
   for a specific file.

This is provider-agnostic — works for Gitea, GitHub, GitLab, and Bitbucket without any
`RepositoryApiClient` changes.

### Kickoff message (before → after)

**Before:**
```
Please review the following pull request.

Title: ...
Description: ...

The repository is checked out in your read-only workspace. ...

Unified diff:
```diff
<up to 60,000 chars of full diff>
```

When you have gathered enough context, reply with your final review...
```

**After:**
```
Please review the following pull request.

Title: ...
Description: ...

The repository is checked out in your read-only workspace. Use your available
read-only tools to inspect the surrounding code before judging the change.
You cannot modify the repository.

Changed files (12 files, 345 insertions, 89 deletions):

| File | +/- |
|---|---|
| src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java | +45 / -12 |
| src/main/java/org/remus/giteabot/repository/RepositoryApiClient.java | +8 / -0 |
| src/test/java/.../AgentReviewServiceTest.java | +120 / -35 |
| ... |

To see the diff hunks for a specific file, call the `pr-diff` tool with the
file path. To read the full current content of a file, use `cat`. To understand
a file's structure before reading it, use `ctags-signatures`.

When you have gathered enough context, reply with your final review as plain
Markdown (no tool calls). Summarise correctness, risks, and concrete suggestions.
```

---

## Components to Create or Modify

### 1. `DiffSummary` (new value class)

A small parser that takes a unified diff string and produces:
- A list of changed file paths with per-file insertion/deletion counts.
- A total summary line.
- A method to extract the diff hunks for a single file path.

**Location:** `org.remus.giteabot.prworkflow.agentreview.DiffSummary`

```java
/**
 * Parses a unified diff into a compact file-level summary and supports
 * per-file hunk extraction for the {@code pr-diff} tool.
 */
public final class DiffSummary {

    public record FileEntry(String path, int additions, int deletions) {}

    /** Parse the full unified diff into file entries. */
    public static DiffSummary parse(String fullDiff) { ... }

    /** Compact stat line: "12 files changed, 345 insertions(+), 89 deletions(-)" */
    public String statLine() { ... }

    /** Markdown table of changed files with +/- counts. */
    public String fileTable() { ... }

    /** All changed file paths. */
    public List<String> changedFiles() { ... }

    /** Extract the diff hunks (header + @@ lines) for a single file. */
    public String fileDiff(String filePath) { ... }
}
```

**Parsing strategy:**
- Split on `\ndiff --git` (or `---` for non-git diffs) to identify file boundaries.
- For each file block, extract the `b/...` path from the `+++ b/path` line.
- Count lines starting with `+` (not `+++`) as additions, `-` (not `---`) as deletions.
- For `fileDiff()`, return the entire block between two `diff --git` markers.

### 2. `AgentRunContext` — store the parsed DiffSummary

Add a `DiffSummary` field to `AgentRunContext` so the `pr-diff` tool can access it
without re-parsing.

**Location:** `org.remus.giteabot.agent.loop.AgentRunContext`

```java
// New field (nullable — only set for review workflows)
private DiffSummary diffSummary;

public DiffSummary diffSummary() { return diffSummary; }
public void setDiffSummary(DiffSummary diffSummary) { this.diffSummary = diffSummary; }
```

### 3. `ToolCatalog` — register `pr-diff`

Add a new `CONTEXT` tool entry to `STATIC_TOOLS`, available for `Role.WRITER`
(and `Role.CODING` for parity).

```java
entry("pr-diff", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
        "Return the diff hunks for a specific changed file in the current pull request. "
                + "Use this after inspecting the changed-file summary to see what exactly "
                + "was added, removed, or modified in a file. "
                + "Args: [\"path/to/file\"].",
        objectSchema(
                prop("path", "string", "Repository-relative path of the changed file."),
                required("path"))),
```

### 4. `ToolExecutionService` — implement `pr-diff`

Add a `case` branch to `executeContextTool()`:

```java
case "pr-diff" -> executePrDiffTool(arguments);
```

The implementation reads the `DiffSummary` from a thread-local or context parameter
(passed via `ToolCallContext`):

```java
private ToolResult executePrDiffTool(List<String> arguments) {
    if (arguments == null || arguments.isEmpty()) {
        return new ToolResult(false, -1, "", "pr-diff requires a file path");
    }
    String filePath = arguments.getFirst();
    DiffSummary summary = /* obtained from context */;
    if (summary == null) {
        return new ToolResult(false, -1, "", "No PR diff available in this context");
    }
    String hunk = summary.fileDiff(filePath);
    if (hunk == null || hunk.isBlank()) {
        return new ToolResult(true, 0,
                "No diff hunks found for: " + filePath
                + "\nChanged files: " + String.join(", ", summary.changedFiles()), "");
    }
    return new ToolResult(true, 0, hunk, "");
}
```

**Context passing:** The `DiffSummary` needs to reach `ToolExecutionService`. Two options:

- **Option A (preferred):** Extend `ToolCallContext` with an optional `DiffSummary` field.
  `ReviewAgentStrategy` already builds `ToolCallContext` in `executeAll()` — it just needs
  to pass the summary from `AgentRunContext`.
- **Option B:** Pass it as a constructor parameter to `ToolExecutionService` per-invocation
  (less clean — the service is a singleton).

### 5. `ToolCallContext` — add optional DiffSummary

```java
// New field
private final DiffSummary diffSummary;

// New constructor overload or builder field
```

### 6. `AgentReviewService` — modify kickoff message

#### 6a. `reviewPullRequest()`

```java
String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
// ... existing null check ...

DiffSummary diffSummary = DiffSummary.parse(diff);

// ... workspace prep ...

String userMessage = buildKickoffMessage(prTitle, prBody, diffSummary);

// Store in AgentRunContext (done inside runReviewLoop)
```

#### 6b. `buildKickoffMessage()` — accept DiffSummary instead of raw diff

```java
private String buildKickoffMessage(String prTitle, String prBody, DiffSummary diffSummary) {
    StringBuilder sb = new StringBuilder();
    sb.append("Please review the following pull request.\n\n");
    sb.append("Title: ").append(prTitle == null ? "(none)" : prTitle).append('\n');
    if (prBody != null && !prBody.isBlank()) {
        sb.append("Description:\n").append(prBody).append('\n');
    }
    sb.append("""
            
            The repository is checked out in your read-only workspace. Use your available \
            read-only tools to inspect the surrounding code before judging the change. \
            You cannot modify the repository.
            
            """);
    sb.append("Changed files (").append(diffSummary.statLine()).append("):\n\n");
    sb.append(diffSummary.fileTable()).append("\n\n");
    sb.append("""
            To see the diff hunks for a specific file, call the `pr-diff` tool with the \
            file path. To read the full current content of a file, use `cat`. To understand \
            a file's structure before reading it, use `ctags-signatures`.
            
            When you have gathered enough context, reply with your final review as plain \
            Markdown (no tool calls). Summarise correctness, risks, and concrete suggestions.""");
    return sb.toString();
}
```

#### 6c. `runReviewLoop()` — pass DiffSummary into AgentRunContext

```java
AgentRunContext ctx = new AgentRunContext(session, owner, repo, prNumber,
        workspaceDir, headBranch);
ctx.setDiffSummary(diffSummary);
```

#### 6d. `answerClarification()` — same treatment

Apply the same compact-diff approach to `buildClarificationMessage()`. The
clarification flow also fetches the full diff and embeds it — replace with the
`DiffSummary` table + `pr-diff` tool reference.

#### 6e. Remove `MAX_DIFF_CHARS_FOR_CONTEXT`

The 60,000-char truncation constant is no longer needed since the full diff is
never embedded in the prompt. The raw diff is only stored in `DiffSummary` for
per-file extraction (no size limit needed — individual file hunks are small).

### 7. `ReviewAgentStrategy` — pass DiffSummary to ToolCallContext

In `executeAll()`, when building `ToolCallContext`, pass the `DiffSummary` from
`AgentRunContext`:

```java
new ToolCallContext(ctx.owner(), ctx.repo(), ctx.issueNumber(),
        ctx.workspaceDir(), req, ctx.diffSummary())
```

### 8. System prompt update

The operator-editable review-agent system prompt (entity column
`system_prompts.review_agent_system_prompt`) should mention the `pr-diff` tool
in its guidance. This is a documentation/guidance change — the `SystemPromptAssembler`
already appends the tool protocol automatically from `ToolCatalog` descriptors, so
the tool schema is advertised to the LLM without prompt changes.

Recommended addition to the default review-agent system prompt (Flyway migration
or manual update):

```
When reviewing a PR, start by scanning the changed-file summary. Use `pr-diff`
to see what changed in specific files, `cat` to read full file content for
surrounding context, and `ctags-signatures` to understand a file's structure
before diving into details. Focus your review on the most significant changes
first.
```

---

## Implementation Sequence

1. **`DiffSummary`** — new class with `parse()`, `statLine()`, `fileTable()`,
   `changedFiles()`, `fileDiff()`. Write unit tests with known diff fixtures.

2. **`AgentRunContext`** — add `diffSummary` field + getter/setter.

3. **`ToolCallContext`** — add optional `DiffSummary` field (constructor overload
   or builder extension).

4. **`ToolCatalog`** — register `pr-diff` entry.

5. **`ToolExecutionService`** — add `case "pr-diff"` and `executePrDiffTool()`.

6. **`ReviewAgentStrategy`** — pass `diffSummary` from `AgentRunContext` into
   `ToolCallContext` in `executeAll()`.

7. **`AgentReviewService`**:
   - Parse `DiffSummary` from the fetched diff.
   - Rewrite `buildKickoffMessage()` to use `DiffSummary`.
   - Rewrite `buildClarificationMessage()` to use `DiffSummary`.
   - Set `diffSummary` on `AgentRunContext` in `runReviewLoop()`.
   - Remove `MAX_DIFF_CHARS_FOR_CONTEXT`.

8. **System prompt** — add guidance about the new tool to the default review-agent
   prompt (Flyway migration or documentation update).

9. **Tests** — unit tests for `DiffSummary`, integration tests for the modified
   kickoff message, and the `pr-diff` tool execution.

---

## DiffSummary Parsing Details

### Input format (unified diff)

```
diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
index abc1234..def5678 100644
--- a/src/main/java/Foo.java
+++ b/src/main/java/Foo.java
@@ -10,6 +10,8 @@ public class Foo {
     existing line
-    removed line
+    added line 1
+    added line 2
     existing line
diff --git a/src/test/java/FooTest.java b/src/test/java/FooTest.java
...
```

### Parsing rules

1. Split on lines matching `^diff --git ` to get per-file blocks.
2. For each block, extract the file path from `+++ b/<path>` (or `--- a/<path>`
   for deletions where `+++ /dev/null`).
3. Count lines starting with `+` (excluding `+++`) as additions.
4. Count lines starting with `-` (excluding `---`) as deletions.
5. For `fileDiff()`: return the full block verbatim (header + hunks).

### Edge cases

| Scenario | Handling |
|---|---|
| New file (only `+++ b/...`, `--- /dev/null`) | Path from `+++`, all `+` lines counted |
| Deleted file (`+++ /dev/null`) | Path from `---`, all `-` lines counted |
| Renamed file (`rename from` / `rename to`) | Use `rename to` path |
| Binary file (`Binary files differ`) | Show as `Binary file changed` in table |
| Empty diff | `DiffSummary.parse("")` returns empty entries |
| Non-git unified diff (no `diff --git` header) | Fall back to splitting on `^--- ` / `^+++ ` pairs |

---

## Token Budget Impact

| Scenario | Before (chars) | After (chars) |
|---|---|---|
| Small PR (3 files, 50 lines changed) | ~2,000 | ~400 |
| Medium PR (15 files, 500 lines changed) | ~20,000 | ~1,200 |
| Large PR (50 files, 3000 lines changed) | 60,000 (truncated) | ~3,500 |
| Very large PR (200+ files) | 60,000 (truncated, data loss) | ~10,000 (complete file list) |

The agent then loads only the files it needs via `pr-diff` and `cat`, keeping each
tool round's context proportional to the files actually under review rather than the
total PR size.

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| `DiffSummary.parse()` receives malformed diff | Returns best-effort partial parse; logs a warning. If no entries found, falls back to embedding the raw diff (truncated) as before. |
| `pr-diff` called with unknown file path | Returns a helpful message listing the available changed file paths. |
| `pr-diff` called in non-review context (no DiffSummary set) | Returns error: "No PR diff available in this context." |
| Diff is empty/null | Existing early-return in `reviewPullRequest()` already handles this. |

---

## Fallback for Very Large File Lists

If a PR touches hundreds of files, the file table itself could become large. Add a
cap: if the number of changed files exceeds a threshold (e.g. 100), truncate the
table and append `... and N more files. Use `find` or `rg` to locate specific files.`

---

## Testing Strategy

1. **Unit test: `DiffSummary.parse()`** — feed known diffs (small, large, with
   renames, binary files, new/deleted files, empty). Verify file entries, counts,
   stat line, file table, and per-file extraction.

2. **Unit test: `DiffSummary.fileDiff()`** — verify correct hunk extraction for
   each file in a multi-file diff. Verify helpful error for unknown paths.

3. **Unit test: `buildKickoffMessage()`** — verify the new format contains the
   file table, stat line, and tool guidance. Verify no raw diff is embedded.

4. **Integration test: `pr-diff` tool** — set up a `ToolCallContext` with a
   `DiffSummary`, call the tool, verify the returned hunks.

5. **Integration test: `AgentReviewService`** — mock the repository client to
   return a large diff, verify the kickoff message is compact and the
   `DiffSummary` is stored in the context.

6. **Existing tests** — update `AgentReviewWorkflowTest` and any tests that
   assert on the kickoff message format.

---

## Out of Scope

- Provider-specific `getPullRequestFiles()` API additions (we parse the diff locally).
- Inline review comments (the agent still produces a single review comment).
- Changes to the `CodeReviewService` (non-agentic review workflow) — that workflow
  is a single-shot LLM call and still needs the full diff.
- Streaming or chunked diff delivery — the diff is fetched once and stored in memory.

---

## Migration Notes

- No database migration needed (no schema changes).
- The `MAX_DIFF_CHARS_FOR_CONTEXT` constant can be removed from `AgentReviewService`.
- The `buildClarificationMessage()` method signature changes (takes `DiffSummary`
  instead of raw diff string) — internal only, no API impact.
- The `pr-diff` tool is automatically advertised to the LLM via `ToolCatalog`
  descriptors — no operator configuration needed.
