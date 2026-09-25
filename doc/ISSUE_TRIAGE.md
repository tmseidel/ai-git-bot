# Issue Triage

> The **Issue Triage** workflow (`issue-triage`) routes incoming issues to the right assignee — a person, a bot, or `none` — based on an editable routing prompt, posts the routing reason as a comment, and performs the assignment for you.

Where the [Coding Agent](CODING_AGENT.md) and [Writer Agent](WRITER_AGENT.md) workflows *work on* an issue, the triage workflow only **decides who should**. It is meant for initial triage of newly opened issues and never implements the issue itself.

---

## When it runs

The workflow is an [issue-assigned workflow](ISSUE_WORKFLOWS.md) and runs when either of these happens:

1. **An issue is opened** — only when the bot's **Run on issue creation** setting is enabled. The creation event is treated as a virtual assignment to the bot.
2. **An issue is assigned to a bot** whose issue-assigned workflow configuration enables `issue-triage`.

Follow-up comments on the issue are deliberately ignored by this workflow; conversation after triage belongs to the coding/writer workflows.

## Setting it up

1. Open **System settings → Issue-assigned workflow configurations**. A ready-made **Issue: Triage** configuration is seeded (non-default, opt-in); you can also create your own configuration and select the **Issue Triage** workflow.
2. Assign that configuration to a bot (Bot edit form → **Issue-Assigned Workflow Configuration**).
3. Enable **Run on issue creation** on the bot if new issues should be triaged automatically on creation.

### Parameters

| Parameter | Type | What it is |
|---|---|---|
| `systemPrompt` | long text | The routing instructions the model follows. Prefilled with a default triage prompt. |
| `assignees` | single-line text | Comma-separated account names the workflow may assign issues to. `none` is always allowed and never listed here. |

> **Review the defaults before going live.** The default prompt's roles and account names (`Alice`, `Bob`, `David`, `Josh`, `claude-bot`, `issue_hemingway`) are **examples**. Check the role descriptions, replace the account and bot names with your real ones, and make sure every listed account **exists and is assignable** in the connected Git provider. Keep the prompt's account list and the `assignees` parameter in sync — the workflow validates the model's choice against `assignees`, not against the prompt text.

The canonical clarification-bot name in the default prompt is **`issue_hemingway`**.

## How routing works

The workflow runs an **agent loop**: the model receives the issue title and body plus the repository file tree, and may gather more context on its own — reading files (`cat`, `tree`, `rg`, `ctags-signatures`, `git-log`, `git-blame`), looking up issues (`get-issue`, `search-issues`), and any enabled MCP tools — before it commits to a routing decision. All context tools are **read-only**; the only writes the workflow ever performs are the issue comment and the final assignment. The repository is cloned into a temporary read-only workspace for the duration of the run (no agent session is persisted).

When the model has gathered enough context it emits exactly one routing decision: an assignee and a one-line reason.

- **Tool-calling mode** — used when the bot's AI integration has native tool calling enabled and the provider supports it. Context tools are advertised alongside the terminal `assign_issue` tool (`name` + `reason`); the model must call it exactly once, in its own turn, for the final answer.
- **JSON-only mode** — used when native tool calling is unavailable or switched off (see [Tool Calling](TOOL_CALLING.md)). Context is requested via `requestFiles`/`requestTools` JSON envelopes; the final answer is a single `{"assignment": "...", "reason": "..."}` JSON object.

Both modes validate against the same allowed set (your `assignees` parameter plus `none`). A decision that fails validation (unknown assignee, empty or multi-line reason, self-assignment) is fed back to the model, which may correct itself; after the second invalid attempt the run fails. A model that never commits to a decision fails when the round budget is exhausted.

The number of context-gathering rounds and the size of the initial file tree are tunable via `agent.triage.max-tool-rounds` (default 5) and `agent.triage.max-initial-tree-files` (default 100) — in Docker deployments via the `AGENT_TRIAGE_MAX_TOOL_ROUNDS` and `AGENT_TRIAGE_MAX_INITIAL_TREE_FILES` environment variables (see the [configuration reference](AGENT.md#configuration-reference)).

## What you see on the issue

- **Routed to an account:** a comment with the one-line reason, then the issue is assigned to that account.
- **Routed to `none`:** a comment with the reason; the issue stays unassigned. Use this for unclear or contradictory issues.
- **Unassignable account:** if the chosen account does not exist, is not assignable on the repository, or the bot lacks permission, the issue is **not** assigned and an error comment explains the failure. The reason comment stays visible.
- **Invalid model output:** if the model returns malformed JSON, no/multiple tool calls, an unknown assignee, or an empty/multi-line reason, the issue is **not** assigned and an error comment is posted.

All failures are also recorded through the standard issue-workflow error handling: a bot error record in the admin UI and an `issueassignment.failed` outgoing webhook event.

Two safety rules are enforced regardless of the prompt:

- The workflow never assigns the issue **to the triage bot itself** (that would retrigger triage in a loop); such output is treated as invalid.
- The model output is treated as untrusted and is always validated before any assignment happens.

<img src="screenshots/issue-workflows/issue_triage.png" alt="Gitea issue with successful triage" width="800"/>

## Provider support

| Provider | Issue assignment |
|---|---|
| Gitea | Supported. Gitea silently ignores unknown assignees, so the bot re-reads the issue after assigning and reports a failure when the assignment did not take effect. |
| GitHub | Supported (`POST .../issues/{n}/assignees`; unknown or non-collaborator accounts are rejected by the API). |
| GitLab | Supported (resolves the username to a user id first; unknown users fail before any change). |
| Bitbucket | Not supported — routing to a real account ends in the error-comment path. |
| Azure DevOps | Not supported — Azure DevOps has Work Items rather than issues, so the issue workflows do not apply. |
