# Architecture — Answer-Only Completion for the Coding Agent

**Status:** Implemented — see [#418](https://github.com/tmseidel/ai-git-bot/issues/418)
**Scope:** Coding agent (`CodingAgentStrategy`) in **NATIVE** tool mode only. One new session
status (migration V53, `h2` + `postgresql`). No new configuration, no new budget knob.
**Settled decisions:** default-on, no feature flag; NATIVE only (legacy keeps its hard-fail);
new `ANSWERED` session status instead of reusing `IN_PROGRESS`.
**Origin:** bug report against `tmseidel/ai-git-bot:latest`, mode NATIVE, qwen3.6-35B-A3B,
context 16384, CPU-only. Issue: *"Read docker-compose.yaml. Do not modify anything. Tell me the
first 10 lines."*

---

## 1. Problem

Line references are to `CodingAgentStrategy` / `AgentLoop` at `5483102`; the reporter's
`c779085:172` is byte-identical to `CodingAgentStrategy.java:172` today.

| round | model output | strategy | prompt cost |
|-------|--------------|----------|-------------|
| 1 | `cat docker-compose.yaml` | context-only round: `fileRequestRounds++`, `ContinueWithToolResults`, `attempt` untouched (`:204-210`) | 11,468 in |
| 2 | prose answer, 0 tool_calls, `END_TURN` | `:146` -> not a JSON envelope (`:150`) -> NATIVE (`:161`) -> workspace clean (`:162`) -> `attempt++` (`:171`) -> `Continue(buildMissingToolFeedback())` (`:172`). **The answer is discarded; `stopReason` is never read.** | 11,468 in / 218 out |
| 3..32 | repeats the same answer | same branch, no exit condition | ~11–13K each |
| — | — | proactive compaction fires: 0.7 x 16384 = 11468.8 tokens vs the logged 11,468 | history churn |
| 32 | — | loop cap (`AgentLoop:85`) -> `onBudgetExhausted` -> `LoopOutcome.fail` -> *"I was unable to produce a valid implementation"* | ~370K in total, ~20 min CPU |

Root causes, in order of severity:

1. **No completion path for a task that needs no repository change.** Every `Finish(success)` is
   gated on `WorkspaceService#hasUncommittedChanges` (`:162`, `:247`, `:478`). A read-only
   request therefore has a 0 % success probability by construction. Nothing in the runtime
   interprets an issue's read-only/ask-only intent — the read-only tool surface exists only for
   the review, triage and writer roles.
2. **The branch has no budget guard.** It increments `attempt` and never checks it. The legacy
   step checks the same budget as its first statement (`:382-385`); the tool-call branch checks it
   at `:176-179`. Only the loop's round cap terminates the run, which is why a guaranteed-failing
   request costs 32 rounds instead of one.
3. **The nudge is the wrong protocol.** `AgentPromptBuilder#buildMissingToolFeedback` (`:338`)
   instructs the model to return a JSON `runTools` envelope — inside a NATIVE run whose tools
   arrive through the function-calling API. It is also byte-identical every round, so the model
   has no new information and repeats itself.
4. **Prose rounds consume the validation budget.** `attempt` is shared between prose turns and
   real tool rounds, so a chatty model exhausts `maxValidationRetries` and can then trip
   `:176` on a later, genuine tool round.
5. **The answer is never shown to the user.** Nothing posts it; it only reaches the session log
   (`AgentLoop:106`).

## 2. Goal / invariant

An issue that needs no repository change ends as a **successful run that posts an answer
comment** — never as a PR, never as a burnt round budget. Issues that do need changes keep
today's behaviour exactly, including the `FAILED` signal when an implementation was attempted
and produced nothing.

## 3. Trigger

`CodingAgentStrategy#step(AgentRunContext, ChatTurn, int)` is reached with a turn that has **no
`tool_calls`**, whose text does **not** parse as a JSON plan envelope, in **NATIVE** mode. Today
this is the only path that can neither finish nor fail — `:161-172`. The change is confined to
that branch.

## 4. Design

### 4.1 Policy — replaces the single unbounded `Continue`

| # | condition | decision |
|---|-----------|----------|
| R1 | workspace has uncommitted changes | unchanged: `Finish(success)` -> commit + PR |
| R2 | clean, `answerNudges == 0` | record answer candidate; `answerNudges++`; `Continue(native two-way nudge)` |
| R3 | clean, `answerNudges >= 1`, `!implementationAttempted`, candidate != null | `Finish(LoopOutcome.answered(branch, candidate))` |
| R4 | anything else — blank text, truncated-only, or an implementation was attempted and produced nothing | `Finish(fail)` |

R4 is also where the missing budget guard (root cause 2) lands: the branch can no longer return
`Continue` more than once.

### 4.2 New strategy state

* `int answerNudges` — prose-only rounds spent; bounded at 1 by construction, so it needs no
  configuration and cannot interact with `attempt`.
* `boolean implementationAttempted` — set when a tool-execution round actually executes a
  mutation or validation request. **Not** expressed as `attempt > 1`: `attempt` is also bumped by
  no-diff tool rounds and by validation retries, so it does not mean "the agent tried to
  implement". The explicit flag is what keeps R3 honest.
* Prose rounds no longer touch `attempt` at all (fixes root cause 4, and R3 depends on it).

### 4.3 Answer candidate selection

The candidate is the **longest prose turn seen while the workspace was clean whose
`stopReason` is `END_TURN`**.

- *Longest, not last*: a model that answers well and then replies "Understood, nothing needed"
  still yields the good text, and no arbitrary minimum-length constant is needed.
- *`END_TURN` only*: a `MAX_TOKENS` turn is truncated, so a half sentence is never posted.
- *Clean workspace only*: a prose turn with a dirty workspace is the existing "I'm done" signal
  (R1) and must not be collected as an answer.

### 4.4 Outcome type — a payload, not a new record component

```java
// LoopOutcome
public record AgentAnswer(String text) {}
public static LoopOutcome answered(String selectedBranch, String text) {
    return new LoopOutcome(true, selectedBranch, new AgentAnswer(text));
}
```

`success == true` keeps the existing invariant ("the run achieved its goal"), and the payload
type tells the caller which domain action to perform — the same payload-driven dispatch the
writer agent already uses for clarifying questions.

Rejected:

* **A new component on `LoopOutcome`.** Only the three factories use the canonical constructor
  (29 call sites use the factories), so the churn would be small — but it changes a type shared
  by review, triage, writer and E2E for the benefit of one caller.
* **`ImplementationPlan#finalAnswer`.** That type is the model-visible JSON envelope; a new field
  would also have to appear in the legacy protocol renderer and the parser.

### 4.5 Caller wiring (`IssueImplementationService`)

`ToolImplementationLoopResult` gains the answer payload; both entry points branch on it before
any other action.

* `handleIssueAssigned` — set the status, post the answer comment, `return` **before** the critic
  step, commit, push and PR creation (`:212-264`). The critic is skipped deliberately: there is
  no diff to reflect on, and running it would convert a legitimate answer into `ABORT`/`ITERATE`.
* `handleIssueComment` — same, except the status: with `session.getPrNumber() != null` keep
  `PR_CREATED` (an answer to a follow-up question must not erase the open-PR state), otherwise
  `ANSWERED`. Mirrors the existing conditional at `:450`.
* A later `@mention` still continues the conversation: `handleIssueComment` resolves the session
  through `getSessionByIssue` and does not filter on status, and `claimSessionForUpdate` — which
  does filter — is used only by the writer path (`WriterAgentService:197`).

### 4.6 Session status and migration

`AgentSession.AgentSessionStatus.ANSWERED` — *"Coding agent answered the issue without opening a
PR."* The column is `@Enumerated(EnumType.STRING)` (`AgentSession:87-89`), and no template or
message bundle renders the enum (verified: only event-hook and deployment-target badges switch on
their own status enums), so there is no i18n work.

The column does carry a `CHECK` constraint, so the new value needs a migration in **both**
dialects (`V1__init_schema.sql:84`, extended by `V5__technical_writer_agent.sql` in both dirs;
both dialects currently end at V51):

```sql
-- V52__agent_session_answered_status.sql  (h2 + postgresql, CRLF)
ALTER TABLE agent_sessions DROP CONSTRAINT IF EXISTS chk_agent_sessions_status;
ALTER TABLE agent_sessions DROP CONSTRAINT IF EXISTS agent_sessions_status_check;
ALTER TABLE agent_sessions ADD CONSTRAINT chk_agent_sessions_status
    CHECK (status IN ('IN_PROGRESS', 'PR_CREATED', 'UPDATING', 'COMPLETED', 'FAILED',
                      'ISSUE_CREATED', 'ANSWERED'));
```

Gate: a standalone Flyway/H2 probe in the style of `IssueWorkflowMigrationTest` (scratch DB at
`filesystem:src/main/resources/db/migration/h2`, `target("51")`, then migrate to latest; assert a
session row with status `ANSWERED` persists and that an unknown status is still rejected).

Rejected: reusing `IN_PROGRESS` (what the writer sets when it posts clarifying questions) — it
saves the migration but leaves a coding session with no PR looking permanently stuck.

### 4.7 Answer comment

New `IssueNotificationService#postAnswerComment(owner, repo, issueNumber, text)`: the
`AI Agent` header, the answer body, and an explicit closing line stating that **no pull request
was opened** and that a reply can trigger an implementation. Posted by the caller, not by the
strategy — `LoopOutcome`'s contract already says the caller performs the agent-specific final
action. The answer path also logs one line (issue number, answer length) for operators.

### 4.8 Prompt contract and nudge (prerequisite)

An answer path the model does not know about would not be used, so this ships in the same change:

1. `src/main/resources/prompts/native/issue-agent-tool-protocol.md` (classpath, CRLF) gains a
   **"When no code change is needed"** section: do not invent a change; do not call tools; reply
   with the complete final answer — it is posted as a comment and no PR is opened; if changes are
   needed, a prose-only reply is rejected and asked again.
2. The NATIVE nudge becomes `AgentPromptBuilder#buildNativeNoToolCallFeedback()`, carrying both
   exits explicitly. `buildMissingToolFeedback()` stays for LEGACY and keeps its JSON `runTools`
   example.

The contract text goes on the **classpath**, not into the DB-stored `system_prompts` row:
refreshing that row means a Flyway migration with a blunt `UPDATE`, which overwrites an
operator's edited prompt (the V10 precedent). Classpath changes need no migration and never touch
operator content. The text is LLM-facing, so it stays English (UI copy only is localized).

## 5. Behaviour by scenario

| scenario | before | after |
|----------|--------|-------|
| read-only question, model answers (the report) | 32 rounds, FAILED, answer invisible | 3 rounds, `ANSWERED`, answer posted |
| read-only question, model narrates before answering | nudge loop | nudge, then either answer or R4 fail |
| `MAX_TOKENS` truncation only | nudge loop | R4 fail, nothing posted |
| empty prose, no tool calls | nudge loop | R4 fail |
| implementation attempted, then prose, no diff | nudge loop | R4 fail (unchanged signal, bounded cost) |
| changes exist, then prose | PR | PR (unchanged) |
| follow-up comment on an issue with an open PR that needs no change | nudge loop | answer posted, status stays `PR_CREATED` |
| LEGACY mode | hard fail on unparseable text | unchanged |

## 6. Cost and bounds

Reported setup: 16K window, ~11.4K fixed per-round overhead (system prompt + tool descriptors +
repository tree in the first user message).

* Worst case for any read-only issue: 3 rounds, ~36K prompt tokens, instead of 32 rounds and
  ~370K tokens — and it succeeds instead of failing.
* The failure path (`R4`) is bounded to at most 2 rounds from the trigger, instead of 30.
* No new environment variable, no new budget knob, no change to `AgentBudget` arithmetic
  (`IssueImplementationService:315-320`).

## 7. Tests

`CodingAgentStrategyTest`

* rewrite `step_nativeTextOnlyTurnWithoutChanges_nudgesInsteadOfFailing` — it currently asserts
  only `Continue`; pin that the nudge names both exits.
* answer after the nudge -> `Finish` with an `AgentAnswer` payload carrying the prose.
* longest-turn-wins: a short follow-up does not replace a substantial earlier answer.
* prose rounds do not consume the retry budget (a real tool round after prose still executes).
* `MAX_TOKENS`-only run -> fail, nothing posted.
* blank prose after the nudge -> fail.
* implementation attempted, then prose, no diff -> fail (not an answer).

`IssueImplementationServiceTest`

* `handleIssueAssigned` answer path: comment posted, status `ANSWERED`, `commitAndPush` and
  `createPullRequest` never called, critic not consulted.
* `handleIssueComment` answer path with an existing PR: status stays `PR_CREATED`.
* `handleIssueAssigned_aiReturnsNoTools_postsFailure` (legacy) stays green.

`AgentPromptBuilderTest`: the native feedback names both exits; the legacy feedback is unchanged.

Migration gate test: `AgentSessionAnsweredMigrationTest` (see 4.6).

## 8. Non-goals

* **LEGACY mode.** The JSON contract has no answer shape; supporting it means a `finalAnswer`
  field on `ImplementationPlan`, a legacy protocol renderer change and a parser change. Legacy
  keeps its hard-fail; the gap is documented.
* **Triage/routing.** No new issue classification: the coding agent absorbs the read-only case,
  which is cheaper and safer than changing who gets assigned.
* Compaction, metrics and the remaining report follow-ups (see §10).

## 9. Trade-offs and risks

* An issue that genuinely needs work can now end as an answer comment if the model gives up after
  the nudge. Bounded by R3's "no implementation was ever attempted" gate: a run that tried still
  reports `FAILED`; a run that never tried reports what the model said. Monitoring that alerts on
  `FAILED` will therefore not fire for "model talked instead of working" — the comment and the
  session status are the signal.
* Every read-only task now costs one extra round (~12K prompt tokens locally). That is the price
  of distinguishing "let me think first" from an answer, and it replaces a 30-round burn.
* Answer quality is model-dependent. The longest-complete-turn rule prevents a vague one-liner
  from replacing a better earlier answer, but it cannot make a small local model answer well.

## 10. Follow-ups (not in this change)

* Validation nudge: `buildMissingValidationFeedback` still sends a local model hunting for a
  build tool it cannot find; the post-tool `Continue` paths (`:209`, `:241`, `:271`, `:274`) pass
  `null` as the follow-up text, so the model is never told *why* it is looping.
* LEGACY `finalAnswer` support (see §8).
* Allow an answer after no-op mutation rounds, instead of R4 failing.
* Advertised-schema footprint: ~11.4K fixed prompt tokens at a 16K window means the agent starts
  above the 70 % proactive-compaction threshold before doing any work.

## 11. Implementation order

1. `V52__agent_session_answered_status.sql` in `db/migration/h2/` and `.../postgresql/` +
   `AgentSessionAnsweredMigrationTest`.
2. `AgentSession.AgentSessionStatus.ANSWERED`.
3. `LoopOutcome.AgentAnswer` + `LoopOutcome.answered(...)`.
4. `prompts/native/issue-agent-tool-protocol.md` section +
   `AgentPromptBuilder#buildNativeNoToolCallFeedback` + `AgentPromptBuilderTest`.
5. `CodingAgentStrategy`: new state, branch rewrite (§4.1), `implementationAttempted` bookkeeping,
   budget guard.
6. `IssueNotificationService#postAnswerComment`.
7. `IssueImplementationService`: `ToolImplementationLoopResult`, both entry points.
8. Unit tests for 5-7.
9. Docs: `CHANGELOG.md` (Unreleased -> Fixed), `doc/AGENT.md` completion contract, status line of
   this document.

**Open at implementation time:** whether the README needs a capability change. Recommendation:
no — this is a behaviour fix of an existing workflow, so `CHANGELOG.md` + `doc/AGENT.md` only,
which avoids the four-language README mirroring. Confirm before touching `README.md`.
