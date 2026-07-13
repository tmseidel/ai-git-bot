# Coding Agent: Issue → Pull Request

> Category: **ISSUE** · Bot type: **Coding bot** · Agent must be **Enabled**.

## The problem it solves

A well-described issue sits in the backlog — someone triaged it, wrote acceptance
criteria, and tagged it for implementation. But nobody has the time to pick it
up, or the fix is straightforward enough that hand-writing it feels like
toil. **Coding Agent** turns those issues into pull requests: it reads the
issue, clones the repository, implements the change, validates it with your
project's own toolchain, and opens a PR for human review.

## What it does

1. Reads the assigned issue and its discussion thread.
2. Clones the repository into an isolated workspace.
3. Explores the codebase to understand the affected areas — using ctags,
   ripgrep, tree inspection, and targeted file reads.
4. Implements the change by editing files, creating new ones, and deleting
   obsolete code as needed.
5. Runs your project's build and test toolchain to validate the change, retrying
   on failure within the configured budget.
6. Pushes a feature branch and opens a pull request.
7. Posts progress, validation output, and completion status as issue comments.

The agent is **read-write within its own workspace**: it can create, edit, and
delete files, run build commands, and push branches. It never auto-merges — a
human must still review and merge the resulting PR.

## Choosing the coding agent

Use the **coding agent** when the issue already describes:

- A clear implementation path,
- Acceptance criteria,
- Expected tests.

Unfocused, ambiguous, or contradictory issues should go to a
[Writer Agent](WRITER_AGENT.md) first.

## Settings

Common agent settings apply to both coding and writer agents. Coding-agent
specific knobs are listed below.

| Setting | Property | Default | What it controls |
|---|---|---|---|
| `agent.enabled` | `AGENT_ENABLED` | `true` | Global coding-agent feature toggle |
| `agent.branch-prefix` | `AGENT_BRANCH_PREFIX` | `ai-agent/` | Prefix for created branches (e.g. `ai-agent/issue-42`) |
| `agent.allowed-repos` | `AGENT_ALLOWED_REPOS` | empty = all | Comma-separated `owner/repo` allow-list |
| `agent.max-files` | `AGENT_MAX_FILES` | `20` | Maximum files the agent may modify |
| `agent.validation.enabled` | `AGENT_VALIDATION_ENABLED` | `true` | Require build/test validation before finishing |
| `agent.budget.max-rounds` | `AGENT_BUDGET_MAX_ROUNDS` | `20` | Maximum agent loop rounds |
| `agent.budget.max-validation-retries` | `AGENT_BUDGET_MAX_VALIDATION_RETRIES` | `10` | AI correction attempts after validation failure |
| `agent.context.max-tree-files` | `AGENT_CONTEXT_MAX_TREE_FILES` | `500` | Repository tree entries included in context |
| `agent.context.max-issue-comments` | `AGENT_CONTEXT_MAX_ISSUE_COMMENTS` | `50` | Issue comments included in context |

See the full table in [AGENT.md](AGENT.md#configuration-reference) for the
complete configuration reference including shared agent-budget, context, and
validation knobs.

## Running it

- **Automatically** when a coding bot is assigned to an issue and
  **Agent Enabled** is on.
- The bot posts a confirmation comment, then begins work. You'll see progress
  updates as it explores, edits, validates, and finally opens the PR or reports
  failure.
- A stuck session can often be resumed by commenting `try again`, `please
  retry`, or `redo` on the issue.

## Tool-calling mode

The coding agent uses the model's native tool-calling API when the AI
Integration has **Enable native tool calling** turned on. Turn it off only when
a provider/model behaves poorly in agentic workflows; this forces the legacy
JSON-in-prompt fallback. See [TOOL_CALLING.md](TOOL_CALLING.md).

## Branch naming

Coding-agent branches use the pattern:

```text
{agent.branch-prefix}issue-{issue-number}
```

For example: `ai-agent/issue-42`.

## Enabling it

1. Create an **AI Integration** and a **Git Integration** in the admin UI.
2. Create a bot with **Bot Type: Coding bot** and keep **Agent Enabled** on.
3. Configure your repository webhook to send **Issues** events to the bot URL.
4. Grant the bot repository write access (create branches, push commits, open
   pull requests) and issue write access (progress/error comments).

The bot begins work the next time someone assigns it to an issue.

## Limitations

- Coding works best for focused issues. Broad refactors and multi-system changes
  may be incomplete.
- Large repositories or long issue discussions may exceed the model context
  window; the bot sends bounded context.
- Validation can retry only within configured budgets. If retries are exhausted,
  the bot reports the failure instead of silently continuing.
- The agent can edit dependency files, but cannot guarantee external package
  availability or credentials.
- Small or heavily quantized local/ollama models may produce malformed plans,
  skip validation, or fabricate paths. Production use is recommended with
  Anthropic Claude or OpenAI GPT-4/5 class models.

## See also

- [Issue Agents overview](AGENT.md) — configuration reference and shared settings
- [Writer Agent](WRITER_AGENT.md) — the issue-refinement sibling
- [PR Workflows overview](PR_WORKFLOWS.md)
