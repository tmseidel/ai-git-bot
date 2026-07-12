# Plan — PR-Workflow Tool-Execution SPI

**Status:** Proposed (do NOT start until the `readme-sync` feature is merged)
**Scope:** Internal refactor only — no behaviour change, no new feature, no DB migration.
**Author context:** Follow-up to the observation that `ReadmeSyncToolExecutor`,
`UnitTestToolExecutor` and `PrWorkflowToolExecutor` (E2E) are three copies of the
same pattern, and `E2eAgentRunner` / `UnitTestAgentRunner` / `ReadmeSyncAgentRunner`
are three near-identical chat-and-dispatch loops.

---

## Goal

Collapse the three duplicated PR-workflow tool executors behind one SPI and the
three duplicated agent runners into one shared runner, **without** merging any of
this into the coding-agent stack (`AgentToolRouter` / `ToolExecutionService`).

## Why NOT fold into `ToolExecutionService`

Deliberately out of scope — recorded here so the future implementer does not
"helpfully" over-unify:

- `ToolExecutionService` executes the **coding-agent tool families** only (file,
  context, validation) and is routed by `AgentToolRouter` (`Mode.CODING|WRITER`).
  It has no branch for `ToolKind.PR_WORKFLOW`; a `doc-write` sent there falls
  through to "unknown tool".
- The `PR_WORKFLOW` tools trigger **workflow-internal side-effects tied to
  persistent domain state** — `pr-test-write`/`unit-test-write` upsert
  `PrTestCase`/`UnitTestCase` rows, `doc-write`/`doc-delete` mutate the in-memory
  change set on `ReadmeSyncToolContext`. Each needs its own context object, not the
  generic `ToolCallContext`.
- Each executor enforces a **workflow-specific scope guard** (`UnitTestPathGuard`,
  `DocPathGuard`) that has no meaning in a generic executor.
- The `ToolCatalog.Role.PR_WORKFLOW` role governs **advertisement** of tool schemas
  to the LLM, never execution. Execution has always been the workflow's own concern.

Merging would couple generic build/file execution to workflow-specific persistence
and scope rules, and would violate the existing `config`/`session` package
independence rules enforced by `ArchitectureTest`. The SPI keeps the correct
separation while removing the copy-paste.

---

## Current state (before)

Three executors, uniform public signature already:

| Executor | Package | Context param | Tools dispatched |
|---|---|---|---|
| `PrWorkflowToolExecutor` | `prworkflow.e2e.tools` | `PrWorkflowToolContext` (record) | `pr-test-write`, `pr-test-run`, `preview-url`, `preview-status`, `attach-artifact` |
| `UnitTestToolExecutor` | `prworkflow.unittest.tools` | `UnitTestToolContext` (record) | `unit-test-write` |
| `ReadmeSyncToolExecutor` | `prworkflow.readmesync` | `ReadmeSyncToolContext` (class) | `doc-write`, `doc-delete` |

All three expose exactly:
```java
String execute(String toolName, Map<String,Object> args, <Ctx> ctx);
// "OK: ..." on success, "ERROR: ..." on failure
```

Three runners (chat-and-dispatch loops), all `final class`, constructed per-agent-call:

| Runner | Extra machinery vs. the others |
|---|---|
| `E2eAgentRunner` | **superset** — has `HistoryCompactor` (maxHistoryChars ctor arg) AND arg-type decoding (`argTypeByTool`, JSON-decodes array/object positional args) |
| `UnitTestAgentRunner` | arg-order zip only (`argOrderByTool`), no compactor, no arg-type decode |
| `ReadmeSyncAgentRunner` | same as UnitTest (arg-order zip only) |

All three share: native-vs-legacy `ToolingMode.resolve`, the LEGACY-envelope
fallback via `AiResponseParser` + `ImplementationPlan`, the NATIVE `tool_use`
loop, a `record ToolInvocation(String toolName, Map<String,Object> args, String result)`,
and a `record Result(String lastAssistantText, List<ToolInvocation> toolInvocations,
int rounds, boolean budgetExhausted)` with a `writeCount()`-style helper.

---

## Target design (after)

### 1. The SPI — `PrWorkflowToolExecutor` interface

New package `org.remus.giteabot.prworkflow.tools` (neutral home; not under `e2e`).

```java
public interface PrWorkflowToolExecutor<C> {
    /** @return "OK: ..." on success, "ERROR: <reason>" on any failure. Never throws. */
    String execute(String toolName, Map<String, Object> args, C context);
}
```

- Generic over the context type `C` so each workflow keeps its typed context
  (`PrTestToolContext` / `UnitTestToolContext` / `ReadmeSyncToolContext`) with no
  casting. The runner is parameterised on the same `C`.
- **Rename clash:** the E2E concrete class is *already* named
  `PrWorkflowToolExecutor`. Rename that concrete class to `E2eToolExecutor` (or
  `PrTestToolExecutor`) so the interface can take the clean SPI name. Update its
  `@Component` injection sites (`E2eAgentRunner` construction in the E2E agents,
  and any test mocks).

### 2. The shared runner — `PrWorkflowAgentRunner<C>`

New `final class PrWorkflowAgentRunner<C>` in `prworkflow.tools`, built from
`E2eAgentRunner` (the superset) so no capability is lost:

```java
public final class PrWorkflowAgentRunner<C> {
    public PrWorkflowAgentRunner(AiClient aiClient,
                                 PrWorkflowToolExecutor<C> toolExecutor,
                                 C toolContext,
                                 List<ToolDescriptor> toolDescriptors,
                                 String systemPrompt,
                                 int maxRounds,
                                 Integer maxTokens,
                                 int maxHistoryChars,   // 0 or negative => compaction disabled
                                 String agentLabel) { ... }

    public record ToolInvocation(String toolName, Map<String,Object> args, String result) {}
    public record Result(String lastAssistantText, List<ToolInvocation> toolInvocations,
                         int rounds, boolean budgetExhausted) {
        public long okInvocationCount() { /* result startsWith "OK" */ }
    }
    public Result run(String initialUserMessage) { ... }
}
```

- Keep `HistoryCompactor` + arg-type decoding from `E2eAgentRunner`. UnitTest and
  ReadmeSync currently lack the compactor — adopting it is a **safe superset**
  (compaction only triggers past `maxHistoryChars`; pass `0` to preserve their
  current no-compaction behaviour if we want a strictly-inert migration first).
- Replace the per-workflow `writeCount()` helpers with the generic
  `okInvocationCount()`; each `*Agent` already recomputes its own semantic count
  (`filesWritten` / `changesApplied` / `prTestRunInvocations`) by filtering
  invocations, so this stays workflow-owned.

### 3. Concrete executors become thin `implements`

```java
@Component
public class E2eToolExecutor implements PrWorkflowToolExecutor<PrTestToolContext> { ... }
@Component
public class UnitTestToolExecutor implements PrWorkflowToolExecutor<UnitTestToolContext> { ... }
@Component
public class ReadmeSyncToolExecutor implements PrWorkflowToolExecutor<ReadmeSyncToolContext> { ... }
```

Bodies are unchanged — the guards (`UnitTestPathGuard`, `DocPathGuard`), the
`resolveInsideWorkspace` sandbox and the persistence upserts all stay exactly where
they are. Only the class declaration gains `implements`.

### 4. The three agents swap runner type

`TestPlannerAgent` / `TestAuthorAgent` / `TestRunnerAgent` /
`UnitTestAuthorAgent` / `ReadmeSyncAgent` construct
`PrWorkflowAgentRunner<>` instead of their bespoke runner, passing their executor
(now an SPI impl) and typed context. Delete `E2eAgentRunner`,
`UnitTestAgentRunner`, `ReadmeSyncAgentRunner`.

---

## Sequence

1. Create package `prworkflow.tools`; add the `PrWorkflowToolExecutor<C>` interface.
2. Rename the E2E concrete `PrWorkflowToolExecutor` → `E2eToolExecutor`
   (class + filename + `@Component`); update its injectors and test mocks. Compile.
3. Lift `E2eAgentRunner` into `PrWorkflowAgentRunner<C>` (generic context + SPI
   executor). Keep compactor + arg-type decoding. Add `okInvocationCount()`.
4. Make `E2eToolExecutor` implement `PrWorkflowToolExecutor<PrTestToolContext>`;
   point the E2E agents at `PrWorkflowAgentRunner<PrTestToolContext>`; delete
   `E2eAgentRunner`. Run E2E agent tests.
5. Repeat step 4 for unit-test (`UnitTestToolExecutor` + `UnitTestAuthorAgent`,
   delete `UnitTestAgentRunner`), then readme-sync (`ReadmeSyncToolExecutor` +
   `ReadmeSyncAgent`, delete `ReadmeSyncAgentRunner`). Run each workflow's tests
   between steps.
6. Full `mvn -o -Denforcer.skip=true test`; confirm `ArchitectureTest` still green.

Do the migrations one workflow at a time (steps 4→5→5) so a regression is bisectable
to a single workflow, not the whole batch.

---

## Test impact

- `E2eAgentRunner` / `UnitTestAgentRunner` / `ReadmeSyncAgentRunner` have no direct
  unit tests today — they're exercised through `TestAuthorAgentTest`,
  `TestRunnerAgentTest`, and the readme-sync/unit-test agent tests. Those assert on
  the **agent** `Result` records (`budgetExhausted()`, `filesWritten()`), which are
  unchanged, so they should pass untouched.
- Any test that mocks the E2E `PrWorkflowToolExecutor` must update to the renamed
  `E2eToolExecutor` type (step 2).
- Add ONE new focused test `PrWorkflowAgentRunnerTest` (native + legacy dispatch,
  budget exhaustion, OK/ERROR counting) to replace the coverage that was implicit
  across three runners. Match the existing flat (non-`@Nested`) test style in
  `prworkflow.e2e.agents`.

## Risks / watch-list

- **Behaviour drift from adopting the compactor** in unit-test/readme-sync. Mitigate
  by passing `maxHistoryChars = 0` in the first migration commit (inert), then enable
  compaction in a separate follow-up if desired.
- **Arg-type decoding** (E2e-only today): harmless superset for the other two — they
  only pass `path`/`content`/`title` strings, which the decoder leaves untouched.
- **Generic-erasure at the `@Component` boundary:** the SPI is generic but each
  concrete bean binds a concrete `C`; Spring injects by the concrete type, so there's
  no ambiguity. Do NOT inject `PrWorkflowToolExecutor<?>` anywhere — always the
  concrete impl.
- **`ArchitectureTest` package rules:** the new `prworkflow.tools` package lives under
  `prworkflow..`, already exempt in the existing rules — no rule change expected, but
  re-run the test to confirm.

## Acceptance criteria

- One SPI interface + one shared runner; the three `*AgentRunner` classes deleted.
- Three executors reduced to `implements PrWorkflowToolExecutor<...>` with unchanged
  bodies (guards + persistence intact).
- No change to any `PrWorkflow`, `*Service`, `ToolCatalog`, `AgentToolRouter` or
  `ToolExecutionService`.
- No DB migration, no config change, no new dependency.
- Full test suite green; `ArchitectureTest` green.
