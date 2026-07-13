# i18n Coverage

> Workflow key: **`i18n-coverage`** · Opt-in per bot.

## The problem it solves

Translations drift. A pull request adds a user-facing string, changes a message,
or removes a key in the primary locale — but the other locale files
(`messages_de.properties`, `i18n/fr.json`, …) are not updated in the same PR.
Coverage silently degrades until a user sees an untranslated key in production.
**i18n Coverage** watches each pull request for exactly this drift: it compares
every locale file against a configurable **baseline locale** and, when a
translation is missing keys the baseline defines (added or changed strings) or
still carries keys the baseline deleted, it drafts the missing translations per
locale and removes the stale keys — on the same PR that caused the change.

Changes it treats as relevant coverage gaps:

- newly added translation keys,
- changed translation values,
- deleted translation keys.

## What it does

1. Clones the PR head branch and scans the **whole checkout** for locale files
   matching your configured include patterns (not only the files the PR
   touched).
2. Groups the locale files into bundle families and, within each family,
   compares every non-baseline locale against the baseline's key set.
3. When gaps are found, an LLM agent drafts the missing translations into each
   affected locale and removes the stale keys, via the guarded `i18n-write` /
   `i18n-delete` tools.
4. Commits the changes (or opens a follow-up PR, or just reports them — your
   choice) and posts a short summary comment listing what changed. If every
   locale file is already in sync, it says so.

The workflow only ever touches **locale files (`*.properties` / `*.json`) that
match your configured patterns** — it never edits production code. Both
`messages_*.properties`-style resource bundles and `i18n/*.json`-style files
are supported.

## Settings

Set these on **System settings → Workflow configurations → Workflows → i18n
Coverage**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `includedFilePatterns` | text | `messages_*.properties, i18n/*.json, **/messages_*.properties, **/i18n/*.json` | Newline- or comma-separated glob patterns identifying the i18n locale files. These patterns define **both** which files the bot inspects **and** which files it may change. Only `*.properties` / `*.json` files are ever produced. |
| `baselineLocale` | string | *(blank)* | Optional reference locale whose keys/values/deletions the other locale files are compared against (e.g. `en`). Leave blank to auto-detect per bundle family: the implicit-default (suffix-less) file such as `messages.properties` is used when present, otherwise the first locale file. A blank value is saved as-is and does **not** revert to a default. |
| `maxToolRounds` | integer | `14` | How much work the bot may do to draft and apply translations (1–30). |
| `suiteLifecycle` | enum | `commit-to-pr` | What happens to the translation changes — see below. |

The include patterns and the baseline locale are the heart of this workflow: a
file is in scope only if it is a locale file **and** matches one of your
patterns. Globs support `**` (any number of path segments), `*` (within a
segment), and `?`. Examples: `messages_*.properties`, `i18n/*.json`.

### What happens to the changes

| Mode | What happens |
|---|---|
| `commit-to-pr` *(default)* | The translation changes are committed directly onto the PR branch. |
| `offer-as-pr` | A follow-up PR is opened with the translation changes, for separate review. |
| `ephemeral` | The proposed changes are reported in the comment but not committed. |

## Running it

- **Automatically** on PR open / update, when enabled on the bot — following the
  same assigned-bot triggering mechanism as the default review workflows.
- **On command** in a PR comment:
  - `@bot regenerate-i18n [instruction]` — re-run the workflow now. Any text
    after the command is passed to the bot as extra guidance for the generated
    translations (the detection logic is unchanged), e.g.
    `@bot regenerate-i18n Please use for the french translation the word "flâner"`.

## The translation prompt

The bot's role description is the operator-editable **i18n Coverage
System-Prompt** under **System settings → System prompts**. Edit it to steer how
the bot drafts translations (tone, terminology, formatting conventions). The
i18n-scope, baseline and locale-file-only rules are enforced by the software
regardless of the prompt, so editing it can never let the bot escape the
configured scope or modify the baseline.

## Enabling it

1. Open **System settings → Workflow configurations → Workflows**.
2. Tick **i18n Coverage** and set your i18n patterns and baseline locale.
3. Assign the configuration to the bot.

It only runs for bots that have it explicitly enabled.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [README Sync](PR_WORKFLOWS_README_SYNC.md) — keeps Markdown docs in sync; i18n
  Coverage keeps translations in sync.
