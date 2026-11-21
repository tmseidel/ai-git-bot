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
- Records its findings and an overall assessment through dedicated review tools;
  the bot persists them and renders one review comment in a fixed layout (see
  below). The model never writes the comment text itself.
- Optionally updates one progress comment with the latest activity,
  round and completed inspection tool-call counts, start time, and elapsed time.
- Optionally submits a formal review decision (approve / request changes) — see
  below.

The bot is **read-only**: this workflow never commits, pushes, creates branches,
or changes your code. Its outputs are the review comment and, when enabled, the
progress comment and formal review decision.

## Settings

Set these on **System settings → Workflow configurations → Workflows → Agentic
PR Review**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `maxToolRounds` | integer | `12` | How much exploring the bot may do (1–30). Higher means deeper analysis at higher cost. |
| `enableProgressComments` | boolean | `false` | Create and update one progress comment per review or clarification. Supported by GitHub, Gitea, GitLab, and Bitbucket Cloud. The *Latest activity* line names what each finished round did — files inspected, findings recorded, assessment set, review completed — and the counter covers every tool call, not just inspection. Updates are at least 15 seconds apart, except the first round that records tool calls and the final state, which are written immediately. |
| `enableFormalReviewDecision` | boolean | `false` | Let the bot approve or request changes, not just comment. The decision is computed from the stored findings and the thresholds below — it is never asked of the model. |
| `blockerThreshold` | integer | *(empty)* | Maximum allowed `BLOCKER` findings for a formal `APPROVE`. Empty means ignored. |
| `mediumThreshold` | integer | *(empty)* | Maximum allowed `MEDIUM` findings for a formal `APPROVE`. Empty means ignored. |
| `lowThreshold` | integer | *(empty)* | Maximum allowed `LOW` findings for a formal `APPROVE`. Empty means ignored. |
| `feedbackEnabled` | boolean | `false` | Accept `@bot feedback …` commands on this bot's reviews, and keep the evidence a review was produced from (task, diff, prompt, revisions) so that feedback can later be analysed against what the bot actually saw. With this off, feedback commands are consumed silently when they name a target and left to their ordinary routing otherwise, and no snapshot is kept; reviews are created identically. |

Progress comments show timestamps in UTC without fractional seconds. Review
decisions and findings remain in the separate final review.

The same setting applies to agentic clarification requests. Each request has
its own progress comment and publishes the final answer separately.
Progress comments are optional: creation or update failures are logged and do
not change the review or clarification outcome. If creation fails, that run
continues without progress. Activity write attempts are at least 15 seconds
apart, even after a failed update; terminal updates are attempted once immediately.

### The review comment

Every completed agentic review is rendered from the stored review, so the layout
is identical for every provider: a heading with the review reference (`O1234`),
a verdict line with the severity totals, the overall assessment (`O1234-A`),
one section per finding (`O1234-F1`, …) with title, source locations, evidence,
impact and recommendation, and a "Coverage and limitations" section. Headings
start at `#` so the comment keeps a usable outline on every provider.
A finding's reference is stable: correcting, withdrawing or re-sorting findings
never renumbers it. An inconclusive review says so prominently, and a review
without findings renders an explicit zero-findings section.

The comment ends with a configurable footer (Markdown), by default
`*Agentic review by [AI Git Bot](https://gitbot.eu)*`. Set
`REVIEW_COMMENT_FOOTER` (property `review.comment.footer`) to your own text, or
to an empty value to publish reviews without a footer.

The answer to a follow-up carries its own footer, by default
`*Agentic review follow-up by [AI Git Bot](https://gitbot.eu)*`, configured with
`REVIEW_COMMENT_CLARIFICATION_FOOTER` (property
`review.comment.clarification-footer`). It is separate because a follow-up is a
read-only answer rather than a review.

The model submits findings individually and sets the assessment once; the
comment is built from those records, not from a prose reply. A reply written as
text is not published.

## Asking about a finding

A follow-up (`@bot clarify …`, or a plain mention) answers questions about a
review that was already published. It starts without the earlier conversation,
so when you cite a reference such as `O1234-F1` the bot looks it up instead of
guessing: it reads the stored reviews of this pull request — list them, read one
target (`O1234`, `O1234-F1`, `O1234-A`), or search their findings by text. The
lookup only ever sees this bot's reviews on this pull request; references from
anywhere else do not resolve.

### Formal review decision

When `enableFormalReviewDecision` is on, the decision is a pure function of the
stored review: every finding carries a severity (`BLOCKER`, `MEDIUM`, `LOW`), the
application counts the persisted active findings, and compares each count to the
configured thresholds. There is no decision prompt and nothing to parse out of
the model's text:

- If **all configured thresholds** are satisfied, the bot submits `APPROVE`.
- If **any configured threshold** is exceeded, the bot submits `REQUEST_CHANGES`.
- If **no thresholds** are configured, the bot leaves the review state unchanged
  (`NONE`) while still posting the review text.
- An unset threshold is ignored entirely, so you can enforce only the severities
  that matter to you (for example, only `BLOCKER`).

The model cannot state an approval decision of its own, and the severity meaning
it classifies against lives in the `review-upsert-finding` tool schema, so it is
part of the tool contract rather than a free-text prompt. If the formal
submission is rejected by the Git provider, the bot falls back to posting the
same review as a plain comment, so the findings are never lost.

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
   decision** and set at least one severity threshold (for example, **Blocker
   must be less or equal = 0**). The decision is computed from the reported
   findings, so no prompt is involved.

It only runs for bots that have it explicitly enabled — it never replaces or
duplicates the default **PR Review** workflow.

## See also

- [Feedback on agentic reviews](WORKFLOW_FEEDBACK.md) — how reviewers judge findings, and what the bot stores
- [PR Workflows overview](PR_WORKFLOWS.md)
- [PR Review](PR_WORKFLOWS_REVIEW.md) — the simpler, always-on review.
