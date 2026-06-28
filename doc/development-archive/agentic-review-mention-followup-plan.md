# Bugfix Plan: Agentic Review PR Workflow — Missing @mention Follow-up Support

**Date:** 2026-06-28 (revised 2026-06-28)
**Issue:** Agentic Review PR Workflow doesn't support conversational follow-ups via @bot mentions, inline comments, or review submissions — the simple PR Review workflow does.

---

## Root Cause Analysis

Two separate root causes compound to create this bug:

### Root Cause 1 — `BotWebhookService` only checks for `ReviewWorkflow.KEY` ("review")

In the PR comment dispatch chain (`handlePrComment`, `handleBotCommand`), after trying the E2E and unit-test slash command handlers, the fallback only checks `isWorkflowEnabled(bot, ReviewWorkflow.KEY)`. When a bot has only `"agentic-review"` enabled, the mention falls through to "unrecognized command."

In `handleInlineComment` and `handleReviewSubmitted`, the early-return guards also only check `ReviewWorkflow.KEY`.

### Root Cause 2 — No slash-command handler exists for the agentic review workflow

The E2E test workflow and unit-test workflow each have their own `SlashCommandHandler` beans (`E2eTestSlashCommandHandler`, `UnitTestSlashCommandHandler`) that are injected into `BotWebhookService` and given first crack at any @mention in `handlePrComment`/`handleBotCommand`. These handlers:

- Match a regex pattern (e.g. `@bot rerun-tests`, `@bot regenerate-tests <feedback>`)
- Check that their workflow is enabled on the bot's configuration
- Add an 👀 eyes reaction for acknowledgment
- Hydrate PR details if missing from the webhook payload
- Dispatch the workflow via `PrWorkflowOrchestrator.run(bot, payload, workflowKey, hints)`

The agentic review workflow has **no such handler**. And even if it did, `AgentReviewWorkflow.run()` does not check for hint-driven modes — it always runs the full review.

### Root Cause 3 — `AgentReviewWorkflow` has no conversational mode

Unlike `ReviewWorkflow` which uses a hint-based switch (`ACTION_REVIEW`, `ACTION_BOT_COMMAND`, `ACTION_INLINE_COMMAND`, `ACTION_REVIEW_SUBMITTED`, `ACTION_PR_CLOSED`), `AgentReviewWorkflow.run()` has a single code path: clone workspace, run agent loop, post review comment. There's no "answer a clarification question" variant.

---

## Design Decision

**Follow the established SlashCommandHandler pattern** — create an `AgentReviewSlashCommandHandler` that catches `@bot clarify <message>` (and any freeform `@bot <text>` as a fallback when no other handler matches), then re-triggers `AgentReviewWorkflow` in a new "conversational answer" mode driven by a hint.

**Rationale:**
- The `E2eTestSlashCommandHandler` / `UnitTestSlashCommandHandler` pattern is proven, consistent, and well-tested.
- The user explicitly wants consistency with how other agentic workflows handle interactions.
- It avoids coupling the agentic review to the simple `CodeReviewService` (which was the previous plan's approach, rejected by the user).
- Each workflow owns its own interaction surface — the handler is co-located in the same package.

**The slash command pattern:**
```
User writes: @bot clarify Why did you flag the null check in AuthFilter?
Handler matches: "^@\S+\s+clarify\b\s*(.*)$"
Handler checks: is agentic-review enabled on this bot?
Handler: adds 👀 reaction
Handler: dispatches orchestrator.run(bot, payload, "agentic-review", hints={"agentic-review.clarification": "Why did you flag..."})
Workflow: sees hint → runs conversational agent loop → posts answer as PR comment
```

**Fallback behavior:** When the bot has only `agentic-review` enabled and the user writes `@bot <freeform text>` that doesn't match `clarify` (or any other handler), we still need to respond. The simplest path: the `AgentReviewSlashCommandHandler` acts as a catch-all for any `@bot` message that no other handler consumed, interpreting it as a clarification request.

**Alternative considered and rejected:** Route conversational interactions through the simple `ReviewWorkflow`. Rejected because the user explicitly wants consistency with the agentic handler pattern, not delegation to the non-agentic path.

---

## Implementation Plan

### Goal
Add a `@bot clarify <message>` slash command to the agentic review workflow, following the exact pattern established by `E2eTestSlashCommandHandler` and `UnitTestSlashCommandHandler`, so users can have follow-up conversations after an agentic review.

### Components to create / modify

- [ ] **New: `AgentReviewSlashCommandHandler`** — detects `@bot clarify <message>`, dispatches to `AgentReviewWorkflow` with a hint
- [ ] **Modify: `PrWorkflowContext`** — add `HINT_AGENTIC_REVIEW_CLARIFICATION` constant
- [ ] **Modify: `AgentReviewWorkflow`** — check for the clarification hint, run conversational mode when present
- [ ] **Modify: `AgentReviewService`** — add `answerClarification(WebhookPayload, String userQuestion)` method that runs a conversational agent loop
- [ ] **Modify: `BotWebhookService`** — inject `AgentReviewSlashCommandHandler`, give it a `tryHandle()` slot in both `handlePrComment()` and `handleBotCommand()`

### Sequence

#### Step 1: Add hint constant to `PrWorkflowContext`

```
File: src/main/java/org/remus/giteabot/prworkflow/PrWorkflowContext.java
```

Add:
```java
/** Threaded by AgentReviewSlashCommandHandler for the @bot clarify <message> command. */
public static final String HINT_AGENTIC_REVIEW_CLARIFICATION = "agentic-review.clarification";
```

This follows the naming convention of `HINT_E2E_FEEDBACK` and `HINT_RERUN_ONLY`.

#### Step 2: Add `answerClarification()` to `AgentReviewService`

```
File: src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewService.java
```

New method:
```java
/**
 * Answers a clarification question about a previously-reviewed PR by running
 * a conversational agent loop.
 *
 * @param payload       the webhook payload (for PR identity)
 * @param userQuestion  the user's follow-up question
 * @return true when a non-empty answer was produced and posted
 */
public boolean answerClarification(WebhookPayload payload, String userQuestion) {
    // Same workspace clone as reviewPullRequest
    // Build user message: "The user asks a follow-up question about the PR you reviewed earlier:\n\n{question}\n\nPR context:\nTitle: {title}\nDiff:\n{diff}\n\nUse your read-only tools to inspect the code and answer the question."
    // Run the agent loop (same ReviewAgentStrategy — it already works for read-only exploration)
    // Post the answer as a regular PR comment (not a review comment): "## 🤖 Follow-up\n\n{answer}"
    // Clean up workspace
}
```

Key differences from `reviewPullRequest`:
- System prompt mentions this is a follow-up clarification, not a full review
- User message frames the question specifically
- Output is posted via `postPullRequestComment` (not `postReviewComment`) so it appears as a regular thread comment
- No `formatReview` wrapper — uses a lighter `## 🤖 Follow-up` header

#### Step 3: Add conversational mode to `AgentReviewWorkflow.run()`

```
File: src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewWorkflow.java
```

In `run()`, before the current logic, check for the clarification hint:

```java
@Override
public WorkflowResult run(PrWorkflowContext context) {
    Bot bot = context.bot();
    WebhookPayload payload = context.payload();

    // Check for conversational clarification first
    String clarification = context.hint(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION);
    if (clarification != null && !clarification.isBlank()) {
        return doClarification(context, clarification);
    }

    // ... existing review logic unchanged ...
}

private WorkflowResult doClarification(PrWorkflowContext context, String userQuestion) {
    Bot bot = context.bot();
    // Resolve params (only maxToolRounds is needed)
    // ...
    context.requireActive("before running agentic clarification");
    boolean answered = serviceFactory.create(bot)
            .answerClarification(context.payload(), userQuestion);
    context.appendStep("agentic-clarification",
            answered ? "Posted clarification response" : "Failed to produce clarification");
    return answered
            ? WorkflowResult.success("Clarification posted")
            : WorkflowResult.skipped("No clarification produced");
}
```

#### Step 4: Create `AgentReviewSlashCommandHandler`

```
New file: src/main/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewSlashCommandHandler.java
```

Follow the exact pattern of `E2eTestSlashCommandHandler`:

```java
@Service
@RequiredArgsConstructor
public class AgentReviewSlashCommandHandler {

    // Primary pattern: @bot clarify <message>
    private static final Pattern CLARIFY_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+clarify\\b\\s*(.*)$");

    // Fallback: any @bot <text> not caught by other handlers
    // (Only used when agentic-review is the sole REVIEW-category workflow enabled)
    private static final Pattern ANY_MENTION_PATTERN = Pattern.compile(
            "(?im)(?:^|\\s)@\\S+\\s+(.*)$");

    private final PrWorkflowOrchestrator orchestrator;
    private final WorkflowSelectionService selectionService;
    private final GiteaClientFactory repositoryClientFactory;

    public boolean tryHandle(Bot bot, WebhookPayload payload) {
        // 1. Guard: require comment body
        // 2. Match @bot clarify <message> — group 1 = the question
        // 3. If no clarify match, try fallback: @bot <anything> (group 1)
        // 4. Guard: is agentic-review enabled on bot?
        // 5. Add 👀 eyes reaction
        // 6. Hydrate PR details if missing
        // 7. Dispatch: orchestrator.run(bot, payload, AgentReviewWorkflow.KEY,
        //       Map.of(HINT_AGENTIC_REVIEW_CLARIFICATION, message))
        // 8. Return true (consumed)
    }
}
```

The `clarify` keyword is explicit and discoverable. Users who type `@bot <random text>` without `clarify` would still get a response if no other handler matched, via the fallback pattern — but only when agentic-review is enabled (the guard prevents it from consuming mentions intended for the review/unit-test/E2E workflows).

#### Step 5: Wire into `BotWebhookService`

```
File: src/main/java/org/remus/giteabot/admin/BotWebhookService.java
```

1. Add `AgentReviewSlashCommandHandler` as a constructor parameter and field.
2. In `handlePrComment()` — add `if (agentReviewSlashCommandHandler.tryHandle(bot, payload)) return;` before the existing `e2eTestSlashCommandHandler` check (or after it — order matters for precedence; put it after E2E and unit-test so those specific commands win).
3. In `handleBotCommand()` — same addition.

The handler injection follows the exact pattern of `e2eTestSlashCommandHandler` and `unitTestSlashCommandHandler`.

#### Step 6: Add unit tests

```
New file: src/test/java/org/remus/giteabot/prworkflow/agentreview/AgentReviewSlashCommandHandlerTest.java
```

All test cases following `E2eTestSlashCommandHandlerTest` structure:
- `dispatchesOnClarifyCommand()` — `@bot clarify Why is this unsafe?` → dispatches
- `dispatchesWithTrailingText()` — captures `"Why is this unsafe?"` as hint value
- `dispatchesOnClarifyCaseInsensitive()` — `@bot CLARIFY ...` works
- `dispatchesOnFallbackWhenNoOtherHandlerMatched()` — `@bot hello` dispatches with the full text
- `ignoresUnrelatedComment()` — text without @bot mention returns false
- `doesNotDispatchWhenAgenticReviewDisabled()` — returns false
- `ignoresWhenBotHasNoWorkflowConfiguration()` — returns false
- `ignoresPayloadWithoutComment()` — returns false
- `addsEyesReaction()` — verifies `addReaction("eyes")` call
- `reactionFailureDoesNotBlockDispatch()` — eyes fail, dispatch still happens

### Assumptions

- `AgentReviewSlashCommandHandler` is placed in the `agentreview` package alongside `AgentReviewWorkflow` for co-location, matching the pattern where `E2eTestSlashCommandHandler` lives in the `e2e` package.
- The `@bot clarify` verb is the primary interface. A fallback catch-all is included for any `@bot <text>` when no other handler consumed it, but only when agentic-review is enabled — this prevents a bot configured for E2E tests from accidentally routing `@bot rerun-tests` to the agentic review.
- The clarification response is posted as a regular PR comment (via `postPullRequestComment`), not a review comment — it's a conversational reply, not a formal review.
- `AgentReviewService.answerClarification()` reuses the existing `ReviewAgentStrategy` and agent loop infrastructure. No new strategy class needed.

### Verification

1. **Unit tests** (`AgentReviewSlashCommandHandlerTest`) cover pattern matching, dispatch, and guard conditions.
2. **Manual integration test:**
   - Configure a bot with only the "Agentic PR Review" workflow
   - Open a PR → agentic review is posted
   - Comment `@bot clarify Why did you flag the null check in AuthFilter?`
   - Expected: 👀 reaction appears immediately
   - Expected: bot clones workspace, inspects code, posts a conversational answer as a PR comment
3. **Manual integration test (fallback):**
   - Same bot, comment `@bot what about the error handling in line 42?`
   - Expected: bot still responds (fallback catch-all matches)

### Risks

- **Token cost:** Each clarification fires a full agent loop (clone + LLM + tool calls). For a conversational answer this is heavyweight compared to the simple `CodeReviewService` chat. Mitigation: the agent loop budget is already capped by `maxToolRounds` (default 12, user-configurable), and the user explicitly asked for the agentic approach.
- **No conversation memory across clarifications:** Unlike `CodeReviewService` which persists a `ReviewSession` across turns, the agentic clarification is stateless — each `@bot clarify` starts fresh. Mitigation: this is consistent with how `@bot rerun-tests` works (each invocation is independent). If multi-turn memory is needed later, it can be added as a separate feature.
