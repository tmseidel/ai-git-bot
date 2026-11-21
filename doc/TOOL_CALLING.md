# Tool Calling Troubleshooting

This operator guide explains what to do when an AI provider or model has trouble calling tools in AI-Git-Bot agentic workflows. For the high-level coding/writer workflows, see [AGENT.md](AGENT.md). For MCP setup, see [MCP_SERVER_HANDLING.md](MCP_SERVER_HANDLING.md).

## TL;DR — “Something is broken, what do I do?”

Native function/tool calling is the default for new AI integrations and is recommended for frontier models. The older JSON-in-prompt path remains available as a fallback.

1. Open **AI Integrations → Edit** for the affected integration.
2. In **Tool calling**, switch **Enable native tool calling** **off**.
3. Re-run the failing operation, or comment `try again` on the issue/PR.
4. If it works, keep that integration on legacy mode and consider reporting the provider/model combination.

Legacy mode uses more tokens, but it is the most battle-tested fallback across providers, model versions, and self-hosted backends.

Before a run, **Test connection** on the AI integration form checks the values entered there without saving them. It sends a one-word prompt, which confirms DNS, proxies, firewalls, TLS, the API key and the model name. When native tool calling is on, it then offers the model one trivial tool and checks that the model calls it. A model that answers with text instead, or a server that rejects the request with tools, is a sign to turn native tool calling off for that integration.

## Why providers differ

Every provider defines tool calling differently: request format, response format, schema support, name restrictions, and how tool results are replayed in conversation history all vary. AI-Git-Bot translates its tool catalogue into the provider-specific shape and translates model responses back into executable tool requests.

| Provider | Operator notes |
|---|---|
| Anthropic Claude | Strict pairing between tool calls and tool results; invalid history can produce `400 tool_result.tool_use_id` errors. |
| OpenAI-compatible APIs | Tool arguments are usually JSON strings inside `tool_calls`; quality depends heavily on model capability. |
| Google Gemini | Tool/function names and Gemini 3.x thought-signature replay are stricter than many other providers. |
| Ollama | Native tool support depends on Ollama version and model template; some models ignore tools or produce weak calls. |
| llama.cpp | Always uses the legacy path; there is no native tool API for the bot to use. |

Conceptually, the bot keeps agent workflows provider-neutral: it offers a set of allowed tools to the model, executes allowed tool requests, records results, and asks the model to continue. If native provider tooling is unavailable or disabled, the same workflow is described in text and parsed from the model's JSON response instead.

## Fallbacks and safety nets

- **Automatic legacy fallback:** if a provider integration does not support native tools, or **Enable native tool calling** is off, the bot uses the legacy prompt-based path.
- **llama.cpp:** always stays on legacy mode.
- **History cleanup:** current releases drop obviously invalid historic tool-call/result pairs before retrying provider requests, and a replayed turn whose only content was its tool calls is sent as `[called <tools>]` instead of empty text — Anthropic and Gemini reject an empty message, which a native session replayed in legacy mode is full of.
- **Closing round:** the writer's answer round keeps its tool declarations; a model that ignores the wrap-up instruction and keeps calling tools ends on the *"I need more context"* comment rather than on a provider error.
- **Gemini compatibility:** current releases preserve Gemini 3.x metadata required for follow-up tool calls.
- **Validation guard:** when coding-agent validation is enabled and files were changed, the bot nudges the model to run a build/test command before finishing.
- **Writer fallback:** writer sessions can recover when a provider returns prose instead of the expected structured plan.
- **Schema monitoring:** malformed agent plans are logged/counted; with `AGENT_SCHEMA_ENFORCE=true`, invalid plans are rejected and retried.

Useful metrics, when Prometheus is enabled:

| Metric | Look for |
|---|---|
| `agent.tool_call.mode_total` | Unexpected legacy/native mode changes. |
| `agent.tool_call.parse_failures_total` | Models returning malformed tool calls. |
| `agent.tool_call.latency_seconds` | Native vs legacy latency differences. |
| `agent.plan.schema_violations_total` | Structured plan/schema issues. |

## What to do when things go wrong

### Step 0 — Comment “try again”

For coding and writer agents, post `try again`, `please retry`, or `redo` on the issue/PR. The model sees prior context and often corrects transient malformed arguments, missing fields, provider hiccups, or one-off bad decisions.

### Step 1 — Disable native tool calling

Open **AI Integrations → Edit → Tool calling** and turn **Enable native tool calling** off. This avoids provider-specific native API quirks and gives the model explicit tool instructions in the prompt every round.

Choose this when:

- native requests fail with provider validation errors,
- a local/self-hosted model ignores the tool catalogue,
- tool arguments are repeatedly malformed,
- a smaller model performs better with explicit prompt examples.

### Step 2 — Check logs, metrics, and comments

- Rising `agent.tool_call.parse_failures_total` means malformed tool responses.
- Provider `400` errors usually point to native history/schema/name restrictions.
- Issue/PR comments show visible validation errors and workflow progress.
- Application logs contain provider errors and any dropped invalid history entries.

### Step 3 — Inspect what the model tried

If the model calls tools nonsensically, read the issue/PR trace and validation comments. Confirm that the bot's **Tool Configuration** and MCP tool whitelist expose only tools you actually want the model to use.

### Step 4 — Restart the run

If retrying keeps replaying bad history, start fresh: resolve/reset the session in the admin UI if available, or re-trigger by reassigning the bot according to the workflow you are using.

### Step 5 — Open a bug report

Include:

- provider and model name/version,
- whether **Enable native tool calling** was on,
- raw provider error from logs,
- relevant issue/PR comment trace,
- `agent.tool_call.*` metrics around the failure window.

## “The model calls tools, but not sensibly”

Native tool calling guarantees structure, not judgment. Common model-quality failures include:

- editing before reading enough context,
- skipping or choosing the wrong validation command,
- patching text that does not exist,
- sending truncated MCP arguments,
- repeatedly issuing the same failed tool call,
- requesting tools not enabled in the bot's whitelist.

Better fixes than debugging the API:

1. Use a stronger model on the same provider.
2. Turn **Enable native tool calling** off so the model sees explicit legacy instructions.
3. Tighten the relevant prompt under **System settings → System prompts**.
4. Reduce exposed tools through **Tool Configuration** or MCP selections.

Smaller, older, or heavily quantized local models are much more likely to misuse tools. Frontier reasoning models generally validate before finishing and recover better from tool errors.

## Quick reference

| Situation | First action |
|---|---|
| One failed run, otherwise stable | Comment `try again`. |
| Repeated failures on one integration | Turn **Enable native tool calling** off. |
| Anthropic `400 tool_result.tool_use_id` | Upgrade to current release, then retry; use legacy mode if it persists. |
| Gemini `thought_signature` or invalid function-name errors | Upgrade to current release, then retry; use legacy mode if it persists. |
| MCP tools receive empty/truncated arguments | Upgrade to current release, verify MCP whitelist/config, then try legacy mode. |
| Model writes files but never builds | Confirm validation is enabled; try a stronger model or legacy mode. |
| Ollama ignores tools | Try a larger model, update Ollama, or use legacy mode. |
| llama.cpp ignores native toggle | Expected; it always uses legacy mode. |

## See also

- [AGENT.md](AGENT.md) — coding and writer agent setup.
- [MCP_SERVER_HANDLING.md](MCP_SERVER_HANDLING.md) — MCP discovery and whitelisting.
- [USER_GUIDE.md](USER_GUIDE.md) — UI walkthrough for integrations and prompts.
- [ARCHITECTURE.md](ARCHITECTURE.md) — developer-oriented internals.
