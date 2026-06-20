# Implementation Plan: AgentSession Object-State Cleanup

## Goal

Clean up the read/write lifecycle of `AgentSession` so that ownership of the
JPA entity is unambiguous, transactions are scoped correctly, and the
`AgentSessionService` is no longer forced into a dual-mutation workaround.

---

## Problem Inventory

### P1 — Dual-mutation pattern in `AgentSessionService`

Every write method (`setStatus`, `setBranchName`, `setPrNumber`, `recordPlan`,
`recordTokenUsage`, `addMessage`) mutates **both** the caller's detached
session object **and** a freshly-fetched managed proxy via
`getReferenceById()`. This exists because:

- Callers load the session in Transaction A (e.g. `createSession`,
  `claimSessionForUpdate`), which commits and detaches the entity.
- Callers then call `sessionService.setXxx(session, value)` which opens
  Transaction B. Inside B, the passed `session` is detached — calling
  `repository.save(session)` would trigger `merge()` which traverses the
  lazy `messages` collection and may fail with `ObjectNotFoundException`
  if a prior compaction deleted messages.
- The workaround: mutate the caller's object (so subsequent reads see the
  new value) AND mutate a managed proxy (so the change is persisted via
  dirty checking).

**Consequence:** Every setter does double work, the return contract is
inconsistent (some methods return the caller's object, `compactContextWindow`
returns the managed entity), and the whole pattern is a band-aid for missing
transaction boundaries in the callers.

### P2 — No transaction boundary around the orchestration methods

`IssueImplementationService.handleIssueAssigned`,
`IssueImplementationService.handleIssueComment`,
`WriterAgentService.handleIssueAssigned`,
`WriterAgentService.handleIssueComment`, and
`AgentReviewService.reviewPullRequest` have **no `@Transactional`**
annotation. Each `sessionService.setXxx()` call opens its own
micro-transaction. A typical `handleIssueAssigned` flow opens:

| # | Call | Transaction |
|---|------|-------------|
| 1 | `createSession` | T1 (INSERT) |
| 2 | `setStatus(FAILED)` or `setStatus(UPDATING)` | T2 (UPDATE) |
| 3 | `setBranchName` | T3 (UPDATE) |
| 4 | `setPrNumber` | T4 (UPDATE) |
| 5 | N × `addMessage` (inside AgentLoop) | T5…TN+5 |
| 6 | `setStatus(PR_CREATED)` | TN+6 |

If the JVM crashes between T1 and T6, the session is stuck in `IN_PROGRESS`
forever. The individual micro-transactions also prevent atomic status
transitions (e.g., setBranchName + setStatus should be one unit).

### P3 — AgentLoop opens N micro-transactions

`AgentLoop.run()` calls `sessionService.addMessage(ctx.session(), ...)` at
least 5 times per round (initial user message, assistant response, tool
results, follow-up user messages). With a 10-round loop, that's **50+
independent transactions**, each doing a `getReferenceById()` + dual-mutation.

This is wasteful (connection pool churn) and creates a window where a crash
mid-loop loses all messages persisted so far in the current round.

### P4 — AgentReviewService creates a DB session for a read-only review

`AgentReviewService.reviewPullRequest()` calls `getOrCreateSession()` at
line 122 which either reads or **creates** an `AgentSession` row. The review
agent is strictly read-only — it posts a comment, never creates a PR or
commits code. The session is only used as a conversation-history carrier
through the `AgentLoop`. Persisting it to the database:

- Creates an unnecessary row in `agent_sessions`
- Opens a transaction window that spans the entire review (workspace clone
  + AI calls)
- Pollutes the session table with review sessions that serve no audit purpose

### P5 — `compactContextWindow` returns managed entity but callers keep stale reference

`compactContextWindow()` deletes messages via `orphanRemoval` and returns the
managed entity. But callers like `IssueImplementationService.handleIssueComment`
(line 373) and `WriterAgentService.handleIssueComment` (line 207) ignore the
return value and continue using the original `session` variable. The original
session's `messages` collection now contains references to deleted rows.

This is the root cause of the `ObjectNotFoundException` that was recently
fixed in commit 71632d7 — but the fix was applied to `AgentSessionService`
(dual-mutation workaround) rather than to the callers.

### P6 — Unclear dependency graph in AgentLoop construction

`AgentLoop` receives `sessionService` in its constructor. `AgentRunContext`
carries the `session`. `AgentRunContext` is passed as a parameter to
`AgentLoop.run()`. This creates a confusing triangle:

```
AgentLoop ──has──> AgentSessionService
    ▲                   │
    │                   │ addMessage(session, ...)
    │                   ▼
AgentRunContext ──has──> AgentSession
    ▲
    │
    └── passed to ── AgentLoop.run(ctx, ...)
```

The loop reaches through `ctx.session()` to call `sessionService.addMessage()`.
It's unclear whether the loop or the caller owns the session's persistence.

---

## Architecture Decision Record

### ADR-1: Transaction granularity for AgentLoop runs

**Status:** Proposed

**Context**
Should the entire `AgentLoop.run()` execute within a single `@Transactional`
boundary?

**Options Considered**

1. **Single transaction over the entire loop** — wrap `run()` in
   `@Transactional`
   - ✅ One transaction, atomic commit, no dual-mutation needed
   - ❌ AI calls take 10–60+ seconds each; a 10-round loop holds a DB
     connection and transaction for 2–10 minutes
   - ❌ Connection pool exhaustion under concurrent webhooks
   - ❌ Long transactions cause lock contention on the `agent_sessions` row
   - ❌ If the AI call hangs, the transaction blocks indefinitely

2. **Batch-flush pattern** — AgentLoop accumulates messages in memory,
   flushes them in batches (e.g., after each round or every N messages)
   - ✅ Fewer transactions (one per round instead of 5+ per round)
   - ✅ Messages are persisted incrementally (crash loses only current round)
   - ❌ More complex — needs a buffer and flush trigger
   - ❌ Still needs the dual-mutation workaround or managed-entity rebinding

3. **In-memory loop with pre/post flush** — AgentLoop works purely in
   memory. The caller persists the initial state before the loop and the
   final state after. Messages are batch-persisted at the end.
   - ✅ Cleanest separation of concerns
   - ✅ No transactions during AI calls (no connection held)
   - ❌ Crash mid-loop loses all messages from that run
   - ❌ Token usage tracking won't be persisted incrementally

**Decision**
We choose **Option 2 (batch-flush)** because:
- It avoids holding DB connections during AI calls (ruling out Option 1)
- It preserves incremental persistence for crash recovery (ruling out Option 3)
- The batch boundary is naturally "end of each round" — after the AI
  response and tool execution complete, flush all messages from that round
  in a single transaction

**Consequences**
- `AgentLoop` needs a `flushMessages(session, List<Message>)` method on
  the session service that persists all pending messages in one transaction
- The per-round flush also persists token usage, so `recordTokenUsage`
  is folded into the same transaction
- The dual-mutation workaround is no longer needed for `addMessage` since
  messages are batch-added to the in-memory session and flushed together

### ADR-2: AgentReviewService session persistence

**Status:** Proposed

**Context**
Should the read-only review agent persist a session to the database?

**Options Considered**

1. **Keep persisting** — current behavior
   - ✅ Consistent with other agent flows
   - ✅ Conversation history survives for debugging
   - ❌ Unnecessary DB rows for read-only reviews
   - ❌ Transaction window spans the entire review

2. **In-memory-only session** — create a transient `AgentSession` object
   (not saved to DB) and pass it through the loop
   - ✅ No DB overhead for read-only operations
   - ✅ No transaction window
   - ❌ Conversation history is lost after the review (acceptable — the
     review text is posted as a PR comment)
   - ❌ `AgentLoop` currently calls `sessionService.addMessage()` which
     tries to persist — needs to handle null/unsaved sessions

3. **Optional persistence** — add a flag to `AgentRunContext` or
   `AgentLoop` that disables DB persistence
   - ✅ Flexible — could be used for other ephemeral agent runs
   - ❌ Adds complexity to the loop

**Decision**
We choose **Option 2 (in-memory-only)** because:
- The review agent never needs to resume a session across webhook events
- The review output is fully captured in the posted PR comment
- It eliminates the unnecessary `agent_sessions` row and the transaction
  window

**Consequences**
- `AgentLoop` must tolerate a session without an `id` (transient entity)
- `sessionService.addMessage()` and `recordTokenUsage()` must short-circuit
  when the session has no `id` (i.e., is not persisted)
- `AgentReviewService` creates a plain `new AgentSession(...)` without
  calling `sessionService.createSession()`

### ADR-3: Eliminating the dual-mutation pattern

**Status:** Proposed

**Context**
How do we eliminate the dual-mutation workaround in `AgentSessionService`?

**Options Considered**

1. **Always return managed entity** — every write method fetches a managed
   proxy, mutates it, and returns it. Callers must rebind.
   - ✅ Single source of truth — the managed entity
   - ✅ No `merge()` risk — we never save a detached entity
   - ❌ Requires updating all ~50 call sites to rebind
   - ❌ Callers that read fields after the call must use the return value

2. **Detach-and-reattach** — callers pass the session ID, not the entity.
   The service loads, mutates, and returns.
   - ✅ Impossible to hold a stale reference
   - ❌ Every call does a SELECT + UPDATE (extra round-trip)
   - ❌ Large API change — every caller needs the ID, not the object

**Decision**
We choose **Option 1 (always return managed entity)** because:
- It's the standard JPA pattern (the skill document already recommends it)
- The batch-flush pattern (ADR-1) reduces the number of call sites that
  need rebinding
- Callers that only write (e.g., `setStatus(FAILED)` in catch blocks)
  don't need to rebind — they don't read the session afterward

**Consequences**
- All `AgentSessionService` write methods return the managed entity
- Callers must `session = sessionService.setXxx(session, value)` for any
  session they continue to use
- The dual-mutation code (mutate caller's object + managed proxy) is
  removed — only the managed proxy is mutated
- `addMessage` is replaced by `flushMessages(sessionId, List<PendingMessage>)`
  which is called once per round

---

## Components to Create / Modify

- [ ] **Modify** `AgentSessionService`: remove dual-mutation, introduce
      `flushMessages()`, make all setters return managed entity
- [ ] **Modify** `AgentLoop`: batch messages per round, flush once per
      round, remove `sessionService` constructor dependency (or make it
      optional for transient sessions)
- [ ] **Modify** `AgentRunContext`: add `persistent` flag or derive it
      from `session.getId() == null`
- [ ] **Modify** `IssueImplementationService.handleIssueAssigned`: wrap
      pre-loop state changes in a single service call, rebind session
      after each write
- [ ] **Modify** `IssueImplementationService.handleIssueComment`: rebind
      session after `compactContextWindow`, wrap post-loop writes
- [ ] **Modify** `WriterAgentService.handleIssueAssigned`: same as above
- [ ] **Modify** `WriterAgentService.handleIssueComment`: same as above
- [ ] **Modify** `AgentReviewService.reviewPullRequest`: use transient
      session, remove `getOrCreateSession()`
- [ ] **Modify** `CodingAgentStrategy`: rebind session after
      `recordPlan` calls
- [ ] **Modify** `WriterAgentStrategy`: rebind session after
      `setStatus`/`setBranchName` calls
- [ ] **Modify** `TokenUsageTracker`: fold token persistence into the
      per-round flush, handle transient sessions

---

## Sequence

### Phase 1: Clean up `AgentSessionService` return contracts (low risk)

1. Change all write methods to **only** mutate the managed proxy (remove
   the `session.setXxx(value)` lines that mutate the caller's object).
2. Change all write methods to return the managed entity.
3. `addMessage` stays as-is for now (Phase 2 replaces it).
4. `recordTokenUsage` changes return type from `void` to `AgentSession`.

### Phase 2: Introduce batch-flush for AgentLoop (medium risk)

5. Add `PendingMessage` record to `AgentLoop` (role + content).
6. AgentLoop accumulates `PendingMessage` objects in a `List` during each
   round instead of calling `sessionService.addMessage()` immediately.
7. At the end of each round (after `strategy.step()`), call a new
   `sessionService.flushMessages(sessionId, List<PendingMessage>, long totalInputTokens, long totalOutputTokens)`
   that persists all messages and token usage in a single `@Transactional`.
8. `flushMessages` fetches the managed entity by ID, adds all messages to
   its collection, updates token counts, and returns the managed entity.
9. AgentLoop rebinds `ctx`'s session reference to the returned managed
   entity (or: `AgentRunContext` gets a `setSession()` method for rebinding).

### Phase 3: Fix caller transaction boundaries (medium risk)

10. In `IssueImplementationService.handleIssueAssigned`: rebind `session`
    after every `sessionService.setXxx()` call.
11. In `IssueImplementationService.handleIssueComment`: rebind `session`
    after `compactContextWindow()` — this is critical to fix the stale
    reference problem (P5).
12. In `WriterAgentService.handleIssueAssigned`: rebind `session` after
    every `sessionService.setXxx()` call.
13. In `WriterAgentService.handleIssueComment`: rebind `session` after
    `compactContextWindow()`.
14. In `CodingAgentStrategy`: rebind `ctx.session()` after `recordPlan()`.
15. In `WriterAgentStrategy`: rebind `ctx.session()` after `setStatus()`
    and `setBranchName()`.

### Phase 4: AgentReviewService transient session (low risk)

16. Replace `getOrCreateSession()` with `new AgentSession(owner, repo, prNumber, prTitle)`.
    The transient session has `id = null`.
17. `AgentLoop` checks `ctx.session().getId() == null` before calling
    `sessionService.flushMessages()` — if null, skip persistence.
18. `TokenUsageTracker.record()` already null-guards on `session == null`;
    extend the guard to also skip when `session.getId() == null`.

### Phase 5: Verify and harden (low risk)

19. Run full test suite — the dual-mutation removal will break tests that
    assert on the caller's object after a service call. Fix by using the
    return value or adjusting mock setup.
20. Add integration test: verify that after `compactContextWindow`, the
    caller's session reference is the managed entity (same object identity).
21. Verify no `ObjectNotFoundException` in the agent loop after compaction.

---

## Detailed Design

### `AgentSessionService` — new and changed methods

```java
// REMOVED: addMessage(AgentSession, String, String)
// REPLACED BY:

/**
 * Persists a batch of messages and updated token counts in a single
 * transaction. Returns the managed entity.
 */
@Transactional
public AgentSession flushMessages(Long sessionId,
                                  List<PendingMessage> messages,
                                  long totalInputTokens,
                                  long totalOutputTokens) {
    AgentSession managed = repository.getReferenceById(sessionId);
    for (PendingMessage msg : messages) {
        managed.addMessage(msg.role(), msg.content());
    }
    managed.setTotalInputTokens(totalInputTokens);
    managed.setTotalOutputTokens(totalOutputTokens);
    return managed; // dirty checking flushes on commit
}

/**
 * Setter methods — single contract: mutate managed proxy, return it.
 */
@Transactional
public AgentSession setStatus(Long sessionId, AgentSessionStatus status) {
    AgentSession managed = repository.getReferenceById(sessionId);
    managed.setStatus(status);
    return managed;
}

// Same pattern for setBranchName, setPrNumber, recordPlan, etc.
// Note: parameter changes from AgentSession to Long sessionId to
// eliminate any temptation to use the caller's detached object.
```

**Key change:** Write methods take `Long sessionId` instead of
`AgentSession session`. This makes it impossible to accidentally pass a
detached entity and forces callers to think about which session they're
updating by ID.

### `AgentLoop` — batch message accumulation

```java
public LoopOutcome run(AgentRunContext ctx, String initialUserMessage, AgentStrategy strategy) {
    // ... setup ...
    List<PendingMessage> pendingMessages = new ArrayList<>();
    pendingMessages.add(new PendingMessage("user", initialUserMessage));

    for (int round = 1; round <= budget.maxRounds(); round++) {
        // ... AI call ...
        pendingMessages.add(new PendingMessage("assistant", aiResponse));

        // ... strategy.step() ...

        // Flush at end of round (if session is persisted)
        if (ctx.session().getId() != null) {
            tokenTracker.record(ctx.session(), turn, promptChars);
            AgentSession managed = sessionService.flushMessages(
                ctx.session().getId(),
                pendingMessages,
                ctx.session().getTotalInputTokens(),
                ctx.session().getTotalOutputTokens());
            ctx.setSession(managed); // rebind
        }
        pendingMessages.clear();

        // ... continue with next round ...
    }
}

record PendingMessage(String role, String content) {}
```

### `AgentRunContext` — add rebind capability

```java
public final class AgentRunContext {
    @Setter  // already exists for baseBranch
    private AgentSession session;
    // ... rest unchanged ...
}
```

### Callers — rebind pattern

```java
// Before:
sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
// After:
session = sessionService.setStatus(session.getId(), AgentSession.AgentSessionStatus.FAILED);

// Before (critical fix):
sessionService.compactContextWindow(session);
// After:
session = sessionService.compactContextWindow(session);
// session is now the managed entity with up-to-date messages collection
```

---

## Assumptions

1. The `AgentSession.messages` collection uses `Set<ConversationMessage>`
   with `orphanRemoval = true`. Adding messages via `managed.addMessage()`
   within a transaction correctly cascades inserts.
2. `getReferenceById()` returns a lazy proxy — it does NOT hit the database
   until a property is accessed. Setting a scalar field on the proxy does
   not trigger a SELECT.
3. The `@Async` annotation on `BotWebhookService.handleIssueAssigned` means
   each agent run gets its own thread. Transaction boundaries within the
   async method are independent of the webhook handler's thread.
4. The in-memory `history` list in `AgentLoop` is the authoritative
   conversation context for the AI calls. The persisted `messages`
   collection is only for cross-session continuity (follow-up comments).
5. Token usage tracking can tolerate a one-round delay in persistence
   (flushed at end of round, not after each AI call).

---

## Risk Assessment

| Phase | Risk | Mitigation |
|-------|------|------------|
| Phase 1 | Test breakage (callers assert on old object) | Fix tests to use return value |
| Phase 2 | Messages lost on crash mid-round | Acceptable — current pattern also loses the current round's messages if the JVM crashes during an AI call |
| Phase 3 | Missed rebind site causes stale reference | Use `execute_code` to search ALL call sites mechanically |
| Phase 4 | Transient session leaks if `getId()` check is missed | Unit test with null-ID session |
| Phase 5 | Integration test gaps | Add targeted test for post-compaction rebinding |

---

## Open Questions

1. **Should `claimSessionForUpdate` still eagerly load messages?**
   Currently line 81 calls `savedSession.getMessages().size()` to
   force-initialize the collection. With the batch-flush pattern, the
   loop reads messages via `sessionService.toAiMessages(session)` which
   accesses the collection. If `claimSessionForUpdate` returns a managed
   entity in a committed transaction, the collection is detached and
   accessing it triggers a lazy load (which works, but outside a
   transaction). Should we keep the eager load or rely on lazy loading?

Answer: keep the eager load for now

2. **Should `compactContextWindow` take a `Long sessionId` instead of
   `AgentSession`?**
   It already fetches a managed entity internally. Taking an ID would
   make the contract consistent with the other methods and eliminate
   the temptation to pass a detached entity.

Answer: Yes, change to `Long sessionId` and return the managed entity.

3. **Should we introduce a `SessionLifecycleService` that wraps the
   pre-loop / loop / post-loop transitions?**
   This could enforce the "set UPDATING → run loop → set PR_CREATED"
   sequence as a single method, reducing the chance of missed status
   transitions. However, it may be over-engineering for three call sites.

Answer: No, keep the current pattern but ensure callers rebind after each write.
