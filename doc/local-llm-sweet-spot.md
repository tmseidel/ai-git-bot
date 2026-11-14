# Running a Useful AI Agent on Modest Hardware

*Why parameters, VRAM, and tokens/sec are the wrong headline — and how a
refurbished 64 GB / 6 GB machine runs a real, self-hosted agent.*

---

## The benchmarks we are handed are the wrong ones

The dominant "score" for local AI is a trio: **number of parameters, installed
VRAM, tokens per second**. Those are *throughput and footprint* numbers, not
*capability* numbers. For the narrow, well-defined tasks a self-hosted bot
actually does — reviewing a PR, turning an issue into a diff, validating a
diff — a small model with the right constraints is genuinely competitive, and
the hardware that used to be required for that is now the price of a
refurbished office desktop.

This article is the synthesis of a working decision about one concrete
machine: **a 64 GB RAM / 6 GB VRAM box that also has to host other
webapps**, running a Gitea AI PR-review bot on a local LLM. Every claim below
that is about the *bot's own code and prompts* points at a file in this
repository; claims about the *outside world* (model specs, engine behaviour,
the history of tool-calling) carry numbered sources.

---

## 1. What the hardware actually can do

64 GB of RAM plus 6 GB of VRAM is a **CPU-heavy, thin-VRAM** shape. The 6 GB
can only hold the hot ~4–8 B of a model's layers; the rest lives in RAM and
speed is set by memory bandwidth, not the GPU. Three consequences follow.

- **≤ ~7 B models fit entirely in the 6 GB** → fast, effectively GPU-bound.
- **13 B–120 B models are mostly in RAM** → speed is bandwidth-bound.
- **MoE models are the unlock for this shape.** A 120 B MoE has 120 B total
  parameters but only ~5–15 B active per token, so it gives near-7 B speed
  with 100 B+ of knowledge. This box is more capable than the "7 B is all you
  can run" intuition suggests.

The concrete example: `gpt-oss-120b` at Q4 is ≈ 62 GB and *fits a 64 GB box*
[3][7]. Two years ago, a 120 B model was a server-class purchase. The catch
is speed: at ~6–15 tok/s on CPU, a multi-step agent (issue → plan → several
tool calls → diff) takes many minutes per issue. Fine for a batch or
nightly bot, too slow for interactive use.

---

## 2. The inference engine — and your hardware decides it

Three engines dominate local serving. The decision pivots on one question:
**is this a GPU box or a CPU box?** [14]

| | llama.cpp | Ollama | vLLM |
|---|---|---|---|
| CPU + small-VRAM offload | **Best** (fine-grained per-layer) | Good (coarser) | **Poor fit** (GPU-first) |
| Grammar-constrained output | **GBNF (full native)** | JSON *mode* only | guided-json only |
| Native support in this bot | **Yes** (`llamacpp` provider) | Yes (`ollama` provider) | **No** |
| Concurrency / batching | Low | Low | **Best** (useless on CPU) |
| OpenAI-compatible `/v1` | Yes | Yes | Yes |
| Effort to run 120 B on this box | Low (one `llama-server` cmd) | Low (`ollama pull`) | High, and slow on CPU |

**Recommendation: llama.cpp as the primary engine**, for three compounding
reasons.

1. **GBNF grammar is the single biggest driver of the bot's agent
   reliability.** The repository documents this directly: llama.cpp's
   "GBNF grammar support is the main advantage" over Ollama, which "often
   fails" on the structured JSON the agent workflow needs. The agent prompts
   are constrained by a grammar so a 7 B model reliably emits the JSON the
   loop expects — that is the whole reason a small model is usable here.
   `doc/LLAMACPP.md`, `src/main/resources/prompts/native/*-agent-tool-protocol.md`.
2. **The bot already has a native `llamacpp` provider**
   (`src/main/java/org/remus/giteabot/ai/llamacpp/LlamaCppClient.java`).
   Zero new client code. Ollama is already present too; vLLM is not.
3. **CPU offload is exactly where llama.cpp is strongest**, and vLLM's only
   real advantage — continuous batching for many concurrent GPU requests —
   is wasted on a 6 GB-VRAM machine.

**Keep Ollama as the zero-effort fallback** (`ollama pull gpt-oss:120b` just
runs). Its cost is no GBNF, so the agent workflow is less reliable than the
same model under llama.cpp; use Ollama for review, llama.cpp for the agent.

**Skip vLLM for this box.** Its value is GPU-bound multi-user throughput.
The one case to reconsider it is *after* you buy a real 24–80 GB GPU.

"Something completely different" is not better for this shape: MLX is
Apple-only, LM Studio is a llama.cpp GUI, TensorRT-LLM is GPU-only. The real
way to serve *more* people is a GPU, not a different CPU engine.

---

## 3. What you can actually run on the 64 GB / 6 GB box

| Bot task | Free-form / structured | Tool-calling | Reliability on 7–14 B |
|---|---|---|---|
| **PR review** | Free-form | None (one-shot) | **High** — the model's best use |
| **Critic** (validate diff) | Free-form → one JSON verdict | None (one-shot) | **Very high** — one decision is the easiest task shape |
| **Agent** (issue → diff) | Structured JSON (GBNF) | Yes (N calls) | **Workable** — GBNF forces valid JSON; *tool choice* is still the model's |

The meta-point: **tool-calling reliability on a local model is a "many small
calls" problem, not a "one big agent" problem.** The critic (one decision) is
more reliable than the review (one answer), which is more reliable than the
agent (N calls). A prompt architecture should exploit that ordering — and
this one mostly does.

The honest ceiling (documented in the repo, `doc/OLLAMA.md`): small models are
fine for *review* but the *autonomous coding agent* needs ~13 B+ to be
reliable. Prompt tuning is a real multiplier on top of the model; it is not a
substitute for a bigger model on the agent workflow. Do not market the 7 B as
a reliable multi-file coder.

---

## 4. Choosing the model: Qwen3.8-27B

The working choice for this box is **Qwen3.8-27B at Q4_K_M on llama.cpp**.

Qwen3.8-27B is a **dense 27 B** model — 64 layers, a hybrid Gated DeltaNet +
Gated Attention design, 5120 hidden dimension, 262k native context, a vision
encoder, Apache-2.0 licensed [2][15][16]. "Dense" is the key word: *all* 27 B
parameters are active per token, the opposite of the 120 B MoE. So the trade-off
flips in your favour:

- **vs. gpt-oss-120b (MoE):** 27 B dense is *faster* (less active weight per
  token, roughly 1.5–3× the tok/s at similar RAM) and fits 64 GB with ~45 GB
  left for the webapps. The 120 B wins on knowledge/agent reasoning. For
  review + critic + a bounded agent, 27 B is well in range.
- **vs. 7 B / 14 B:** 27 B is the quality sweet spot without the 120 B's
  "eats the whole machine during a run" problem.

**Footprint (mmap, reclaimable when idle):**

| Quant | Approx. file / RAM | Headroom for webapps |
|---|---|---|
| **Q4_K_M (recommended)** | ~16–17 GB | **~45 GB free** |
| Q5_K_M | ~18–19 GB | comfortable |
| Q8_0 | ~27–28 GB | still fine |

(An AWQ build such as `Qwen3.8-27B-W4A16-AWQ` is the right format for vLLM
but not for llama.cpp, which wants GGUF — use the Q4_K_M GGUF instead [1].)

**The one setting that decides fast vs. painful on 6 GB VRAM.** Do *not* use
`--n-gpu-layers -1` ("all layers on GPU"): with 6 GB you can hold only a
fraction of a 27 B Q4, and offloading more than fits makes llama.cpp bounce
the remaining layers between GPU and RAM every token — *slower* than pure CPU.
Start at **`--n-gpu-layers 8`** (roughly a quarter of the 64 layers), step up
while watching `nvidia-smi`, and stop before it starts thrashing. That is the
single biggest speed lever. Also:

- `--mmap` **on** (default) — keeps the 16 GB reclaimable when idle [10][11].
- `--mlock` **off** — mlock would *pin* all the pages in RAM and kill your
  headroom.
- `--ctx-size` **moderate** (8k–16k). The 262k context is a *file*
  capability, not a *memory* one; a 16k context on a 27 B costs real resident
  RAM you do not want competing with the webapps.

Realistic generation speed for a dense 27 B that is mostly in RAM is roughly
**5–10 tok/s** (bandwidth-bound), so a 500-token review is about 1–2 minutes.

---

## 5. Can you keep using the 64 GB while the model is loaded?

**Yes — and the reason is `mmap`.** llama.cpp memory-maps the weights by
default, so a ~16–17 GB (27 B) or ~62 GB (120 B) model is *not* a hard RAM
reservation. It is file-backed and demand-paged: when the webapps need the
memory, the kernel pages the model's weight pages out to disk and gives the
RAM to the webapps [10][11]. The only cost is that the *next* inference
re-reads those pages from disk (a few extra seconds before the first token).

| Model state | RAM actually held | Effect on webapps |
|---|---|---|
| Loaded, **idle** | Small (recently-touched pages) + pinned bits | **Fine** — webapps get the rest |
| **Actively generating** (long agent run) | Climbs toward the model size | Webapps' pages evicted to swap → slow *while* running; recover after |
| **Unloaded** (API call) | ~0 (process baseline) | **Full RAM available** |

What does *not* shrink: the **KV cache / context** (allocated at load, pinned
for the model's lifetime, scales with `--ctx-size`), the **6 GB of VRAM**
while loaded, and the **active working set** during a generation.

**The tight-case warning:** a 120 B Q4 is ~62 GB in a 64 GB box — almost no
headroom, so an *active* 120 B run consumes the whole machine and pushes the
webapps into swap. That is the real trade-off of the 120 B here. The 27 B
(~16–17 GB) does not have this problem, which is one more reason for the
27 B as the resident model.

**Three ways to make it coexist, by how often the bot runs:**

1. **Keep it resident (default).** mmap reclaims on idle; set `--mmap` on,
   `--mlock` off, `--ctx-size` modest. Best when the bot runs often.
2. **Load on demand, unload when done (most headroom).** llama-server exposes
   a **model load/unload API** — `POST /models/load` and `POST /models/unload`
   (and with `--models-dir`, swap models without a restart) [13]. A cron or
   the bot itself loads the model only while an issue is being processed and
   unloads it after, so the RAM is fully free the rest of the day. This is the
   clean, deterministic answer to "I run other webapps here."
3. **Resident small model + on-demand 120 B.** Keep a 14 B/27 B resident for
   reviews (small enough to barely compete with the webapps) and load the
   120 B only when the issue→diff workflow actually fires. Probably the best
   practical split for a shared box.

**Contrast with Ollama:** Ollama **auto-unloads a model 5 minutes after the
last request** and frees the RAM/VRAM automatically (`keep_alive` changes
that) [12]. llama.cpp does *not* auto-unload by default — you must call
`/models/unload` or restart. For a box where the webapps are the primary
tenant and the bot is secondary, Ollama's free auto-unload is a real
convenience worth re-weighing, at the cost of the less-reliable JSON on the
agent path.

**Safeguards so a runaway model cannot take down the webapps:**

- Run the model in its **own container with a `--memory` limit** (e.g. 58 GB)
  and a `--pids-limit`.
- Put the model on **fast NVMe/SSD**, not an HDD — the idle↔active
  re-warm is disk-bound.
- Add **swap** as a safety valve so the webapps degrade to slow instead of
  being OOM-killed under a peak inference burst.
- **Verify empirically** — run the model, then `free -h` and
  `ps -o rss -p $(pgrep llama-server)` idle vs. mid-run. That measured number,
  not the file size, is the real resident working set.

**Recommendation for this use case:** an isolated, memory-capped container,
mmap on / mlock off, **Qwen3.8-27B Q4_K_M resident for reviews**, and the
120 B **loaded on demand** for the agent. The webapps own the machine the
normal working day; the 120 B claims it only for the few minutes an issue
actually takes. If you would rather not script the load/unload, run Ollama for
its 5-minute auto-unload and accept the less-reliable agent JSON.

---

## 6. Prompt tweaks that keep a small model reliable

The pattern that makes a local model dependable is **one narrow task per call,
strict output, explicit stop conditions** — not a long free-form agent. The
repository's four prompt files already follow it; the levers are:

| Prompt file | Bot task | Scope-keeping mechanism | Tool-calling | 7–14 B risk |
|---|---|---|---|---|
| `local-llm.md` | PR review | Free-form, 5 focus areas, What/Where/Why/How | None (one-shot) | Broad focus list dilutes a 7 B; "memory leaks / inefficient algorithms" is above a 7 B's reasoning → tighten to "top 2–4 highest-severity issues, one-line fix each" |
| `agent.md` (+ `prompts/native/*-agent-tool-protocol.md`) | Issue → diff | GBNF-constrained JSON: file-changes / run-tool / message / done | Yes | JSON is forced (good), but *tool choice* is still the model's → keep the tool set minimal, add "stop when done" + an iteration budget |
| `critic.md` | Validate diff | Free-form → one JSON verdict (APPROVE / ITERATE / ABORT) | None (one-shot) | Very low — one clear decision is the easiest task shape |

Concrete, smallest-diff levers (no code changes):

- **Narrow the focus list** in `local-llm.md` — fewer, higher-signal bullets
  beat a comprehensive checklist on small models.
- **Tighten the agent JSON contract** — fewer fields, one concrete example,
  and an explicit "when you are done, output `done` with no tool request."
- **Cap the tool set** — every extra tool widens the "wrong tool / bad arg"
  error surface; expose only what the task needs.
- **State the budget the code already enforces** — the loop has an
  `AgentBudget` (`src/main/java/org/remus/giteabot/agent/loop/AgentBudget.java`)
  and an `AgentLoop`. That code-level cap is more reliable than any prompt
  phrase; the prompt should just *name* the budget the code enforces.
- **Give one concrete example, not just the schema** — small models copy
  examples far more reliably than they infer from grammar alone.

The meta-point again: reliability on a local model comes from *narrow,
constrained, single-purpose calls* more than from any single knob. That is the
reason the critic outperforms the agent — and the reason the prompt
architecture should keep pushing tasks toward the critic's shape.

---

## 7. "Since when has tool-calling been reliable enough for an agent?"

- **Jun 13, 2023** — OpenAI ships the function-calling API with GPT-4
  [8][9]. First structured tool calls. *Unreliable* — frequent malformed JSON
  and hallucinated arguments.
- **~mid-2024** — GPT-4o-class function calling is widely considered the first
  *reliably usable* tool-calling in a production agent; this is when agentic
  tool loops stopped being a research demo. [8]
- **2025 → 2026** — open-weight models catch up (Qwen3 / Qwen3-Coder, Gemma,
  gpt-oss), and a benchmark — the **Berkeley Function-Calling Leaderboard
  (BFCL v4)** — measures it [6]. 2026 evals show small models tool-call
  surprisingly well *when the output is constrained*: one eval reports a
  ~4 B model scoring ~97 % on a constrained local tool-calling task [5], and
  several mid-size models (Gemma, GLM, Qwen3 32 B, Qwen3-Coder 30 B, Llama 3.3
  70 B) are rated "reliable" [4].

So the honest answer: **API-side, reliably agentic since ~mid-2024 (GPT-4o);
local-model-side, only reliably recently (2025–2026), and only with
structured-output constraints (GBNF / JSON mode) plus narrow tool sets.**
That is precisely this bot's design — and it is a defensible, testable
marketing claim: *"a 7 B model with a grammar does real tool-calling — that
was not true before 2025."*

---

## 8. The real accessibility story

The accessibility argument for a self-hosted bot is **not** raw capability —
it is *hardware floor, privacy, and no per-token cost*. Levers, roughly in
order of leverage:

1. **Partial GPU offload** — the 27 B Q4 with ~8 layers on 6 GB runs on a
   4–6 GB laptop. "A 27 B reviewer on a box with no real GPU" is the claim.
2. **A low floor for review-only** — a 1 B/3 B model (≈1.3–2 GB) gives a
   usable reviewer on a machine with no GPU at all (`doc/OLLAMA.md`).
3. **Context/chunking so small models do not OOM** — already present as
   *Max Diff Chars Per Chunk* / *Max Diff Chunks* (`doc/LLAMACPP.md`).
4. **Model routing** (the forward-looking one): a small model triages and
   escalates to a bigger local model — or a cloud provider — only when the
   diff is large or the task is the agent workflow. This is the one new
   capability worth adding next; it makes "it works" true across hardware
   tiers and cuts cost.
5. **No API key, self-hosted, code never leaves the machine** — for
   compliance-bound shops, *this* is the accessibility story, more than any
   benchmark number.

---

## Bottom line

Do not chase parameters / VRAM / tok-s as the headline. The headline is:
**a 27 B dense model (or a 120 B MoE, loaded on demand) on a 64 GB / 6 GB
refurb, served by llama.cpp with GBNF grammars, does real PR review, a real
critic, and a bounded issue→diff agent — with the code never leaving the
machine.** Prompt/skill tuning is a real multiplier on top of that, not a
substitute for it.

Concrete defaults to ship:

- **Engine:** llama.cpp (GBNF + native provider + best CPU offload); Ollama as
  the easy fallback; skip vLLM until there is a real GPU.
- **Model:** Qwen3.8-27B **Q4_K_M**, `--n-gpu-layers ~8` (tune against
  `nvidia-smi`), `--mmap` on, `--mlock` off, `--ctx-size` 8k–16k.
- **Coexistence with webapps:** memory-capped container + `--mmap` reclaim on
  idle; keep the 27 B resident, load the 120 B on demand.
- **Reliability:** narrow, GBNF-constrained, single-purpose calls + a
  code-level agent budget + a tightened `local-llm.md`.

---

## A skeptical read of the claims above

Per the article's own discipline, the softest points in this text:

- **The 27 B speed figure (5–10 tok/s) and the `--n-gpu-layers 8` starting
  point are reasoned from standard memory-bandwidth and VRAM-fitting math, not
  measured on this box.** They are the numbers to confirm with `llama-bench`
  and `nvidia-smi` before they are published as guarantees. Treat them as
  planning estimates.
- **The 120 B "≈ 62 GB at Q4" figure traces to a vendor/content site and
  OpenAI's own repo**, not to a primary benchmark. The exact size depends on
  the specific GGUF build; it is a planning number, not a spec.
- **The tool-calling reliability claims rest on 2026 content-marketing and
  community evals** (a 2026 eval, a 2026 roundup) and the BFCL v4
  leaderboard [4][5][6]. They are directionally strong (constrained small
  models *do* tool-call well) but the specific percentages are single-sourced;
  re-check the BFCL v4 numbers directly before quoting exact figures.
- **The "reliably agentic since ~mid-2024 (GPT-4o)" framing is a community
  consensus, not a dated, measured milestone** [8]. It is a useful heuristic,
  but it should be labelled as judgement in the published version.
- **The strongest counter-evidence the article leans on is its own:** the
  repo's `doc/OLLAMA.md` already states that 1–7 B is *not recommended* for the
  agent workflow and that 13 B+ is the floor for reliability. The marketing
  claim in §7 must not be read as contradicting that — the grammar makes the
  *JSON* reliable; it does not make a 7 B a reliable multi-file *coder*.
- **Provenance note:** several of these facts (27 B VRAM tables, 64 GB model
  picks) were taken from search-result summaries because page-level extraction
  was unavailable in this environment. Re-verify those against the live pages
  for the 64 GB model picks and the 27 B VRAM tables (the Hugging Face and
  64 GB sources in the list below) before this is published externally.
## Sources

[1] https://huggingface.co/unsloth/Qwen3.8-27B-GGUF — Unsloth Qwen3.8-27B GGUF
[2] https://console.groq.com/docs/models/qwen/qwen3.8-27b — Qwen3.8-27B architecture (Groq docs)
[3] https://openclawdc.com/blog/best-local-llms-64gb-ram — Best local LLMs for 64GB RAM (2026)
[4] https://www.promptquorum.com/power-local-llm/best-local-models-tool-calling-2026 — Best local models for tool calling 2026
[5] https://www.jdhodges.com/blog/local-llms-on-tool-calling-2026-pt1-local-lm — 13 local LLMs on tool calling eval 2026
[6] https://gorilla.cs.berkeley.edu/leaderboard.html — BFCL v4 function-calling leaderboard
[7] https://github.com/openai/gpt-oss — OpenAI gpt-oss (120b/20b)
[8] https://www.aiwiki.ai/wiki/function_calling — Function calling history (AI Wiki)
[9] https://developers.openai.com/api/docs/guides/function-calling — OpenAI function calling docs
[10] https://github.com/ggml-org/llama.cpp/discussions/1876 — llama.cpp memory usage (mmap)
[11] https://github.com/ggml-org/llama.cpp/discussions/19163 — llama.cpp MoE/offload to disk (mmap default)
[12] https://www.ssdnodes.com/learn/ollama-keep-model-loaded — Ollama keep_alive default 5 min
[13] https://www.glukhov.org/llm-hosting/llama-cpp/unload-llama-cpp-router-models — llama.cpp model load/unload API
[14] https://insiderllm.com/guides/llamacpp-vs-ollama-vs-vllm — llama.cpp vs Ollama vs vLLM (2026)
[15] https://atomic.chat/blog/guides/how-to-run-qwen-3-8-locally — Run Qwen3.8-27B locally (VRAM tables)
[16] https://codersera.com/blog/how-to-run-qwen-3-8-locally-2026 — Qwen3.8-27B on 16/24GB GPUs (2026)
