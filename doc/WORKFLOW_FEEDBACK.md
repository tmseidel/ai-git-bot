# Feedback on agentic reviews

Agentic PR Review can record what reviewers think of its findings, so later work can
be judged against what the bot actually said. Feedback is text in Git comments — there
is no web UI, no separate database to administer, and no AI call involved in recording it.

This page covers recording, correcting, retracting and reading feedback on **agentic
review** output. Turning feedback into reusable guidance is described in
[Lessons for agentic reviews](WORKFLOW_LESSONS.md).

## What feedback applies to

Only **Agentic PR Review** (`agentic-review`) produces feedback targets. Normal code
review (`review`), the issue workflows, E2E/unit-test/README workflows and evaluation
records are not targets, and nothing you write about them is stored as feedback.

A target is always a reference the bot printed:

| Reference | Meaning |
|---|---|
| `O1234` | the complete review |
| `O1234-F2` | finding number 2 of that review |
| `O1234-A` | the review's overall assessment |

A review becomes a target only once it is **finalized**. Drafts, withdrawn findings and
reviews that were never completed are refused with a message.

## Turning it on

Feedback is collected per bot, through its workflow configuration:

**System settings → Workflow configurations → Workflows → Agentic PR Review → `feedbackEnabled`**.

It is off by default. With it off, reviews are produced exactly as before: a comment that
unmistakably names a target (`@bot feedback O1234-F1 incorrect`) is consumed and logged but
not recorded and not answered, while prose such as `@bot feedback please, is this approach
OK?` keeps whatever routing it had before feedback existed — a coding session, a
clarification, an ordinary command. The setting is part of the shared workflow
configuration: editing it affects every bot assigned to that configuration.

## Giving feedback

Mention the bot, the word `feedback`, the target, a verdict and a reason:

```text
@bot feedback O1234-F2 incorrect Authorization is already enforced in SettingsSecurityFilter.
@bot feedback O1234-F1 useful This caught a missing permission check.
@bot feedback O1234-F3 irrelevant The file is generated.
@bot feedback O1234-A needs-improvement Please explain the areas you did not review.
@bot feedback O1234 useful The review was useful overall.
```

Verdicts are `useful`, `incorrect`, `irrelevant` and `needs-improvement`. Only the first
line is the command; everything after it is the reason (up to 4 000 characters), so
pasting an explanation, a log snippet or a longer argument works. Commands inside quoted
lines (`> …`) and fenced code blocks are ignored, so quoting an example does not record it.

The command works in a pull-request conversation comment, an inline (diff) comment, and the
body of a submitted review on GitHub and Gitea. A submitted review is only recorded when the
provider identifies it: Gitea's review webhook carries no review id, so feedback written there
is refused with a message — post it as a comment instead.

The bot answers with a `FB…` reference for the record it created, so the feedback can be
quoted later:

```text
Recorded FB31 — O1234-F2 is incorrect (by alice).
```

Feedback **only records an assessment**. It never re-runs a review, changes code, edits
instructions, generates tests, or continues a coding session. Existing commands such as
"review again" keep their old meaning; a comment that carries a feedback command is
consumed as feedback and is not interpreted as anything else.

### Who may give feedback

The bot's configured `userWhitelist` applies. A permitted reviewer does not have to be the
pull-request author — reviewing someone else's change is the normal case. With no whitelist
configured, anyone who may comment on the repository may give feedback; that permission is
for collecting opinions only and never grants policy rights.

The actor is always the author of the authenticated comment — never the pull-request author.
A commenter who is not permitted gets no answer at all: the command is consumed and the
refusal is logged. A public "not permitted" reply would tell anyone excluded by the
whitelist exactly how to make the bot comment on demand.

## Correcting and retracting

A later comment from the same person about the same target replaces their earlier one. The
earlier record stays visible as *superseded*, so a change of mind is distinguishable from a
deletion, and conflicting assessments from different people coexist.

```text
@bot feedback retract FB31
```

Retraction is limited to the author of the record and to the bot's current learning
maintainers (see [Lessons](WORKFLOW_LESSONS.md)), and retracting something already
superseded is refused with a pointer to the record that replaced it. Lessons that cite
retracted or replaced feedback are flagged for a maintainer's review.

## Reading feedback

The admin panel's [Reviews page](USER_GUIDE.md#reviews) lists the feedback of every bot and
repository next to the reviews it judges. It can also retract feedback, which is recorded as
`admin:<user>` and posts nothing on the pull request. Its [Review quality page](USER_GUIDE.md#review-quality)
turns the verdicts into precision and feedback coverage per period, repository and category. On a
pull request:

```text
@bot feedback list                 # this pull request
@bot feedback list repository      # this repository, across pull requests
@bot feedback show FB31            # one stored record
```

Answers are rendered from the database — target, verdict, author, state, reason and
timestamp — with no model involved, so the same question always produces the same answer.
Each overview names the bot it belongs to: bots sharing one Git account answer the same
mention, and each lists only its own records. Where the provider's URL scheme is known
(Gitea, GitHub, GitLab, Bitbucket), entries link to their pull request and to the comment
that submitted them; Azure DevOps entries are plain text.

`feedback show` adds the record's history: when it was submitted, superseded (and by
which record) or retracted, and by whom — and names the lessons that cite it.

Repository-wide listing shows records from other pull requests but stays inside the same
bot and repository. Lists show 20 entries, newest first, and end with a line such as
`@bot feedback list page: MjAuNGU3…` when more exist. The cursor is opaque and belongs to
the listing that produced it: using it on another pull request, repository or bot, or
switching between `list` and `list repository`, is refused.

## Known limits

- **Only newly created comments are commands.** Editing an old comment does not re-run it,
  and deleting one does not retract it. Post a new comment instead.
- **One comment is one record.** Webhook redelivery of the same comment resolves to the
  stored record instead of creating a second one, including when two deliveries arrive at
  the same moment.
- **Confirmations are tracked, not guaranteed.** The bot records whether each confirmation
  (`Recorded FB…`, `Retracted FB…`) was posted. A redelivery posts it again only when the
  provider definitely refused the first one (a 4xx answer). After a timeout, a dropped
  connection or a server error the comment may already be on the pull request, so it is not
  repeated; the feedback itself is stored either way — check with `feedback show`.
- **A reference is not a permission.** `O1234-F2` resolves only inside the bot, Git host,
  repository and pull request it was created in. The same-looking reference from another
  repository is reported as unknown.
- **Feedback on closed pull requests stays valid** while the bot still selects
  `agentic-review` with collection enabled. Deselecting the workflow stops collection;
  existing records remain stored and readable once a valid selection is restored.
