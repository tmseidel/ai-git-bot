# README Sync

> Workflow key: **`readme-sync`** · Opt-in per bot.

## The problem it solves

Documentation drifts. A pull request renames a flag, changes a setup step, or
removes a feature — and the README, quick-start, or docs still describe the old
behavior. Nobody notices until a new user follows stale instructions.
**README Sync** watches each pull request for exactly this drift and updates the
affected Markdown documentation so it stays consistent with the code, on the
same PR that caused the change.

Typical drift it fixes:

- quick-start or setup steps that no longer match current behaviour,
- outdated README examples or command invocations,
- newly required setup/usage steps that are missing,
- documentation sections describing removed functionality.

## What it does

1. Looks at the code the pull request changes and the documentation in scope.
2. Decides whether the change makes any docs inaccurate, incomplete, or
   outdated.
3. If so, updates, creates, or removes the affected **Markdown** files — within
   the documentation scope you configure.
4. Commits the changes (or opens a follow-up PR, or just reports them — your
   choice) and posts a short summary comment listing what changed. If nothing
   needed to change, it says so and why.

The workflow only ever touches **Markdown files that match your configured
documentation patterns** — it never edits production code, and never produces
non-Markdown files. Translated documentation variants (for example
`README.de.md`) are included automatically when your patterns cover them.

## Settings

Set these on **System settings → Workflow configurations → Workflows → README
Sync**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `includedFilePatterns` | text | `README.md, README.*.md, doc/**/*.md, docs/**/*.md` | Newline- or comma-separated glob patterns defining the documentation scope. These patterns define **both** which docs the bot reads **and** which files it is allowed to change. Only Markdown files are ever produced. |
| `maxToolRounds` | integer | `12` | How much work the bot may do to gather context and apply changes (1–30). |
| `suiteLifecycle` | enum | `commit-to-pr` | What happens to the documentation changes — see below. |

The include patterns are the heart of this workflow: a file is in scope only if
it is Markdown **and** matches one of your patterns. Globs support `**` (any
number of path segments), `*` (within a segment), and `?`. Examples:
`README.md`, `README.*.md`, `doc/**/*.md`.

### What happens to the changes

| Mode | What happens |
|---|---|
| `commit-to-pr` *(default)* | The documentation changes are committed directly onto the PR branch. |
| `offer-as-pr` | A follow-up PR is opened with the documentation changes, for separate review. |
| `ephemeral` | The proposed changes are reported in the comment but not committed. |

## Running it

- **Automatically** on PR open / update, when enabled on the bot.
- **On command** in a PR comment:
  - `@bot regenerate-readme [instruction]` — re-run the workflow now. Any text
    after the command is passed to the bot as extra guidance, e.g.
    `@bot regenerate-readme add an example showing how to run the docker container`.

## The sync prompt

The bot's role description is the operator-editable **README Sync
System-Prompt** under **System settings → System prompts**. Edit it to steer how
the bot decides what to update and how it writes documentation. The Markdown-only
and scope rules are enforced by the software regardless of the prompt, so editing
it can never let the bot escape the configured documentation scope.

## Enabling it

1. Open **System settings → Workflow configurations → Workflows**.
2. Tick **README Sync** and set your documentation patterns.
3. Assign the configuration to the bot.

It only runs for bots that have it explicitly enabled.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [PR Review](PR_WORKFLOWS_REVIEW.md) — reviews the code; README Sync keeps the
  docs about that code current.
