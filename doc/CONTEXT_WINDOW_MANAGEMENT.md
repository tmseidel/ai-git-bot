# Context Window Management Strategy

This document describes the multi-layered approach to managing the AI
model's context window during long-running agent sessions. It is written
at an architectural level — no concrete class names or package paths —
so the algorithms are clear regardless of implementation language.

---

## 1. Overview

An agent session is an iterative loop: the AI model receives the full
conversation history on every call, so the prompt size grows monotonically
unless something actively removes content. Without intervention, every
long session will eventually exceed the model's context window and fail.

The system uses **four defensive layers**, ordered from cheapest to most
destructive:

```
┌─────────────────────────────────────────────────────────────────┐
│                    CONTEXT WINDOW BUDGET                        │
│                                                                 │
│  Layer 1: TOOL RESULT TRUNCATION                                │
│  ───────────────────────────────                                │
│  Applied at ingestion time. Caps individual tool outputs        │
│  before they enter the conversation history.                    │
│                                                                 │
│  Layer 2: PROACTIVE COMPACTION                                  │
│  ─────────────────────────────                                  │
│  Triggered between rounds. Removes old conversation units       │
│  when the last AI call's prompt size approaches the limit.      │
│                                                                 │
│  Layer 3: AGGRESSIVE COMPACTION                                 │
│  ──────────────────────────────                                 │
│  Emergency fallback. Drops everything except a summary and      │
│  the most recent N logical units.                               │
│                                                                 │
│  Layer 4: REACTIVE ERROR HANDLING                               │
│  ──────────────────────────────                                 │
│  Last resort. Catches "prompt too long" errors from the AI      │
│  provider and triggers compaction mid-round.                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Concepts

### 2.1 Logical Units

A conversation is not a flat list of messages — it has structure.
The most important structural invariant is the **tool call / tool
response pair**:

```
  assistant: "I'll read that file."
    └── tool_call(id="abc", name="read_file", args={path: "src/main.py"})

  tool: (response to call "abc")
    └── tool_result: "def main():\n    ...\n"
```

These two messages form a **logical unit**. Separating them breaks
the conversation: the model sees a tool response with no matching
request, or a tool call with no response. Both confuse the model and
waste tokens.

A **logical unit** is defined as:

```
unit = one of:
  - [assistant_with_tool_calls, tool_response_for_each_call]
  - [single_message]  (no tool calls)
```

### 2.2 Context Window Pressure

The critical metric is **the size of the most recent AI call's prompt**,
not the cumulative token count across all calls.

Why? Each AI call's input already contains the full history. Summing
input tokens across rounds produces a superlinear number that has no
relationship to actual occupancy:

```
  Round 1:  prompt =  5,000 tokens   → cumulative =  5,000
  Round 2:  prompt = 15,000 tokens   → cumulative = 20,000
  Round 3:  prompt = 30,000 tokens   → cumulative = 50,000
  Round 4:  prompt = 50,000 tokens   → cumulative = 100,000  ← fires!
```

At round 4 the real prompt is 50k tokens (25% of a 200k window), but
the cumulative sum says 100k (50%). The correct measure is always the
**last call's input tokens**.

---

## 3. Layer 1: Tool Result Truncation

**When:** Every time a tool produces output, before it enters history.

**Why:** Tool outputs (file contents, build logs, command output) can be
enormous. A single `cat` of a large file can consume 100k+ characters.
Capping at ingestion time prevents any single tool result from dominating
the context window.

**Algorithm: Head + Tail with Marker**

```
function truncate(text, max_chars):
    if text.length <= max_chars:
        return text

    marker_overhead = 64          // space for "[... N chars truncated ...]"
    head_size = max_chars / 2
    tail_size = max_chars - head_size - marker_overhead

    if tail_size <= 0:            // budget too small for both halves
        head_size = max_chars - marker_overhead
        tail_size = 0

    removed = text.length - head_size - tail_size

    return text[0 : head_size]
         + "\n\n[... " + removed + " characters truncated ...]\n\n"
         + text[-tail_size : ]    // empty string if tail_size == 0
```

**Design rationale:** The head preserves the beginning of the output
(headers, structure, opening lines). The tail preserves the end (error
messages, exit codes, build results). The middle — usually repetitive
data — is discarded.

```
  Original:  ┌──────────┬──────────────────────────────┬──────────┐
             │  HEADER  │      ... 5000 chars ...      │  FOOTER  │
             └──────────┴──────────────────────────────┴──────────┘

  After:     ┌──────────┬─────────────────────┬──────────┐
             │  HEADER  │ [5000 chars removed]│  FOOTER  │
             └──────────┴─────────────────────┴──────────┘
```

---

## 4. Layer 2: Proactive Compaction

**When:** After each round of the agent loop, before the next AI call.

**Why:** Even with truncated tool results, the history grows with each
round. Proactive compaction removes old context before the model starts
losing track of recent instructions or the provider rejects the request.

### 4.1 Trigger Decision

```
function should_compact_proactively(session):
    last_input_tokens = session.last_call_input_tokens   // NOT cumulative!
    context_window    = session.context_window_tokens
    threshold         = config.proactive_threshold       // default: 0.7

    return (last_input_tokens / context_window) >= threshold
```

The threshold is configurable. 70% is the default — it leaves headroom
for the AI's response tokens and for the next round's growth.

### 4.2 Normal Compaction Algorithm

```
function compact(history, max_history_chars, min_keep_units):
    total_chars = sum of chars across all messages in history

    if total_chars <= max_history_chars:
        return 0   // nothing to do

    // 1. Walk the history from the END, grouping into logical units
    units = group_into_logical_units(history, from_end=true)

    // 2. Decide how many units to keep (at least min_keep_units)
    keep_count = max(min_keep_units, units_to_fit_budget)

    // 3. Drop old units from the FRONT
    units_to_drop = units[0 : len(units) - keep_count]

    // 4. Build summary of dropped content
    summary = "[Earlier context compacted to reduce token usage. "
            + "Key decisions and recent work preserved below.]"

    // 5. Replace dropped units with summary message
    history = [summary_message] + units[keep_count:]

    return chars_freed
```

### 4.3 Logical Unit Grouping (from the end)

This is the heart of the algorithm. Walking from the end ensures we
keep the most recent context (most relevant to the current task) and
drop the oldest.

```
function group_into_logical_units(messages):
    units = []
    i = len(messages) - 1

    while i >= 0:
        msg = messages[i]

        if msg.role == "tool":
            // This is a tool response. Look BACKWARD for the matching
            // assistant message with the tool_call that initiated it.
            tool_call_id = msg.tool_call_id

            for j from i-1 down to 0:
                if messages[j].has_tool_call_with_id(tool_call_id):
                    // Found the pair. They form one unit.
                    unit = [messages[j], msg]
                    units.prepend(unit)
                    i = j - 1       // skip past both messages
                    break
            else:
                // Orphan response — no matching call. Treat as single unit.
                units.prepend([msg])
                i -= 1

        elif msg.has_tool_calls:
            // Assistant message with tool calls but NO response found
            // ahead of it (would have been consumed above). Orphan call.
            units.prepend([msg])
            i -= 1

        else:
            // Plain message (user or assistant without tools)
            units.prepend([msg])
            i -= 1

    return units
```

**Example walkthrough:**

```
  History (oldest → newest):

    [0] assistant: "Let me check the config"     ─┐
    [1] tool_call: read_file("config.yml")         │ ← Unit A (pair)
    [2] tool:      result of read_file            ─┘
    [3] assistant: "The port is 8080"            ──── Unit B (single)
    [4] user:      "Change it to 9090"           ──── Unit C (single)
    [5] assistant: "Done, updated port"           ─┐
    [6] tool_call: write_file("config.yml", ...)   │ ← Unit D (pair)
    [7] tool:      result of write_file           ─┘

  Grouped units (from end):  [D, C, B, A]

  If we keep 2 units:  [D, C] are kept, [B, A] are dropped.
  Result:  [summary] + [C] + [D]
```

### 4.4 Aggressive Compaction (Emergency Fallback)

If normal compaction cannot free enough space (e.g., the remaining
units are all too large), the system escalates to aggressive mode:

```
function compact_aggressively(history, min_keep_units):
    units = group_into_logical_units(history)

    // Keep only the absolute minimum: summary + last N units
    keep = units[-min_keep_units:]

    summary = "[History was aggressively compacted due to context "
            + "window pressure. Only the most recent " + min_keep_units
            + " interaction units are preserved. Earlier context "
            + "has been summarized and discarded.]"

    history = [summary_message] + keep
```

---

## 5. Layer 3: Reactive Error Handling

**When:** The AI provider rejects a request because the prompt exceeds
its context window limit.

**Why:** Despite proactive measures, edge cases can still produce
oversized prompts (e.g., a tool returns unexpectedly large output that
slipped through truncation, or the token estimator underestimates).

### 5.1 Detection

The system detects "prompt too long" errors by inspecting:

```
function is_prompt_too_long_error(exception):
    // Check HTTP status codes commonly used for this
    if exception.status_code in [400, 413, 429, 500]:
        message = exception.message.lower()

        // Check for provider-specific error patterns
        keywords = [
            "too long",
            "too large",
            "context length",
            "maximum context",
            "token limit",
            "input too long",
            "prompt too long"
        ]

        return any(keyword in message for keyword in keywords)

    return false
```

### 5.2 Recovery

```
catch PromptTooLongError:
    log.warning("Prompt too long — triggering emergency compaction")

    // Try aggressive compaction
    compact_aggressively(history, min_keep_units=2)

    // Retry the same round with the compacted history
    continue to next iteration of the loop
```

This gives the agent one more chance to complete the round with a
smaller prompt. If it still fails, the loop terminates with an error.

---

## 6. The Full Lifecycle

```
                    ┌──────────────────┐
                    │   Agent Loop     │
                    │   (per round)    │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Build prompt     │
                    │ from history +   │
                    │ system prompt +  │
                    │ current message  │
                    └────────┬─────────┘
                             │
              ┌──────────────▼──────────────┐
              │ Send to AI provider         │
              │                             │
              │  ┌──────────────────────┐   │
              │  │ "Prompt too long"?   │───┼──► REACTIVE: aggressive
              │  └──────────────────────┘   │     compaction + retry
              └──────────────┬──────────────┘
                             │ success
                    ┌────────▼─────────┐
                    │ Record response  │
                    │ in history       │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Track tokens     │
                    │ (last call only) │
                    └────────┬─────────┘
                             │
              ┌──────────────▼──────────────┐
              │ Usage > threshold?          │
              │                             │
              │   YES ──► PROACTIVE:        │
              │             compact history │
              │                             │
              │   NO  ──► continue          │
              └──────────────┬──────────────┘
                             │
                    ┌────────▼─────────┐
                    │ Execute tool     │
                    │ calls (if any)   │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Truncate tool    │◄── LAYER 1: cap each result
                    │ results          │     before adding to history
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Add tool results │
                    │ to history       │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Next round       │
                    └──────────────────┘
```

---

## 7. Configuration Knobs

| Parameter                  | Default  | Purpose                                       |
|----------------------------|----------|-----------------------------------------------|
| `max_tool_result_chars`    | 8,000    | Cap per tool output (Layer 1)                 |
| `max_history_chars`        | 120,000  | Soft limit for total history (Layer 2)        |
| `proactive_threshold`      | 0.7      | Fraction of context window that triggers      |
|                            |          | proactive compaction                          |
| `min_keep_units`           | 4        | Minimum logical units to preserve during      |
|                            |          | compaction                                    |
| `context_window_tokens`    | per-     | Model-specific, sourced from the AI provider  |
|                            | provider | configuration                                 |

---

## 8. Design Decisions

### Why logical units instead of message count?

Message count is misleading. A "round" of tool use produces 2+ messages
(call + response), while a plain exchange produces 1. Counting messages
would drop tool pairs at unpredictable boundaries, leaving orphan
responses that confuse the model.

### Why track last-call tokens instead of cumulative?

Each AI call's prompt includes the full history, so the last call's
input tokens IS the current prompt size. Cumulative tokens grow
superlinearly (5k + 15k + 30k = 50k cumulative, but the real prompt
at round 3 is only 30k). Using cumulative would trigger compaction
far too early and permanently — the counter only grows.

### Why head+tail truncation for tool results?

Tool outputs have predictable structure: the beginning contains headers,
schema, or structure; the end contains errors, exit codes, or results.
The middle is usually repetitive data (log lines, file contents, test
output) that the model can work without. Head+tail preserves both
anchor points.

### Why walk from the end during compaction?

The most recent context is the most relevant. The agent is working on
the current task, and dropping recent units would lose the working
memory. Walking from the end and keeping the tail ensures the agent
retains awareness of what it was just doing.

### Why is aggressive compaction a separate layer?

Normal compaction respects the `max_history_chars` budget and tries to
keep as many units as fit. Aggressive compaction ignores the budget
and keeps only the absolute minimum. It is a last resort — it loses
significant context — so it should only fire when normal compaction
has already tried and the situation is still critical.
