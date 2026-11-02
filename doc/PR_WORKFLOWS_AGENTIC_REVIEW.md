# Agentic PR Review

> Workflow key: **`agentic-review`** · Opt-in per bot.

## The problem it solves

A plain diff review sees only the changed lines. But the *right* review often
depends on code the diff doesn't show — the function being called, the test that
already covers this path, the convention used elsewhere in the module.
**Agentic PR Review** lets the bot explore the surrounding codebase before it
writes its review, so its feedback is grounded in how your project actually
works, not just the diff in isolation.

Use it instead of (or alongside) plain **PR Review** when you want deeper,
context-aware feedback and are willing to spend more time and tokens per review.

## What it does

- Reads the pull request and looks around the repository as needed before
  forming an opinion.
- Focuses on the files that matter first, so it handles large PRs without
  drowning in noise.
- Posts a single Markdown review comment with its findings.
- Optionally updates one progress comment with the latest activity,
  round and completed inspection tool-call counts, start time, and elapsed time.
- Withholds all formal actions until the revision, coverage and adapter gates
  are qualified — see below.

The bot is **read-only**: this workflow never commits, pushes, creates branches,
or changes your code. Its outputs are the review comment and, when enabled, the
progress comment; formal decisions are currently disabled.

## Settings

Set these on **System settings → Workflow configurations → Workflows → Agentic
PR Review**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `maxToolRounds` | integer | `12` | How much exploring the bot may do (1–30). Higher means deeper analysis at higher cost. |
| `enableProgressComments` | boolean | `false` | Create and update one progress comment per review or clarification. Supported by GitHub, Gitea, GitLab, and Bitbucket Cloud. Activity updates are at least 15 seconds apart; the final update is immediate. |
| `enableFormalReviewDecision` | boolean | `false` | Request severity classification for analysis. Formal actions remain `NONE`. |
| `formalReviewDecisionPrompt` | text | *(built-in default)* | Criteria the model uses to classify findings by severity. Only used when the decision is enabled. |
| `blockerThreshold` | integer | *(empty)* | Maximum allowed `BLOCKER` findings for a formal `APPROVE`. Empty means ignored. |
| `mediumThreshold` | integer | *(empty)* | Maximum allowed `MEDIUM` findings for a formal `APPROVE`. Empty means ignored. |
| `lowThreshold` | integer | *(empty)* | Maximum allowed `LOW` findings for a formal `APPROVE`. Empty means ignored. |

Progress comments show timestamps in UTC without fractional seconds. Review
decisions and findings remain in the separate final review.

The same setting applies to agentic clarification requests. Each request has
its own progress comment and publishes the final answer separately.
Progress comments are optional: creation or update failures are logged and do
not change the review or clarification outcome. If creation fails, that run
continues without progress. Activity write attempts are at least 15 seconds
apart, even after a failed update; terminal updates are attempted once immediately.

### Structured review state

The structured protocol stores bounded schema-1 review drafts on the workflow
run, bound to its captured head/base/diff revision. Updates use application-owned
versions and conditional writes; cancelled runs, stale versions and sealed
snapshots cannot be overwritten. Persistence failures stop the run before an
update is acknowledged. Snapshot JSON is parsed strictly but does not require
byte-identical reserialization.

**The new protocol cannot be activated through the workflow.** There is no UI
toggle; a persisted `structuredReviewShadow=true` selection fails before creating
an AI client or workspace, including clarification requests. It never falls back
to the publishing path. Ordinary configurations still select the existing
comment workflow, with the safety restrictions below.

Offline integration tests exercise the dormant, package-local shadow seam:

- `record_findings` applies validated add/remove operations and tracks unresolved
  `openQuestions`. Every acknowledged version is already persisted.
- Only `submit_review` can complete the structured run. `COMPLETE` requires a
  nonblank summary, explicit `complete: true`, and no open questions;
  `INSUFFICIENT_CONTEXT` preserves the diagnostic draft and fails the run.
- Native calls and legacy JSON use the same argument shapes. A legacy terminal
  response is `{"submit_review":{"result":"COMPLETE","summary":"...","complete":true}}`.
  Assistant prose and trailing classification JSON are never terminal results.
- Two logical turns are reserved for submission and one repair. An early submit
  also permanently stops inspection; a second terminal error fails immediately.
  These are logical-turn reserves, not a qualified provider-call or token-budget guarantee.
- A complete snapshot, terminal run state and required local audit are sealed
  in one transaction using the expected version and captured revision. Audit
  failure rolls back the seal. Its preview is rendered from the reloaded snapshot,
  never from assistant prose.
- The terminal run status is **SHADOW**, not `SUCCESS`. There are no PR comments,
  progress or retry comments, formal actions, finding events or review-success
  events. Retry notices are suppressed in a nested context, preserving the caller's notice.

Do not enable even SHADOW before the full terminal/transport/budget/read-only
contracts are qualified. Request-preservation, exact remote capture,
pending-evidence/coverage, execution fencing and publication/recovery remain
unfinished. No live-model canary, deployment or authoritative activation is
authorized by these offline tests or green CI.

### Formal review decision

Formal actions are currently withheld (`NONE`) until repository adapters prove
atomic binding to the complete reviewed revision and the completion/coverage
gates are qualified. Reviews are posted only as comments labelled with the
captured head SHA, base SHA and complete diff hash. Providers without a fresh
revision lookup fail before inference; a changed revision fails rather than
being recorded as a successful skipped review.

Outside shadow mode, the existing classification prompt/thresholds remain available
for analysis and finding-event compatibility, but the submitted formal action is
always `NONE`. Shadow mode does not send the classification prompt at all. A model's
completeness confirmation is necessary for a structured terminal snapshot; it is
never sufficient to authorize an approval without application-verified coverage
and adapter/revision gates. Failed publication is not retried as a plain comment.

## The review prompt

The bot's role description is the operator-editable **Review-Agent
System-Prompt** under **System settings → System prompts**. Edit it to steer the
review; the mechanics of *how* the bot explores are handled by the software and
are not part of this prompt, so editing it can't break anything.

## Enabling it

1. Open **System settings → Workflow configurations → Workflows** for the
   relevant configuration.
2. Tick **Agentic PR Review** and adjust its settings if you like.
3. Only the existing comment workflow is available. Structured SHADOW activation
   is blocked pending qualification. Formal decision settings cannot currently
   authorize APPROVE or REQUEST_CHANGES.

It only runs for bots that have it explicitly enabled — it never replaces or
duplicates the default **PR Review** workflow.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [PR Review](PR_WORKFLOWS_REVIEW.md) — the simpler, always-on review.
