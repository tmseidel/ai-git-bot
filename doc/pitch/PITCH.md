# AI-Git-Bot — The detailed pitch

> *Automate the necessary-but-uncomfortable parts of software
> development — directly inside the Git tools your team already uses.*

---

## 1. The problem nobody owns

Every team has a list of *"we know we should be doing this"*
engineering chores:

- Writing a properly scoped issue **before** coding starts.
- Adding the regression E2E test for the login bug that bit you
  last Friday.
- Re-reviewing a PR after the third force-push.
- Tearing down the stale preview environment that's been burning
  cluster budget for nine days.
- Picking up the boring follow-up issues — the rename, the dependency
  bump, the trivial refactor that the on-call engineer never gets to.

These chores are **necessary** (skipping them rots the codebase) but
**uncomfortable** (they aren't the fun part, and they are the first
thing cut under deadline pressure). They are also **everywhere** — a
small startup has them, a 500-engineer org has them, an open-source
project has them.

The market has plenty of point solutions:

- Linters and SAST tools for code style and security.
- GitHub Copilot Workspace / GitLab Duo / Qodo for IDE-side AI help.
- Vercel / Netlify / Render for preview-per-PR deployments.
- Playwright / Cypress / k6 for E2E test execution.
- Renovate / Mergify for rule-based PR pipelines.

But none of them sit in the *one* place every team already touches —
the **Git host** — and orchestrate the chores end-to-end across the
LLM, the CI/CD, the preview environment, and the PR comment thread.

**That gap is exactly what AI-Git-Bot fills.**

---

## 2. What AI-Git-Bot is, in one paragraph

AI-Git-Bot is a small, self-hostable **gateway** that turns Git-host
events (issue assigned, PR opened, reviewer re-requested,
`@bot` mention) into **named, agentic workflows**: a review workflow,
a coding workflow, a writer workflow, a full-stack QA workflow that
generates and runs Playwright tests against per-PR preview
deployments — and any custom workflow you implement on top of the
pluggable `PrWorkflow` SPI. It speaks **four Git platforms**
(Gitea, GitHub + GitHub Enterprise, GitLab CE/EE, Bitbucket Cloud) and
**five AI providers** (Anthropic, OpenAI, Google Gemini, Ollama,
llama.cpp). One Docker image, one PostgreSQL database, one admin UI.

---

## 3. The six chores AI-Git-Bot already automates

Every chore below is implemented and shipping in 1.7.0 — not a
roadmap item:

| Chore | What usually happens | What AI-Git-Bot does |
|---|---|---|
| 🧾 **Writing a good issue before any code is written** | Vague bug reports get queued and re-clarified in chat days later; acceptance criteria are missing. | Assign a **writer bot** to the issue → it inspects related issues + the repo (read-only), asks the *minimum* clarifying questions, and produces a structured `AI Created Issue` with acceptance criteria. |
| 🔍 **Reviewing PRs consistently even when the reviewer is swamped** | Reviews are skimmed, regressions slip in, the same comments keep being written by hand. | A **review bot** runs the same review every time the bot is requested as reviewer — large diffs are chunked, comments land inline, and `@bot` mentions keep the discussion in the PR. |
| 🧪 **Writing regression E2E tests for the bug you just fixed** | "We'll add a test later" — and we never do; manual QA is repeated for every PR. | Assign a deployment target + the `Full-stack QA` workflow → the bot **plans, authors, deploys, and runs** Playwright tests per PR, posts the report as a PR comment, and tears the environment down on close. |
| 🛠️ **Implementing the boring follow-up issues** | They pile up; senior engineers don't want them; juniors get blocked on them. | Assign an issue to a **coding bot** — it reads the source, drafts the change in a workspace, validates with the project's own build tooling (Maven / Gradle / npm / Go / Cargo / .NET), and opens a PR. |
| 🔁 **Re-running tests / regenerating coverage when something flaked** | Engineer manually re-runs locally, copies the report, pastes a screenshot. | `@bot rerun-tests` re-executes the existing suite; `@bot regenerate-tests <feedback>` re-plans the suite with operator hints. |
| 🧹 **Tearing down stale preview environments** | Forgotten previews accumulate, burn cluster budget, leak data. | PR-close lifecycle hook calls the deployment target's `teardown` action — webhook, MCP tool, static no-op, or a CI workflow dispatch. |

> Pick the chore that hurts most this quarter. Wire one bot. Done.
> The other workflows are opt-in per bot — nothing changes for repos
> you don't touch.

---

## 4. The headline feature: agentic PR workflows

Most PR-review bots stop at "diff in, comment out". AI-Git-Bot
treats a pull request as a **pipeline of named, agentic workflows**:

```
PR opened ─▶ PrWorkflowOrchestrator
              │
              ├── ReviewWorkflow         ──▶ inline + summary comments
              └── E2ETestWorkflow
                     │
                     ├── TestPlannerAgent  ──▶ which user journeys?
                     ├── DeploymentStrategy ─▶ STATIC / WEBHOOK / MCP / CI_ACTION
                     ├── TestAuthorAgent   ──▶ Playwright specs
                     ├── TestRunnerAgent   ──▶ run against preview, attach artifacts
                     └── SuitePromotionService ─▶ optional follow-up PR
```

Three things make this special:

1. **The bot doesn't deploy anything itself.** It plugs into the world
   you already have — Vercel / Jenkins / GitHub Actions / your
   internal platform MCP server — via a small `DeploymentStrategy`
   SPI. Four interchangeable strategies ship today (details below).
2. **The agents reuse the same `AgentLoop` / `chatWithTools`
   infrastructure** as the coding/writer agents. Native function
   calling on every provider, schema-validated plans, telemetry, and
   audit log work for free.
3. **The generated test suite lives per PR** (database-resident),
   so the bot can iterate freely without polluting the repo — and a
   four-mode suite-promotion workflow (`ephemeral` /
   `commit-to-pr` / `offer-as-pr` / `promote-on-merge`) lets the
   tests "graduate" into the codebase under operator control.

### 4.1 Four deployment strategies — pick the one that fits the world you already live in

| Strategy | Best for | Why it matters |
|---|---|---|
| **`STATIC`** | Vercel / Netlify / GitLab review apps / Render — anything that already publishes a preview-per-PR URL. | Zero extra infrastructure. The bot computes the URL from a template, optionally probes `/healthz`, and proceeds. **Marco the Frontend Lead's story.** |
| **`WEBHOOK`** | Jenkins / TeamCity / scripts behind a corporate firewall. | The bot POSTs an HMAC-signed envelope to your existing job and awaits a callback. One extra `curl` line in the Jenkinsfile — nothing more. **Priya the DevOps Engineer's story.** |
| **`MCP`** | Internal platform teams already exposing deploy/status/teardown over the Model Context Protocol. | The bot becomes *just another MCP client*. One whitelist, one audit trail, no new HTTP service, no inbound callback channel needed. **Alex the Platform Engineer's story.** |
| **`CI_ACTION`** | GitHub Actions / GitLab CI / Bitbucket Pipelines / Gitea Actions. | Native dispatch using the bot's existing provider token. Zero new services, zero new secrets, runs show up in the provider's native UI. **Sam the SRE's story.** |

Each strategy ships with:

- A persona-driven user story in
  [`doc/agentic-workflows/`](../doc/agentic-workflows/README.md).
- A laptop-runnable scenario under
  [`systemtest/`](../systemtest/README.md) so a developer can verify
  the integration in under two minutes — no real production CI needed.
- Per-strategy WireMock-style unit tests and integration coverage.

### 4.2 Suite promotion — the generated tests stop being throw-away

The single most common complaint about "AI generates tests for you"
products: *the tests evaporate when the PR closes, and the next PR
re-generates them from scratch*. AI-Git-Bot's
`SuitePromotionService` solves that with four lifecycle modes:

| Mode | What happens on a successful run |
|---|---|
| **`ephemeral`** (safe default) | Suite is deleted on PR close. Use while you trial the bot. |
| **`commit-to-pr`** | Tests are committed directly onto the feature branch — single PR review. |
| **`offer-as-pr`** | A follow-up PR `ai-tests/pr-{n}-r{runId} → feature` is opened with the generated tests under `tests/e2e/pr-{n}/`. |
| **`promote-on-merge`** | On parent-PR merge a follow-up PR `ai-tests/promoted-pr-{n}-r{runId} → default-branch` is opened so the tests join the CI matrix. |

Plus: idempotency via `PrWorkflowRun.followUpPrNumber`,
conflict-resolving file naming (`login.spec.ts` → `login_2.spec.ts`),
best-effort failure (the parent run is never rolled back), and a
nightly `PromotedSuiteGarbageCollector` (`@Scheduled` cron, retention
configurable via `prworkflow.e2e.promotion.retention`, default
`P30D`).

This is *the* feature that converts a one-off demo into compounding
engineering value.

---

## 5. Why the architecture is the point

A bot that only works on GitHub, only with OpenAI, and only with one
CI vendor is a *script*. AI-Git-Bot is a **gateway**:

- **Every Git platform** plugs in through a `RepositoryApiClient` SPI.
  Four implementations ship today (Gitea, GitHub + GHE, GitLab CE/EE,
  Bitbucket Cloud).
- **Every AI provider** plugs in through an `AiClient` SPI. Five
  implementations ship today (Anthropic, OpenAI, Google Gemini,
  Ollama, llama.cpp).
- **Every tool call** (built-in + MCP) flows through a unified
  `AgentToolRouter` with provider-native function calling and a legacy
  fallback (`agent.use_legacy_tool_calling`).
- **Every external MCP server** is reachable through the standard MCP
  configuration + per-tool whitelist (`McpToolSelectionService`).
  Disable a tool after the fact and runtime calls degrade to
  `REJECTED` — no silent execution.

The practical consequence: a single AI-Git-Bot instance can serve
mixed estates (GitHub for product, Gitea for embedded, Bitbucket for
the legacy acquisition) with mixed AI budgets (Claude for the
high-value review bot, Ollama for the cheap planner step) — all from
**one admin UI, one PostgreSQL database, one set of webhook secrets,
one encrypted credential store** (AES-256-GCM via
`EncryptionService`).

---

## 6. Why it is *safe* to adopt

Adoption risk is the #1 reason "AI in the SDLC" pilots die. The
design choices below exist specifically to keep that risk near zero:

- **Backwards compatible.** Every new column on `bots` is nullable.
  Existing bots without a `WorkflowConfiguration` keep running the
  legacy `review` workflow only.
- **Opt-in everywhere.** Every workflow ships behind a
  `prworkflow.<name>.enabled` flag. Only `review` defaults to `true`.
- **Operator-owned tool whitelists.** The per-bot built-in tool
  whitelist (`BotToolConfiguration`) and the per-MCP-config tool
  whitelist (`McpConfiguration`) decide what the agent can do — not
  the LLM, not the prompt.
- **No silent deploys.** The bot **triggers** your existing CI; it
  never invents a deploy path. The four `DeploymentStrategy`
  implementations exist precisely so deployment stays under your
  team's existing controls.
- **Encrypted at rest.** Every credential, every webhook secret,
  every deployment-target `config_json` is encrypted with the
  application's AES-256-GCM key.
- **HMAC-signed callbacks with per-run secrets.** The async callback
  endpoint validates secrets in constant time and refuses replays
  past a terminal state with HTTP 409.
- **Auditable.** Every workflow run is persisted (`pr_workflow_runs`,
  `pr_workflow_steps`), every tool call is logged, and Micrometer
  meters (`prworkflow.run_total{workflow,status}` +
  `prworkflow.run_duration_seconds{workflow}`) are exposed at
  `/actuator/prometheus` for Grafana.
- **Cost-bounded.** Workflow params expose hard caps
  (`maxTestCases`, `maxRetries`) and the agent loop honours the
  shared `agent.budget.*` token budget.

> One Docker image, one PostgreSQL database, no Kubernetes
> required. Self-hostable end-to-end including local LLMs
> (Ollama, llama.cpp) — nothing has to leave your infrastructure.

---

## 7. Where it beats the alternatives

| If you currently rely on… | …you'll keep using it. AI-Git-Bot adds: |
|---|---|
| **GitHub Copilot Workspace / GitLab Duo** | A **gateway** that survives the next Git-host migration. Workspace is GitHub-only, Duo is GitLab-only. AI-Git-Bot does both, plus Gitea and Bitbucket. |
| **Qodo (CodiumAI) `/test` slash commands** | The same slash-command UX *plus* the deployment + execution legs. Qodo generates static unit tests; AI-Git-Bot deploys a per-PR preview, runs Playwright against it, and reports back. |
| **Sweep / Aider / Devin** | A multi-tenant, per-bot UI with shared session memory, audit logs, and provider-agnostic tool routing — not a per-developer CLI. |
| **Playwright MCP / Browser-Use** | Orchestration around the test execution: plan generation, deployment lifecycle, artifact upload to the PR, suite-promotion follow-up PRs. Plug Playwright MCP into AI-Git-Bot via the standard MCP whitelist — best of both worlds. |
| **Renovate / Mergify** | Generative steps. Renovate is rule-based; AI-Git-Bot decides *which* user journeys to test from the diff, then writes them. |
| **A bespoke Python script calling OpenAI** | Five AI providers behind one switch, four Git providers behind another, encryption at rest, an admin UI, an audit trail, a dashboard, and a maintainer who isn't you. |

There is **no** open-source product today that offers configurable,
agentic PR follow-up workflows combining **test generation +
deployment + test execution** while staying **provider-agnostic** on
both axes (Git and AI). That gap is the product.

---

## 8. Adoption path — start small, expand by chore

Most teams adopt one chore at a time. The progression we recommend:

1. **Day 1 — Review bot.** Create one AI integration, one Git
   integration, one bot. Assign it as reviewer on a couple of repos.
   This is the safest, lowest-friction win. Nothing else changes.
2. **Week 1 — Writer bot.** Assign a writer bot to your noisiest
   bug-report queue. Watch it ask the *minimum* set of clarifying
   questions and produce structured `AI Created Issue` follow-ups.
3. **Week 2 — Coding bot for boring follow-ups.** Pick five issues
   that have been sitting in the backlog for a quarter. Assign them
   to a coding bot. Review the PRs as you would a junior's.
4. **Month 1 — `STATIC` deployment + E2E workflow on one repo.** If
   you already use Vercel / Netlify / GitLab review apps, this is
   the cheapest entry into the agentic PR pipeline. Watch the bot
   author and run Playwright specs against the existing previews.
5. **Month 2 — pick the deployment strategy that matches the rest of
   your estate.** `WEBHOOK` for Jenkins, `CI_ACTION` for GitHub
   Actions, `MCP` for an internal platform server. The four user
   stories under [`doc/agentic-workflows/`](../doc/agentic-workflows/README.md)
   tell you which one to pick.
6. **Quarter 1 — flip suite promotion to `offer-as-pr` or
   `promote-on-merge`.** The generated coverage stops evaporating and
   starts compounding.

There is no big-bang migration. Every step above is opt-in per bot.

---

## 9. The bottom line

- AI-Git-Bot **automates the chores nobody on your team wants to
  own** — issue drafting, PR review, regression E2E coverage,
  follow-up coding work, preview teardown.
- It does so **inside the Git tools your team already uses**, with
  the **AI provider you already pay for**, against the **CI/CD you
  already operate**.
- It is **self-hostable**, **encrypted end-to-end**, **opt-in per
  bot**, and **provider-agnostic on both the Git axis and the AI
  axis** — the only open-source product in its category that hits all
  four.
- One Docker image. One PostgreSQL database. One admin UI. Adopt
  one chore this sprint, ship value next week, expand from there.

> **Pick the chore that hurts most this quarter. Wire one bot. Done.**

---

## Appendix — proof points and pointers

- Architecture overview: [`doc/ARCHITECTURE.md`](../doc/ARCHITECTURE.md)
- Agentic PR workflows feature docs: [`doc/agentic-workflows/`](../doc/agentic-workflows/README.md)
- Per-feature operator recipes:
  [`doc/PR_WORKFLOWS.md`](../doc/PR_WORKFLOWS.md),
  [`doc/PR_WORKFLOWS_E2E.md`](../doc/PR_WORKFLOWS_E2E.md),
  [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../doc/PR_WORKFLOWS_CI_ACTIONS.md),
  [`doc/PR_WORKFLOWS_WEBHOOK_RECIPES.md`](../doc/PR_WORKFLOWS_WEBHOOK_RECIPES.md)
- MCP integration: [`doc/MCP_SERVER_HANDLING.md`](../doc/MCP_SERVER_HANDLING.md)
- Per-bot tool whitelist: [`doc/BOT_TOOL_CONFIGURATIONS.md`](../doc/BOT_TOOL_CONFIGURATIONS.md)
- Hands-on laptop scenarios (one per feature):
  [`systemtest/README.md`](../systemtest/README.md)
- Persona-driven user stories:
  [`STATIC`](../doc/agentic-workflows/STATIC_DEPLOYMENT_USER_STORY.md),
  [`WEBHOOK`](../doc/agentic-workflows/WEBHOOK_DEPLOYMENT_USER_STORY.md),
  [`MCP`](../doc/agentic-workflows/MCP_DEPLOYMENT_USER_STORY.md),
  [`CI_ACTION`](../doc/agentic-workflows/CI_ACTION_DEPLOYMENT_USER_STORY.md),
  [Suite promotion](../doc/agentic-workflows/SUITE_PROMOTION_USER_STORY.md)
- Docker image: [`tmseidel/ai-git-bot` on Docker Hub](https://hub.docker.com/r/tmseidel/ai-git-bot)
- Source: [`github.com/tmseidel/ai-git-bot`](https://github.com/tmseidel/ai-git-bot)
- License: MIT

