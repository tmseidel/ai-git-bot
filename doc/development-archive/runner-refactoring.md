# Runner Refactoring Plan — Unify on AgentRunContext

> **Goal:** All agentic PR workflow runners use `AgentRunContext` + its `auditToolCallConsumer` so `TOOL_CALL_EXECUTED` audit events are emitted uniformly, without each workflow service building its own audit wiring.

**Status:** Plan — not yet implemented.

---

## Current State

Four agentic workflows have their own chat-and-dispatch runners, separate from `AgentLoop`:

| Workflow | Runner | Uses AgentLoop? | Tool audit? |
|----------|--------|:---:|:---:|
| `agentic-review` | `AgentLoop` via `ReviewAgentStrategy` | yes | yes (via `AgentRunContext.auditToolCallConsumer`) |
| `e2e-test` | `E2eAgentRunner` | no | **no** |
| `unit-test` | `UnitTestAgentRunner` | no | **no** |
| `readme-sync` | `ReadmeSyncAgentRunner` | no | **no** |
| `i18n-coverage` | `I18nCoverageAgentRunner` | no | **no** |

All four non-AgentLoop runners share an identical architecture:

```
Agent class              Service class          Runner class
──────────────────────────────────────────────────────────────
TestPlannerAgent ─┐
TestAuthorAgent   ├── E2ETestService ──→ E2eAgentRunner.run(string) → Result
TestRunnerAgent  ─┘
UnitTestAuthorAgent ── UnitTestService ──→ UnitTestAgentRunner.run(string) → Result
ReadmeSyncAgent     ── ReadmeSyncService ──→ ReadmeSyncAgentRunner.run(string) → Result
I18nCoverageAgent   ── I18nCoverageService ──→ I18nCoverageAgentRunner.run(string) → Result
```

Each runner is a standalone class (~250–420 lines) containing:
- A `run(String initialUserMessage)` method with its own for-loop
- NATIVE mode: `aiClient.chatWithTools()` → dispatch `ToolCall`s → feed results back
- LEGACY mode: `aiClient.chat()` → parse `ImplementationPlan` → dispatch `ToolRequest`s → feed feedback
- A `ToolInvocation` record (toolName, args, result)
- A `Result` record (lastAssistantText, toolInvocations, rounds, budgetExhausted)
- Arg extraction: `extractArgs(JsonNode)` and `positionalToNamedArgs(String, List<String>)`
- `E2eAgentRunner` additionally has retry-and-compact, type coercion, and `HistoryCompactor`

---

## Why They Don't Use AgentLoop

The `E2eAgentRunner` Javadoc states the rationale (shared by all four):

> *AgentLoop is tied to AgentSession persistence, the AgentToolRouter, branch switching and an AgentRunContext that assumes a cloned source-repository workspace. These agents only dispatch PR_WORKFLOW tools against their own workspace and care about chat turns + tool results. Sharing AgentLoop would force us to fabricate fake sessions, fake branches and fake source-workspace paths.*

The blockers are:

1. **AgentSession requirement** — `AgentLoop` persists messages to `AgentSession` via `AgentSessionService`. The PR-workflow agents have no session concept.
2. **AgentToolRouter requirement** — `AgentLoop` dispatches tools through `AgentToolRouter`, which wraps executors with workspace validation. The PR-workflow tools use their own executors (`PrWorkflowToolExecutor`, `UnitTestToolExecutor`, `ReadmeSyncToolExecutor`, `I18nToolExecutor`) with their own context objects.
3. **Branch switching** — `AgentRunContext` carries `baseBranch` and is designed for source-repo workflows that switch branches. PR-workflow agents operate on a static workspace.
4. **AgentBudget** — `AgentLoop` requires an `AgentBudget` with context-window tokens, proactive compaction thresholds, etc. Runners use simpler `maxRounds` + `maxTokens`.

---

## Decision: Don't Migrate to AgentLoop — Extend Runners

Migrating all four runners to `AgentLoop` would require:
- Adding `AgentSession` persistence (no-op stubs or real sessions)
- Wrapping tool executors in `AgentToolRouter`
- Creating `AgentRunContext` with fake branches
- Wiring `AgentSessionService`, `TokenUsageTracker`, `HistoryCompactor`
- Rewriting all four agent classes to use `AgentStrategy` instead of direct `run(string)` calls

This is a disproportionate refactoring for the benefit (audit events). The runners are stable, well-tested, and deliberately simpler than `AgentLoop`.

**Instead:** Add a `Consumer<AgentRunContext.ToolCallRecord>` parameter to each runner. The consumer is called after each tool execution. The orchestrator already builds this consumer and places it on `PrWorkflowContext.auditToolCallConsumer()`. Each workflow threads it from the context → service → agent → runner.

---

## Scope

### Phase 1: Add Consumer to All Runners (4 files)

Add an optional `Consumer<AgentRunContext.ToolCallRecord>` field to each runner. Accept it in the constructor (nullable — no consumer = no audit, backward-compatible for tests).

Call it after each tool execution, in both NATIVE and LEGACY branches:

```java
// In the runner's run() loop, after toolExecutor.execute():
if (auditToolCallConsumer != null) {
    auditToolCallConsumer.accept(new AgentRunContext.ToolCallRecord(
        toolName,                        // from ToolCall.name() or ToolRequest.getTool()
        argsJson,                        // Map.toString() or JsonNode.toString()
        toolResult,                      // truncated to 1KB
        result != null && !result.startsWith("ERROR"),  // success
        durationMs,                     // per-invocation timing
        turn.inputTokens() > 0 ? turn.inputTokens() : null,
        turn.outputTokens() > 0 ? turn.outputTokens() : null,
        round));
}
```

Files:
- `src/main/java/org/remus/giteabot/prworkflow/e2e/agents/E2eAgentRunner.java`
- `src/main/java/org/remus/giteabot/prworkflow/unittest/agents/UnitTestAgentRunner.java`
- `src/main/java/org/remus/giteabot/prworkflow/readmesync/ReadmeSyncAgentRunner.java`
- `src/main/java/org/remus/giteabot/prworkflow/i18n/I18nCoverageAgentRunner.java`

### Phase 2: Thread Consumer From Workflow → Service → Agent → Runner

For each workflow, the chain is:

```
PrWorkflowContext.auditToolCallConsumer()   ← already set by orchestrator
        │
        ▼
Workflow.run(context)
        │ reads context.auditToolCallConsumer()
        ▼
Service.run(..., consumer)
        │ passes consumer through
        ▼
Agent.write(..., consumer)
        │ passes consumer to runner constructor
        ▼
Runner.run(userMessage)
        │ calls consumer after each tool execution
```

Files to modify per workflow:

**E2E (3 files):**
- `E2ETestWorkflow.java` — read consumer from context, pass to service
- `E2ETestService.java` — accept consumer param, pass to agent methods
- `TestPlannerAgent.java`, `TestAuthorAgent.java`, `TestRunnerAgent.java` — accept consumer, pass to runner

**UnitTest (3 files):**
- `UnitTestWorkflow.java`
- `UnitTestService.java`
- `UnitTestAuthorAgent.java`

**ReadmeSync (3 files):**
- `ReadmeSyncWorkflow.java`
- `ReadmeSyncService.java`
- `ReadmeSyncAgent.java`

**I18nCoverage (3 files):**
- `I18nCoverageWorkflow.java`
- `I18nCoverageService.java`
- `I18nCoverageAgent.java`

### Phase 3: Tests

Each runner test needs a new test case verifying the consumer is called. Each workflow test needs the method signature updated (new `Consumer` parameter). Follow the existing test patterns.

---

## Concrete Changes Per Runner

### E2eAgentRunner

| What | Detail |
|------|--------|
| New constructor param | `Consumer<AgentRunContext.ToolCallRecord> auditToolCallConsumer` (nullable) |
| New field | `private final Consumer<AgentRunContext.ToolCallRecord> auditToolCallConsumer;` |
| Consumer calls | 3 sites: LEGACY path (line ~219, after `toolExecutor.execute()`), NATIVE path (line ~247, after `toolExecutor.execute()`), narrated-tool-call recovery (TestAuthorAgent, TestRunnerAgent) |
| Token tracking | `turn.inputTokens()` / `turn.outputTokens()` available from `ChatTurn` in NATIVE mode; LEGACY mode has no token data |
| Duration | `System.nanoTime()` around each `toolExecutor.execute()` call |

### UnitTestAgentRunner

Identical pattern to `E2eAgentRunner` but simpler (no retry-and-compact, no type coercion). 2 consumer call sites: LEGACY (line ~137) and NATIVE (line ~160).

### ReadmeSyncAgentRunner

Identical to `UnitTestAgentRunner`. 2 consumer call sites. Additionally: the `ReadmeSyncAgent` has narrated-tool-call recovery that also calls `toolExecutor.execute()` — add consumer call there too.

### I18nCoverageAgentRunner

Identical to `ReadmeSyncAgentRunner`. 2 consumer call sites + narrated recovery.

---

## Files Changed Summary

| Action | File |
|--------|------|
| Modify | `src/main/java/.../e2e/agents/E2eAgentRunner.java` |
| Modify | `src/main/java/.../e2e/E2ETestWorkflow.java` |
| Modify | `src/main/java/.../e2e/E2ETestService.java` (or agents directly) |
| Modify | `src/main/java/.../e2e/agents/TestPlannerAgent.java` |
| Modify | `src/main/java/.../e2e/agents/TestAuthorAgent.java` |
| Modify | `src/main/java/.../e2e/agents/TestRunnerAgent.java` |
| Modify | `src/main/java/.../unittest/agents/UnitTestAgentRunner.java` |
| Modify | `src/main/java/.../unittest/UnitTestWorkflow.java` |
| Modify | `src/main/java/.../unittest/UnitTestService.java` |
| Modify | `src/main/java/.../unittest/agents/UnitTestAuthorAgent.java` |
| Modify | `src/main/java/.../readmesync/ReadmeSyncAgentRunner.java` |
| Modify | `src/main/java/.../readmesync/ReadmeSyncWorkflow.java` |
| Modify | `src/main/java/.../readmesync/ReadmeSyncService.java` |
| Modify | `src/main/java/.../readmesync/ReadmeSyncAgent.java` |
| Modify | `src/main/java/.../i18n/I18nCoverageAgentRunner.java` |
| Modify | `src/main/java/.../i18n/I18nCoverageWorkflow.java` |
| Modify | `src/main/java/.../i18n/I18nCoverageService.java` |
| Modify | `src/main/java/.../i18n/I18nCoverageAgent.java` |
| Modify | Tests for all above |

---

## Risks

1. **E2E multi-agent**: The E2E workflow has three agents (Planner, Author, Runner). Each gets the same consumer. This means one E2E workflow run produces tool-call events from three agents — they'll be interleaved in the audit trail by timestamp. The `agentLabel` distinguishes them.

2. **LEGACY mode token data**: Unlike NATIVE mode (`ChatTurn` carries `inputTokens`/`outputTokens`), the LEGACY path uses `aiClient.chat()` which returns a plain `String`. Token data is unavailable. Pass `null` for `inputTokens`/`outputTokens` in LEGACY mode.

3. **Narrated tool-call recovery**: Several agents have a post-hoc parsing step (`NarratedToolCallParser.parse()`) that re-executes tools from the assistant text when the model narrated them instead of using function calling. These also need consumer calls.

4. **Test surface**: ~15 test files need updates for the new `Consumer` parameter. Most pass `null`. Use the existing mock patterns from `AgentReviewWorkflowTest` (which was already updated for `auditToolCallConsumer`).

5. **Consumer is nullable everywhere**: Backward-compatible — all runners, agents, and services accept `null` for the consumer. Tests that don't care about auditing just pass `null` or use the existing constructor signatures.
