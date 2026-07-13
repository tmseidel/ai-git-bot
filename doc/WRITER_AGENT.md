# Writer Agent: Issue → Better Issue

> Category: **ISSUE** · Bot type: **Writer bot**.

## The problem it solves

Issues arrive in all shapes: one-liners without context, vague feature requests,
bug reports that don't say what the expected behavior is. Someone has to ask
questions, chase down details, and restructure the issue so it's actually
implementable — work that bounces between triager and reporter, stretching a
fast fix into a multi-day discussion. **Writer Agent** takes on that refinement:
it reads the issue, asks the original author clarifying questions when needed,
and creates a structured, actionable replacement issue.

## What it does

1. Reads the assigned issue and its discussion thread.
2. Gathers repository context — project structure, conventions, existing
   patterns — to ground its questions in reality.
3. Evaluates the issue for clarity, completeness, and testability:
   - Is the problem statement unambiguous?
   - Are acceptance criteria present and measurable?
   - Is the scope defined?
   - Are edge cases addressed?
4. When the issue is ready, creates a new linked issue titled
   **AI Created Issue: \<original title\>** with:
   - Background
   - Requirements
   - Acceptance criteria
   - Implementation notes
5. When the issue needs more input, posts clarifying questions as a comment
   addressed to the original author and waits for a reply.
6. Posts visible progress and completion comments on the original issue.

The writer bot is **read-only for the repository**: it can explore code and
documentation for context, but it never creates branches, edits files, or opens
pull requests. Its only side effects are comments and the new improved issue.

## Choosing the writer agent

Use the **writer agent** when an issue is:

- Ambiguous or contradictory,
- Too broad or missing scope,
- Lacking testable outcomes,
- Missing acceptance criteria.

Well-structured issues that are ready to implement should go to a
[Coding Agent](CODING_AGENT.md) instead.

The writer bot will not start on an issue that already has an active
coding-agent session.

## Settings

Writer-specific settings share the `agent.budget.*` namespace with the coding
agent. The writer agent is simpler — it only uses read-only tools and issue
mutations — so its configuration surface is smaller.

| Setting | Property | Default | What it controls |
|---|---|---|---|
| `agent.budget.max-rounds` | `AGENT_BUDGET_MAX_ROUNDS` | `20` | Maximum agent loop rounds |
| `agent.budget.max-context-rounds` | `AGENT_BUDGET_MAX_CONTEXT_ROUNDS` | `10` | Maximum context-only rounds |
| `agent.budget.max-tokens-per-call` | `AGENT_BUDGET_MAX_TOKENS_PER_CALL` | `16384` | Token budget per AI call |
| `agent.budget.max-history-chars` | `AGENT_BUDGET_MAX_HISTORY_CHARS` | `180000` | Maximum characters for agent conversation history |
| `agent.budget.max-tool-result-chars` | `AGENT_BUDGET_MAX_TOOL_RESULT_CHARS` | `8000` | Maximum characters for tool execution results |
| `agent.max-file-content-chars` | `AGENT_MAX_FILE_CONTENT_CHARS` | `100000` | Max file-content characters included in prompts |

See the full table in [AGENT.md](AGENT.md#configuration-reference) for the
complete configuration reference.

## Running it

- **Automatically** when a writer bot is assigned to an issue.
- The bot posts a confirmation comment, then either asks clarifying questions or
  creates the improved issue.
- When the bot is waiting for the author to answer questions, only the original
  reporter's reply continues the workflow — comments from other users do not
  advance it.
- A stuck session can often be resumed by commenting `try again`, `please
  retry`, or `redo` on the issue.

## Tool-calling mode

The writer agent uses the model's native tool-calling API when the AI
Integration has **Enable native tool calling** turned on. Turn it off only when
a provider/model behaves poorly in agentic workflows; this forces the legacy
JSON-in-prompt fallback. See [TOOL_CALLING.md](TOOL_CALLING.md).

## Enabling it

1. Create an **AI Integration** and a **Git Integration** in the admin UI.
2. Create a bot with **Bot Type: Writer bot**.
3. Configure your repository webhook to send **Issues** events to the bot URL.
4. Grant the bot repository read/clone access for context, and issue write
   access to post comments and create the improved issue.

The bot begins work the next time someone assigns it to an issue.

## Limitations

- Writer sessions that need missing business context wait for the original issue
  author; the bot cannot proceed until it has enough information.
- Large repositories or long issue discussions may exceed the model context
  window; the bot sends bounded context.
- Small or heavily quantized local/ollama models may ask low-quality questions
  or produce poorly structured output. Production use is recommended with
  Anthropic Claude or OpenAI GPT-4/5 class models.

## See also

- [Issue Agents overview](AGENT.md) — configuration reference and shared settings
- [Coding Agent](CODING_AGENT.md) — the implementation sibling
- [PR Workflows overview](PR_WORKFLOWS.md)
