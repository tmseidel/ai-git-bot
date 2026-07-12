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
| `formalReviewDecisionPrompt` | text | *(built-in default)* | Your criteria for when to approve, request changes, or leave the review state unchanged. Only used when the decision is enabled. |

### Formal review decision

When `enableFormalReviewDecision` is on, the bot ends its review with a
structured decision and submits it together with the review body as a single
review. Valid decisions are **approve**, **request changes**, or **leave
unchanged**. Your `formalReviewDecisionPrompt` defines the criteria; if the bot
can't produce a clean decision, it falls back to posting a plain comment so the
findings are never lost.

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
   decision** and optionally customise the decision prompt.

It only runs for bots that have it explicitly enabled — it never replaces or
duplicates the default **PR Review** workflow.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [PR Review](PR_WORKFLOWS_REVIEW.md) — the simpler, always-on review.
