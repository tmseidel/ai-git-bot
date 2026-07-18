# Implementation Plan: PR Reopen and Head-Update Webhook Actions

## Problem

The shared PR workflow can review an updated pull request, but the GitHub and
Gitea webhook handlers currently ignore native `reopened` and `synchronized`
actions. GitHub already normalizes `synchronize` to `synchronized` in
`GitHubWebhookHandler.mapAction()` (line 367), but neither handler routes that
action, and both ignore `reopened`.

## Goal

- `reopened` follows the same eligibility rules as `opened` on GitHub and Gitea.
- `synchronized` runs configured workflows only when a new per-bot boolean
  `runOnPrUpdate` (default `false`) is enabled.
- `runOnPrUpdate=false` is respected even when the bot is already a reviewer
  (reviewer membership does not bypass the update setting).
- `review_requested`, Gitea PR assignment, and `close` cleanup remain unchanged.
- GitLab and Bitbucket Cloud are intentionally out of scope.

## Architecture Context (traced)

### Dispatch chain

```
GitHub webhook ──→ GitHubWebhookHandler.handleWebhook()
                            │
                            ├── "pull_request" event
                            │       handlePullRequestEvent(bot, payload)
                            │       │  action="opened"      → reviewPullRequest (if runOnPrCreation || hasBotReviewer)
                            │       │  action="review_requested" → reviewPullRequest (if isRequestedReviewer)
                            │       │  action="closed"      → handlePrClosed
                            │       │  action="reopened"    → IGNORED (no case)
                            │       │  action="synchronized" → IGNORED (no case)
                            │       │  all others           → "ignored"
                            │
                            └── "issue_comment" / "pull_request_review" / etc.

Gitea webhook ──→ GiteaWebhookHandler.handleWebhook()
                            │
                            └── handleBotWebhookEvent(bot, payload)
                                    │  action="opened"          → reviewPullRequest (if runOnPrCreation || hasBotReviewer)
                                    │  action="assigned"        → reviewPullRequest (if isBotAssignee)
                                    │  action="review_requested" → reviewPullRequest (if isRequestedReviewer)
                                    │  action="closed"          → handlePrClosed
                                    │  action="reviewed"        → handleReviewSubmitted
                                    │  action="reopened"        → IGNORED (falls through)
                                    │  action="synchronized"    → IGNORED (falls through)
                                    │  all others               → "ignored"
```

The `runOnPrCreation` flag (Bot.java:81) gates the `opened` action in BOTH handlers
identically: `("opened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))`.

The new `runOnPrUpdate` flag mirrors this pattern — it is a per-bot toggle on the
Bot entity, persisted as a column, exposed in the bot form, and checked at the
webhook handler level. It is NOT a workflow parameter.

### Why not a workflow parameter?

`runOnPrCreation` is already an entity-level flag on `Bot`, not a workflow param.
`runOnPrUpdate` follows the same pattern — it's a per-bot deployment decision
("do I want AI usage on every push to this repo's PRs?"), not a per-workflow
tuning knob. Keeping it on `Bot` maintains consistency with the existing
trigger-condition model.

## Components to create / modify

- [ ] **Entity**: `Bot.java` — add `runOnPrUpdate` boolean field (default `false`)
- [ ] **Flyway migration (PostgreSQL)**: `V33__add_run_on_pr_update_to_bots.sql` — `ALTER TABLE bots ADD COLUMN IF NOT EXISTS run_on_pr_update BOOLEAN NOT NULL DEFAULT FALSE`
- [ ] **Flyway migration (H2)**: `V33__add_run_on_pr_update_to_bots.sql` — same DDL
- [ ] **GitHubWebhookHandler**: `handlePullRequestEvent()` — add `reopened` (alongside `opened`) and `synchronized` (gated on `bot.isRunOnPrUpdate()`)
- [ ] **GiteaWebhookHandler**: `handleBotWebhookEvent()` — add `reopened` (alongside `opened`) and `synchronized` (gated on `bot.isRunOnPrUpdate()`)
- [ ] **Bot form template**: `templates/bots/form.html` — add `runOnPrUpdate` checkbox below `runOnPrCreation`
- [ ] **Tests — GitHubWebhookHandlerTest**: update `pullRequestSynchronize_*` tests; add `reopened` tests
- [ ] **Tests — GiteaWebhookHandlerTest**: update `prSynchronized_*` tests; add `reopened` tests
- [ ] **Documentation**: `doc/PR_WORKFLOWS.md` — add `runOnPrUpdate` row to trigger-conditions table
- [ ] **README files**: README.md, README.ja.md, README.ko.md, README.zh.md — mention the new setting

## Sequence

### 1. Entity: `Bot.java`
Add field adjacent to `runOnPrCreation` (line 81):
```java
@Column(nullable = false)
private boolean runOnPrUpdate = false;
```
Lombok `@Data` auto-generates `isRunOnPrUpdate()` / `setRunOnPrUpdate(boolean)`.

### 2. Flyway migrations (V33)
Create identical SQL files in BOTH directories. Latest existing version is V32
(`system_prompts_i18n_coverage.sql` in postgresql; `enable_ctags_tools.sql` in h2 —
but h2 has V28..V32 that match postgresql).

**Check:** Verify h2 also has V29–V32 before writing V33.

```sql
ALTER TABLE bots ADD COLUMN IF NOT EXISTS run_on_pr_update BOOLEAN NOT NULL DEFAULT FALSE;
```

### 3. GitHubWebhookHandler.handlePullRequestEvent()
Change from:
```java
if (("opened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
        || ("review_requested".equals(action) && isRequestedReviewer(bot, payload))) {
    botWebhookService.reviewPullRequest(bot, payload);
    return ResponseEntity.ok("review triggered");
}
```
To:
```java
if (("opened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
        || ("reopened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
        || ("synchronized".equals(action) && bot.isRunOnPrUpdate())
        || ("review_requested".equals(action) && isRequestedReviewer(bot, payload))) {
    botWebhookService.reviewPullRequest(bot, payload);
    return ResponseEntity.ok("review triggered");
}
```

Key design decisions:
- `reopened` mirrors `opened` exactly — same gate (`runOnPrCreation || hasBotReviewer`).
- `synchronized` is gated SOLELY on `runOnPrUpdate`. Reviewer membership does NOT
  bypass it (per AC: "Reviewer membership does not bypass the update setting").
  This is intentional: being a reviewer means you review the PR's content, not that
  you want a re-review on every push (that's what the opt-in toggle is for).

### 4. GiteaWebhookHandler.handleBotWebhookEvent()
Same logic, inserted into the existing `if` block at line 298:
```java
if (("opened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
        || ("reopened".equals(action) && (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload)))
        || ("synchronized".equals(action) && bot.isRunOnPrUpdate())
        || ("assigned".equals(action) && isBotAssignee(bot, payload))
        || ("review_requested".equals(action) && isRequestedReviewer(bot, payload))) {
```

### 5. Bot form template
Add a new checkbox section below `runOnPrCreation` (after line 164):
```html
<div class="col-12">
    <div class="form-check form-switch">
        <input class="form-check-input" type="checkbox" id="runOnPrUpdate"
               th:field="*{runOnPrUpdate}"/>
        <label class="form-check-label" for="runOnPrUpdate">Run workflow when PR head is updated</label>
    </div>
    <div class="form-text">
        When enabled, the bot executes its configured PR workflow on every <code>synchronized</code> event (new commits pushed to the PR branch). Supported on GitHub and Gitea only. Existing installations default to off.
    </div>
</div>
```

### 6. Tests — GitHubWebhookHandlerTest

Existing tests that need updating:

| Test | Current expectation | New expectation |
|---|---|---|
| `pullRequestSynchronizedWithRunOnPrCreation_isStillIgnored` | ignored | **review triggered** (when `runOnPrUpdate=true`) |
| `pullRequestSynchronize_isIgnoredEvenWhenBotReviewer` | ignored | ignored (reviewer does not bypass update gate) |

New tests to add:
- `pullRequestReopenedWithRunOnPrCreation_triggersReview` — `bot.setRunOnPrCreation(true)`, action="reopened", no bot reviewer → review triggered
- `pullRequestReopenedWithBotReviewer_triggersReview` — action="reopened", bot is reviewer → review triggered
- `pullRequestReopenedWithoutBotReviewerOrRunOnPrCreation_isIgnored` — action="reopened", no reviewer, `runOnPrCreation=false` → ignored
- `pullRequestSynchronizedWithoutRunOnPrUpdate_isIgnored` — action="synchronized", `runOnPrUpdate=false` → ignored
- `pullRequestSynchronizedWithRunOnPrUpdate_triggersReview` — action="synchronized", `runOnPrUpdate=true` → review triggered
- `pullRequestSynchronizedWithRunOnPrUpdate_evenWithoutBotReviewer_triggersReview` — action="synchronized", `runOnPrUpdate=true`, no bot reviewer → review triggered

The existing `pullRequestSynchronizedWithRunOnPrCreation_isStillIgnored` test should
be renamed to `pullRequestSynchronizedWithRunOnPrUpdate_triggersReview` and changed
to set `runOnPrUpdate=true` (since `runOnPrCreation` doesn't control sync).

The existing `pullRequestSynchronize_isIgnoredEvenWhenBotReviewer` stays as-is
(reviewer does NOT bypass the update gate — add a comment noting this).

### 7. Tests — GiteaWebhookHandlerTest

Existing tests that need updating:

| Test | Current expectation | New expectation |
|---|---|---|
| `prSynchronizedEvent_routesToReviewPullRequest` | ignored | ignored (no `runOnPrUpdate`) |
| `prSynchronizedEvent_withRunOnPrCreation_isStillIgnored` | ignored | **review triggered** (when `runOnPrUpdate=true`) |

New tests to add:
- `prReopenedEvent_routesToReviewPullRequest` — action="reopened" (bot is reviewer by default in test fixtures) → review triggered
- `prReopenedEvent_withoutBotReviewer_isIgnored` — action="reopened", no reviewer → ignored
- `prReopenedEvent_withRunOnPrCreation_triggersReviewWithoutBotReviewer` — `runOnPrCreation=true`, action="reopened", no reviewer → review triggered
- `prSynchronizedEvent_withRunOnPrUpdate_triggersReview` — action="synchronized", `runOnPrUpdate=true` → review triggered

The existing `prSynchronizedEvent_routesToReviewPullRequest` stays but add a comment
that it tests the `runOnPrUpdate=false` default. The existing
`prSynchronizedEvent_withRunOnPrCreation_isStillIgnored` should be renamed to
`prSynchronizedEvent_withRunOnPrUpdate_triggersReview` and changed to set
`runOnPrUpdate=true`.

### 8. Documentation — doc/PR_WORKFLOWS.md

In the trigger-conditions table (section 3), add a row:

| Setting | Default | Effect |
|---|---|---|
| **Run workflow when PR head is updated** | Off | Runs configured workflows on every `synchronized` event (new commits pushed to the PR branch). GitHub and Gitea only. |

Also update the `runOnPrCreation` row to mention `reopened`:

| **Run workflow when PR is opened** | Off | Runs on every new or reopened PR, no reviewer request needed. |

### 9. README files
Add `runOnPrUpdate` to the feature list in all four README translations (README.md,
README.ja.md, README.ko.md, README.zh.md), mirroring the existing `runOnPrCreation`
entry. Place it directly after the `runOnPrCreation` line.

## Assumptions

- `synchronized` on Gitea uses the exact string `"synchronized"` (confirmed by the
  existing test `buildPrEventPayload("synchronized")` in GiteaWebhookHandlerTest).
- GitHub maps `"synchronize"` → `"synchronized"` via the existing `mapAction()` method.
  The handler already receives `"synchronized"` for GitHub sync events.
- No `RepositoryApiClient` changes needed — the payload already carries head ref/sha
  for both `reopened` and `synchronized` events.
- The `PrWorkflowRunLockManager` already handles back-to-back synchronize events
  (superseding in-flight runs), as documented in its class javadoc.
- `BotWebhookService.reviewPullRequest()` is already `@Async` and idempotent-safe
  (the orchestrator's `runAll` → `PrWorkflowRunService.start()` cancels superseded
  runs).

## Out of scope (GitLab and Bitbucket — detailed analysis)

### GitLab — already handles `reopened`, `update` needs discrimination

**Reopened: Already works.** When a GitLab MR is reopened, GitLab sends
`Merge Request Hook` with `action: "open"` (same event as a fresh MR). The
existing `open` case (line 88-95) maps this to `"opened"` and applies the
`runOnPrCreation || hasBotReviewer` gate — no code change needed.

**Synchronized (head update): Already routes but with wrong gate.** The `update`
case (lines 96-104) already maps to `"synchronized"` and calls `reviewPullRequest`.
However, it currently gates on `hasBotReviewer(bot, payload, changes)` — which
checks `changes.reviewers` (Was the bot reviewer just added?). This is a
"reviewer-just-added" trigger, not a "new-head-commit" trigger.

The problem: `update` fires on **every** MR change — description edits, title
changes, label updates, target branch switches — not just new commits. To safely
wire `runOnPrUpdate` here, we'd need an additional check like
`hasNewHeadCommit(raw)` that inspects `changes` for `oldrev` (GitLab includes
`changes.oldrev` when the source branch receives new commits):

```java
case "update" -> {
    webhookPayload.setAction("synchronized");
    Map<String, Object> changes = (Map<String, Object>) payload.get("changes");
    if (changes != null && hasBotReviewer(bot, payload, changes)) {
        botWebhookService.reviewPullRequest(bot, webhookPayload);
        yield ResponseEntity.ok("review triggered");
    }
    if (bot.isRunOnPrUpdate() && hasNewHeadCommit(payload)) {  // ← new check
        botWebhookService.reviewPullRequest(bot, webhookPayload);
        yield ResponseEntity.ok("review triggered");
    }
    yield ResponseEntity.ok("ignored");
}
```

The `hasNewHeadCommit(raw)` helper would need to verify against real GitLab
payloads — candidate fields are `changes.oldrev` or
`object_attributes.oldrev`. This is a small addition (~10 lines per handler) but
requires payload verification against a live GitLab instance.

### Bitbucket — already handles `reopened`, `pullrequest:updated` needs discrimination

**Reopened: Already works.** `pullrequest:open` (line 69-70) already routes
through `handlePullRequestOpenedOrUpdated` which uses
`runOnPrCreation || hasBotReviewer`. Same story as GitLab.

**Synchronized (head update): Already routes but with wrong gate.**
`pullrequest:updated` (line 69) maps to `"synchronized"` (line 129) and routes
through the same handler, where it currently falls into the `else` branch
(line 85-86): `botReviewerWasAdded(bot, raw)`. This is the same "reviewer-just-added"
pattern.

Same fix needed: add a `hasNewHeadCommit(raw)` check that inspects `changes` for
commit-related fields. Candidate: `changes.source.commit` or `changes.commits`.
The `botReviewerWasAdded` helper already navigates `raw.get("changes")` — the
new helper follows the same pattern.

```java
// BitbucketWebhookHandler.handlePullRequestOpenedOrUpdated
if (("pullrequest:created".equals(eventKey) || "pullrequest:open".equals(eventKey))
        ? (bot.isRunOnPrCreation() || hasBotReviewer(bot, payload))
        : botReviewerWasAdded(bot, raw)                                    // existing gate
                || (bot.isRunOnPrUpdate() && hasNewHeadCommit(raw))) {     // ← new gate
    botWebhookService.reviewPullRequest(bot, payload);
    return ResponseEntity.ok("review triggered");
}
```

### Why exclude from this change

Both providers need payload-level verification against live instances to confirm
the exact field names and shapes in the `changes` map. Guessing the field from
documentation risks a production blind spot. The GitHub/Gitea handlers are
unambiguous — `synchronized`/`synchronize` means exactly "new commits pushed" on
both platforms, with no metadata-noise ambiguity. GitLab/Bitbucket will be a
fast follow-up once the fields are confirmed against real webhook payloads.

### What doesn't change

- The `review_requested` action remains unchanged (explicit reviewer requests
  always trigger, regardless of `runOnPrUpdate`).
