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
- Optionally submits a formal review decision (approve / request changes) — see
  below.

The bot is **read-only**: this workflow never commits, pushes, creates branches,
or changes your code. Its only outputs are the review comment and, when you
enable it, the formal review decision.

## Settings

Set these on **System settings → Workflow configurations → Workflows → Agentic
PR Review**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `maxToolRounds` | integer | `12` | How much exploring the bot may do (1–30). Higher means deeper analysis at higher cost. |
| `enableFormalReviewDecision` | boolean | `false` | Let the bot approve or request changes, not just comment. |
| `formalReviewDecisionPrompt` | text | *(built-in default)* | Criteria the model uses to classify findings by severity. Only used when the decision is enabled. |
| `blockerThreshold` | integer | *(empty)* | Maximum allowed `BLOCKER` findings for a formal `APPROVE`. Empty means ignored. |
| `mediumThreshold` | integer | *(empty)* | Maximum allowed `MEDIUM` findings for a formal `APPROVE`. Empty means ignored. |
| `lowThreshold` | integer | *(empty)* | Maximum allowed `LOW` findings for a formal `APPROVE`. Empty means ignored. |

### Formal review decision

When `enableFormalReviewDecision` is on, the model classifies its findings as
`BLOCKER`, `MEDIUM`, or `LOW` and emits a JSON object such as
`{"blocker": 0, "medium": 1, "low": 2}`. The application then compares each
count to the configured thresholds:

- If **all configured thresholds** are satisfied, the bot submits `APPROVE`.
- If **any configured threshold** is exceeded, the bot submits `REQUEST_CHANGES`.
- If **no thresholds** are configured, the bot leaves the review state unchanged
  (`NONE`) while still posting the review text.
- An unset threshold is ignored entirely, so you can enforce only the severities
  that matter to you (for example, only `BLOCKER`).

If the model fails to emit a clean classification, the bot falls back to posting
a plain review comment so the findings are never lost.

## The review prompt

The bot's role description is the operator-editable **Review-Agent
System-Prompt** under **System settings → System prompts**. Edit it to steer the
review; the mechanics of *how* the bot explores are handled by the software and
are not part of this prompt, so editing it can't break anything.

## Enabling it

1. Open **System settings → Workflow configurations → Workflows** for the
   relevant configuration.
2. Tick **Agentic PR Review** and adjust its settings if you like.
3. To let the bot approve / request changes, tick **Enable formal review
   decision**, optionally customise the decision prompt, and set at least one
   severity threshold (for example, **Blocker must be less or equal = 0**).

It only runs for bots that have it explicitly enabled — it never replaces or
duplicates the default **PR Review** workflow.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [PR Review](PR_WORKFLOWS_REVIEW.md) — the simpler, always-on review.
