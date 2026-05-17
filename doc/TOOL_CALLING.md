# Knowledge Base: Tool Calling Across Providers

This document is the operator- and developer-facing knowledge base for
**how AI-Git-Bot lets language models invoke tools** (file edits, build
runners, MCP tools, repository inspection, …). It explains why the
implementation looks the way it does, which abstractions exist, which
safety nets are in place, and — most importantly — **what to do when a
provider misbehaves**.

If you are looking for the high-level agent workflows, start with
[AGENT.md](AGENT.md). If you are looking for MCP-specific setup, see
[MCP_SERVER_HANDLING.md](MCP_SERVER_HANDLING.md). Everything below applies
on top of those.

---

## TL;DR — “Something is broken, what do I do?”

> **New defaults**: Since the current release, **legacy tool calling is
> the default** for every newly created AI integration, and existing
> integrations are migrated to legacy as well. Native function calling
> is opt-in via the **"Enable experimental native tool calling"**
> switch in the integration editor. The recovery playbook below applies
> whenever you have opted in and run into trouble.

1. Open **AI Integrations → Edit** for the affected integration.
2. Switch **"Enable experimental native tool calling"** to **off**
   (which sets the underlying `use_legacy_tool_calling=true`).
3. Re-run the failing operation (or comment `try again` on the issue/PR).
4. If it now works, you are done. Native tool calling for that
   model/provider combination is currently unreliable; please open an
   issue with the provider/model name and a short log excerpt.

The legacy path uses more tokens than native function calling, but it is
the most thoroughly tested code path in this project and has proven
extremely robust across providers, model versions, and self-hosted
backends.

---

## 1. Why provider tool-call APIs look so different

Every major LLM provider has invented its own contract for "tool /
function calling". They differ in transport, schema, request/response
shape, validation rules, and lifecycle. There is **no common standard**
the project can simply plug into, so each client has to be implemented
from scratch.

| Provider | Wire format on request | Wire format on response | Notable quirks |
|---|---|---|---|
| **Anthropic (Claude)** | `tools: [{ name, description, input_schema }]` on the top-level request | Assistant message with `content` blocks of type `tool_use` (each carries `id`, `name`, `input`) | Strict 1:1 pairing — every `tool_use` block **must** be followed by a `tool_result` block with the matching `tool_use_id` in the next user turn, otherwise the API returns `400 tool_result.tool_use_id: Field required`. Tool names restricted to `[a-zA-Z0-9_-]`. |
| **OpenAI (Chat Completions)** | `tools: [{ type: "function", function: { name, description, parameters } }]` | Assistant message with `tool_calls: [{ id, function: { name, arguments } }]`; `arguments` is a **string** containing JSON | The tool result is a separate message with `role: "tool"` and `tool_call_id`. Names also restricted to `[a-zA-Z0-9_-]`. |
| **Google Gemini** | `tools: [{ functionDeclarations: [{ name, description, parameters }] }]` | `candidates[].content.parts[].functionCall: { name, args }` | Gemini **rejects** function names containing more than one `:`. Gemini 3.x adds a mandatory `thoughtSignature` per `functionCall` that must be echoed verbatim in the follow-up request, otherwise `400 BAD_REQUEST: "Function call is missing a thought_signature in functionCall parts"`. Tool results go back as `functionResponse` parts. |
| **Ollama** | OpenAI-compatible `tools: [...]` on `/api/chat` | OpenAI-compatible `tool_calls` (without provider-generated IDs in older builds) | Native tool support depends on the underlying model template and the Ollama version. Some models silently ignore the `tools` field. |
| **llama.cpp** | n/a — uses `/completion` with a **GBNF grammar** | Plain completion text constrained by grammar | No native function-calling concept. Always runs in legacy mode regardless of the integration toggle. |

Differences are not only cosmetic:

- **Schema dialects**: Anthropic and Gemini use JSON-Schema-like shapes
  but disagree on which fields are mandatory and how enums or
  `additionalProperties` are interpreted. OpenAI accepts a subset.
- **History replay**: Each provider has its own rules about how prior
  assistant tool calls and the matching tool results must be re-sent on
  every subsequent request. A history that is valid for OpenAI is
  rejected by Anthropic, and vice versa.
- **Identifier handling**: Anthropic generates tool-call IDs server-side
  and demands they round-trip. OpenAI does the same with different
  prefixes. Gemini does not use IDs at all and identifies pending calls
  positionally. Ollama’s older builds emit no ID, so the client
  synthesises one.
- **Validation strictness**: A name like `mcp:github:issue_read` is
  perfectly valid internally, accepted by OpenAI and Anthropic, but
  rejected outright by Gemini.

The bottom line: a "tool" is a different object on every provider, and
the project has to translate between a single internal model and four
different wire dialects on every round-trip.

---

## 2. The abstraction layer

To keep the agent code provider-agnostic, AI-Git-Bot funnels everything
through a small, deliberately narrow internal API.

### 2.1 The `AiClient.chatWithTools` contract

```
AiClient.chatWithTools(history, message, tools, systemPrompt, modelOverride, maxTokens)
        -> ChatTurn(assistantText, List<ToolCall>, StopReason)
```

- **Inputs** are normalised: `tools` is a list of provider-neutral
  [`ToolDescriptor`](../src/main/java/org/remus/giteabot/ai/ToolDescriptor.java)
  objects (name, description, JSON-Schema parameters). The client is
  responsible for translating them into the provider’s wire format.
- **Outputs** are normalised: a `ChatTurn` always exposes assistant text,
  a list of structured `ToolCall`s (`id`, `name`, `JsonNode args`,
  optional `providerMetadata`) and a `StopReason`. Callers never see
  Anthropic-style `tool_use` blocks or Gemini `functionCall` parts.
- **Default implementation** delegates to the textual `chat(...)` API, so
  any client without native support behaves identically to the legacy
  path without extra code.

### 2.2 `ToolDescriptor`, `ToolCall`, `ChatTurn`

These are the only types the `AgentLoop` and the agent strategies know
about. They carry no provider-specific fields. Vendor-specific
state that must round-trip (such as Gemini 3.x `thoughtSignature`) is
hidden inside an opaque `providerMetadata` map on `ToolCall` so that
strategies do not need to learn about it.

### 2.3 `AgentLoop` + `AgentStrategy` + `ToolingMode`

The generic `AgentLoop` owns chat/history/session mechanics for **all**
agents. The agent-specific decision logic lives in an `AgentStrategy`
with three opt-in hooks:

- `preferredToolMode()` — `LEGACY` (default) or `NATIVE`.
- `toolDescriptors()` — the tools the model may invoke this round.
- `step(ctx, ChatTurn, round)` — receives a structured turn; default
  delegates to the textual `step(...)` so legacy strategies keep working.

`AgentLoop` runs in `NATIVE` mode **only when all three conditions hold**:

1. The strategy asks for `NATIVE`.
2. The configured `AiClient.supportsNativeTools()` returns `true`.
3. At least one `ToolDescriptor` is supplied.

Otherwise it transparently falls back to the legacy text path. Operators
get a per-integration override (the `use_legacy_tool_calling` flag,
**defaulting to `true` since the current release** so that legacy mode
is in effect unless opt-in) that forces step 2 to `false` regardless of
model defaults. The admin UI exposes the inverse switch as "Enable
experimental native tool calling" so the positive label matches the
opt-in semantics.

### 2.4 `ToolNameSanitizer` — one safe name for every provider

MCP tools are exposed system-wide as `mcp:<server>:<tool>` (e.g.
`mcp:github:issue_read`). Because Gemini rejects multi-colon names and
Anthropic/OpenAI restrict names to `[a-zA-Z0-9_-]`, every native client
funnels names through
[`ToolNameSanitizer`](../src/main/java/org/remus/giteabot/ai/ToolNameSanitizer.java):

- `sanitize(...)` — used when **sending** to the provider (declarations,
  replayed `tool_use`/`tool_call`/`functionCall` parts, matching tool
  results). It substitutes `:` with `__`.
- `desanitize(...)` — used when **reading** the response back, so the
  rest of the system (dispatcher, comments, persistence) keeps seeing
  the canonical MCP-style name.

The substitution is reversible and stays inside the strictest
provider’s character class.

### 2.5 MCP argument round-trip

Built-in tools have known schemas (`path`, `branch`, `content`, …) so the
agent originally serialised them as a positional `List<String>`. MCP
tools have **arbitrary** schemas (`{"owner": "...", "repo": "...",
"state": "open", …}`). The strategies (`CodingAgentStrategy`,
`WriterAgentStrategy`) therefore detect MCP tools via
`McpTools.looksLikeMcpTool(call.name())` and forward the **entire**
argument object as a single JSON string, which `McpOrchestrationService`
unwraps back into a `Map<String, Object>`. This is what keeps unknown
fields from being silently dropped.

---

## 3. Fallbacks and safety nets

The codebase intentionally assumes providers will misbehave and layers
several defensive nets on top of the native code path.

### 3.1 Automatic legacy fallback

- If `AiClient.supportsNativeTools()` is `false`, or the integration
  toggle `use_legacy_tool_calling=true`, the `AgentLoop` never calls
  `chatWithTools` and silently uses the textual `chat(...)` path.
- For llama.cpp this is permanent — there is no native API to fall back
  from.

### 3.2 History sanitisation (Anthropic)

`AnthropicAiClient.reconcileToolCallPairs()` enforces the strict 1:1
mapping Anthropic requires when replaying history:

- drop tool messages without `toolCallId`;
- drop `tool_use` blocks without `id`;
- remove orphan `tool_use` without a matching `tool_result` in the next
  user turn;
- remove `tool_result` whose `tool_use_id` is missing in the preceding
  assistant turn;
- drop empty assistant or user turns left behind by the cleanup;
- log every dropped element at `WARN` so corrupted histories remain
  observable.

This prevents `400 tool_result.tool_use_id: Field required` errors when
a pre-1.1 session is replayed against the native API.

### 3.3 Session-level tool-message filtering

`AgentSessionService` filters obviously invalid tool messages (no
`toolCallId`, no matching prior tool call) before rebuilding the AI
history, so a corrupted DB row cannot poison every subsequent request.

### 3.4 Gemini 3.x `thoughtSignature` round-trip

`GoogleAiClient` captures the per-`functionCall` `thoughtSignature`
into `ToolCall.providerMetadata` on the way in and replays it verbatim
on every subsequent request. Sessions started against Gemini 2.x simply
leave the metadata `null` and remain unaffected.

### 3.5 Validation-before-finish guard

`CodingAgentStrategy.step` refuses to mark a run finished when:

- `validation.enabled = true`,
- the workspace was mutated (files written or patched),
- but no validation/build tool was invoked this round.

In that case the strategy returns
`ContinueWithToolResults(buildMissingValidationFeedback())` which
instructs the model to run `mvn` / `gradle` / `npm` / `dotnet` / … on the
freshly written code. The attempt counter is incremented so this cannot
loop forever.

### 3.6 Writer fallback for unstructured responses

`WriterAgentStrategy` falls back from native to textual parsing when a
provider returns plain prose instead of a structured plan, while making
sure the assistant message is not posted twice in the resulting comment.

### 3.7 JSON-Schema validation for plans

`AiResponseParser` validates the parsed plan against
`prompts/schema/coding-plan.schema.json` (and the writer equivalent).
The Micrometer counter
`agent.plan.schema_violations_total{agent=coding|writer}` exposes how
often violations occur. With `AGENT_SCHEMA_ENFORCE=true` invalid plans
are rejected and the model is asked to retry, which catches malformed
tool-call envelopes early.

### 3.8 Telemetry

Three Micrometer meters under `/actuator/prometheus` make tool-calling
problems visible without reading logs:

| Metric | Tags | Use it to detect |
|---|---|---|
| `agent.tool_call.mode_total` | `mode={native,legacy}`, `provider` | Sudden spikes in legacy mode → integrations falling back. |
| `agent.tool_call.parse_failures_total` | `provider` | Models returning malformed tool calls. |
| `agent.tool_call.latency_seconds` | `mode`, `provider` | Native vs legacy performance comparison. |

---

## 4. What to do when things go wrong

A practical, step-by-step playbook ordered from cheapest to most
invasive.

### Step 0 — Just say "try again"

For both writer and coding agents you can post a comment containing a
phrase like `try again`, `please retry`, or `redo` on the issue / PR.
The session is resumed, the model gets the existing context plus the
new instruction, and a surprising number of transient tool-calling
hiccups (truncated arguments, missing fields, off-by-one schema
mistakes) disappear on the second attempt.

Why this often works:

- The malformed prior tool call is now part of the visible history; the
  model sees its own mistake and self-corrects.
- Provider-side hiccups (rate limits, brief schema regressions, partial
  responses) are non-deterministic.
- Sanitisation passes drop any leftover invalid tool blocks before the
  retry hits the provider.

### Step 1 — Disable the experimental native toggle

If retrying does not help, open
**AI Integrations → Edit → Tool calling** and switch
**"Enable experimental native tool calling"** to **off** (which sets
the underlying `use_legacy_tool_calling=true`). This forces
`chatWithTools` to delegate to the textual `chat(...)` path: the tool
catalogue is described inside the system prompt, and the model is asked
to reply with a JSON block the agent parses itself.

The legacy path:

- Avoids every native-API quirk (no `tool_use_id`, no
  `thoughtSignature`, no schema dialect mismatch, no name validator).
- Uses **more input tokens** (the tool catalogue and instructions go
  into the prompt every round) and slightly more output tokens (the
  model has to repeat the tool name and arguments as text).
- Has been the project’s primary code path since the first release and
  is exercised by the bulk of the test suite. In practice it has proven
  **extremely robust** across providers, model versions, and
  self-hosted backends.

Recommendation: when in doubt, switch to legacy. Pay the token premium
and revisit native mode only after the upstream provider/model gets a
fix.

### Step 2 — Check telemetry and logs

- `agent.tool_call.mode_total{mode="legacy"}` rising unexpectedly → an
  integration silently fell back; check why (`supportsNativeTools()`
  returning false, empty descriptor list, or operator toggle).
- `agent.tool_call.parse_failures_total{provider="..."}` rising → the
  model is returning malformed tool calls. Combine with the agent
  comments on the affected issue/PR to see the raw output.
- Look for `WARN` log lines from `AnthropicAiClient.reconcileToolCallPairs`
  or `AgentSessionService` — they pinpoint corrupted history elements
  that were dropped.

### Step 3 — Inspect the on-issue trace

Every native tool plan is posted as a comment on the issue/PR. If a
model is invoking tools nonsensically (see §5) the comment trace is the
fastest way to see exactly which tool was called with which arguments
and what the result was.

### Step 4 — Restart the agent run

If the session history has become so degraded that even sanitisation
cannot rescue it, close the originating comment thread (or reset the
session via the admin UI) and re-assign the bot. A fresh session avoids
replaying any historic tool blocks.

### Step 5 — Open a bug report

If neither retry nor legacy mode helps, please open an issue with:

- provider + model name and version,
- whether `use_legacy_tool_calling` was on or off,
- the raw provider error (from logs),
- the relevant comment trace,
- the values of `agent.tool_call.*` around the failure window.

---

## 5. “The model is calling tools, but not sensibly”

Native tool calling does **not** guarantee that the model uses the
tools well. Common failure modes we see in the field:

- **Calling `write-file` before reading the file** that needs to be
  changed.
- **Skipping validation tools** (`mvn`, `gradle`, `npm`, `dotnet test`,
  …) and declaring the task done. The validation-before-finish guard
  (§3.5) catches the obvious cases, but a model can still pick an
  inappropriate validation target.
- **Patch-file with a `search` string that does not exist** in the file
  because the model paraphrased it.
- **MCP tools called with truncated arguments** (e.g. `state`-only
  instead of `owner`/`repo`/`state`). The MCP JSON round-trip fix
  (§2.5) closed the framework-level cause; remaining cases are model
  hallucinations.
- **Loops where the model re-issues the same tool call** without
  reacting to the prior result.
- **Calling a tool that is not in the whitelist** — this is rejected by
  the dispatcher, but the model may keep retrying until the round
  budget is exhausted.

Quality of tool use depends on the model far more than on the API.
Smaller / older / heavily-quantised models routinely misuse tools even
when the provider’s tool API works perfectly. Anecdotally:

- Frontier reasoning models (`claude-sonnet-4.x`, `gpt-5.x`,
  `gemini-2.5-pro`) tend to follow the system prompt and validate
  before finishing.
- Small or "flash"/"haiku" variants frequently skip validation, fabricate
  paths, or stop after a single write.
- Local models behind Ollama / llama.cpp vary wildly — many of them
  benefit massively from running in **legacy** mode because the prompt
  contains explicit usage examples.

If a particular model consistently calls tools nonsensically, the right
fix is not to debug the native API but to:

1. Pick a stronger model on the same provider, **or**
2. Switch to legacy tool calling (§4 step 1) so the model has the full
   instruction text in front of it every round, **or**
3. Tighten the system prompt under **System settings → System prompts**
   to spell out the exact tool-usage order you expect.

---

## 6. Quick reference

| Situation | First action |
|---|---|
| Single failed run, otherwise stable | Comment `try again` on the issue/PR. |
| Repeated failures on the same integration | Turn **"Enable experimental native tool calling"** off (= legacy mode). |
| `400 tool_result.tool_use_id` from Anthropic | Make sure you are on the current release (history sanitisation fix); then retry. |
| `400 BAD_REQUEST: Function call is missing a thought_signature` from Gemini | Make sure you are on the current release (Gemini 3.x metadata round-trip); then retry. |
| Gemini `400 Invalid function name` | Update to the current release (`ToolNameSanitizer` is now applied everywhere); retry. |
| MCP tools getting empty arguments | Update to the current release (full JSON payload fix in `McpTools`/strategies); retry. |
| Model writes files but never builds | Confirm validation is enabled; the guard will re-prompt automatically. If it keeps skipping, switch to legacy mode and/or a stronger model. |
| llama.cpp ignores `tools` field | Expected — llama.cpp always runs in legacy mode via GBNF. |

---

## See also

- [AGENT.md](AGENT.md) — coding & writer agent workflows, including the
  detailed “Provider-native Function Calling (Step 6)” section.
- [MCP_SERVER_HANDLING.md](MCP_SERVER_HANDLING.md) — MCP discovery,
  whitelisting, and call transparency.
- [USER_GUIDE.md](USER_GUIDE.md) — UI walkthrough for creating AI
  integrations and toggling the tool-calling mode.
- [ARCHITECTURE.md](ARCHITECTURE.md) — component diagrams and request
  flows.

