# PR-Review Agentic Workflows — Complete Planning Package

This folder contains the complete specification, implementation plan, and GitHub integration guide for **extending AI-Git-Bot's PR review with agentic workflows** (test generation, deployment, execution, reporting).

> **Status:** Planning / Ready for implementation  
> **Target Release:** AI-Git-Bot 1.7.0  
> **Effort:** 8–10 weeks across 7 milestones

---

## Files in this folder

| File | Purpose | Audience |
|---|---|---|
| **[PR_REVIEW_AGENTIC_WORKFLOWS.md](./PR_REVIEW_AGENTIC_WORKFLOWS.md)** | **Concept & Architecture Specification** — the "why" and "what" of the feature. Describes the two new first-class concepts (`PrWorkflow` SPI, `DeploymentTarget` abstraction), data model, deployment strategies, the E2E test workflow, UI sketches, risks, and market research of existing solutions. | Architects, tech leads, stakeholders deciding whether/how to proceed. |
| **[PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md)** | **Detailed Implementation Plan** — breaks down the 7-milestone roadmap into engineering tasks (Java classes, DB migrations, UI components, tests, docs). Includes conventions, cross-cutting work, release strategy, and risk register. | Backend engineers; team leads assigning work; sprint planners. |
| **[GITHUB_ISSUE_EPIC.md](./GITHUB_ISSUE_EPIC.md)** | **GitHub issue template** — ready-to-paste epic issue + 7 child issues (one per milestone) for tracking progress in GitHub/GitLab Issues. Includes acceptance criteria, definitions of done, and a checklist for team coordination. | Team lead; scrum/kanban master; person opening the GitHub issue. |
| **[README.md](./README.md)** (this file) | **Navigation & quick start** — you are reading this now. | Everyone. |

---

## Quick start

### 1. **Understand the concept (30 minutes)**
   - Read [**Motivation and Goal**](./PR_REVIEW_AGENTIC_WORKFLOWS.md#1-motivation-and-goal) (§1).
   - Skim [**High-level architecture**](./PR_REVIEW_AGENTIC_WORKFLOWS.md#4-high-level-architecture) (§4) + diagram.
   - Review [**Market research**](./PR_REVIEW_AGENTIC_WORKFLOWS.md#2-research-what-do-existing-solutions-do) (§2) to see what others are doing.

### 2. **Design review (1–2 hours)**
   - Deep-dive [**Deployment abstraction**](./PR_REVIEW_AGENTIC_WORKFLOWS.md#6-deployment-abstraction-deploymenttarget) (§6) — the most novel part.
   - Study the [**E2E test workflow**](./PR_REVIEW_AGENTIC_WORKFLOWS.md#7-first-concrete-use-case-e2etestworkflow) (§7) — your first real workflow.
   - Review [**Agent modelling**](./PR_REVIEW_AGENTIC_WORKFLOWS.md#10-agent-modelling--what-is-really-the-agent-here) (§10) to understand how the three agents (`Planner`, `Author`, `Runner`) reuse the existing agent infrastructure.
   - Identify any **architectural concerns** in [**Risks and open questions**](./PR_REVIEW_AGENTIC_WORKFLOWS.md#11-risks-and-open-questions) (§11).

### 3. **Plan sprints (30 minutes)**
   - Open [**PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md**](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md).
   - Read [**Conventions**](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md#conventions-for-all-milestones) — establishes package layout, DB migration numbering, testing bar, feature flags.
   - Skim each of the 7 milestone sections (M1–M7):
     - Note the **effort estimate** (e.g. "1–2 weeks").
     - List the **key deliverables** (Java services, UI, DB migrations).
     - Identify **prerequisites** (M1 must ship before M2, etc.).

### 4. **Create GitHub issues**
   - Copy the **[GITHUB_ISSUE_EPIC.md](./GITHUB_ISSUE_EPIC.md)** text into a new GitHub issue.
   - Create 7 child issues (one per milestone), linking each to this epic.
   - Assign team members based on expertise (backend, frontend, per-provider specialists for M6).

### 5. **Start M1 in the next sprint**
   - Assign frontend engineer to review the proposed `PrWorkflowRegistry` + `PrWorkflowOrchestrator` class structure.
   - Assign backend engineer to extract `ReviewWorkflow` and write `ReviewWorkflowTest` regression suite.
   - Run Flyway `V13` migrations on both H2 and Postgres test instances.

---

## Roadmap at a glance

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  M1 — Foundation (1–2w)                                             │
│  ├─ PrWorkflow SPI + registry                                      │
│  ├─ ReviewWorkflow extracted (zero behaviour change)               │
│  └─ Flyway V13 (pr_workflow_runs, pr_workflow_steps)               │
│                                                                     │
│  M2 — Workflow Configurations (1w)                                  │
│  ├─ WorkflowConfiguration + UI CRUD                                │
│  ├─ Default initializer (additive enable on startup)               │
│  └─ Bot form: new "Workflow configuration" dropdown                │
│                                                                     │
│  M3 — Deployment Targets (1–2w)                  [Parallel security] 
│  ├─ DeploymentStrategy SPI                                         │
│  ├─ WebhookTriggerStrategy + StaticPreviewUrlStrategy              │
│  ├─ /api/workflow-callback/{runId}/{secret} endpoint               │
│  └─ Callback endpoint + 2 strategy recipe docs                     │
│                                                                     │
│  M4 — E2E Test Workflow MVP (2–3w)               [Parallel audit log]
│  ├─ TestPlannerAgent + TestAuthorAgent + TestRunnerAgent            │
│  ├─ 5 new built-in tools (pr-test-write, pr-test-run, …)          │
│  ├─ Flyway V16 (pr_test_suites, pr_test_cases)                    │
│  ├─ E2ETestWorkflow orchestration + slash commands                 │
│  └─ Integration test on sample app (systemtest/)                   │
│                                                                     │
│  M5 — MCP Deployment Strategy (1w)                                  │
│  ├─ MCPDeploymentStrategy (reuse McpOrchestrationService)          │
│  ├─ Config UI: MCP config picker + tool name selectors             │
│  └─ Whitelist enforcement at save-time                             │
│                                                                     │
│  M6 — CI Action Strategy (1w)                                       │
│  ├─ RepositoryApiClient.dispatchWorkflow() per provider            │
│  ├─ CiActionTriggerStrategy + scheduled CiActionPoller             │
│  └─ Per-provider recipe docs (GitHub, Gitea, GitLab, Bitbucket)    │
│                                                                     │
│  M7 — Suite Promotion (optional, 1w)                                │
│  ├─ suiteLifecycle param (ephemeral / offer-as-pr / promote)       │
│  ├─ SuitePromotionService (follow-up PR creation)                  │
│  └─ Nightly GC job (clean up orphaned suites after 30d)            │
│                                                                     │
│  ─────────────────────────────────────────────────────────────────│
│                                                                     │
│  Release: 1.7.0-preview1 (M1+M2) → preview2 (M3+M4) → GA (M5+M6+M7)
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Key design decisions (spoiler-free)

1. **Backwards compatibility:** Existing bots don't change. New workflows are opt-in per bot via the UI.
2. **Per-PR test suites:** Tests live in the database, not the repo. They can "graduate" via a follow-up PR (M7).
3. **Non-invasive deployment:** The bot **triggers** your existing CI, then **waits for a callback** with the preview URL. No custom deploy code needed.
4. **Four deployment strategies:** Webhook (generic), CI-native (GitHub/Gitea/GitLab/Bitbucket), MCP (internal platform servers), or static URLs (Vercel/Render style).
5. **Agent reuse:** The three new agents (planner, author, runner) use the **same `AgentLoop` + `chatWithTools` infrastructure** as the existing coding/writer agents. Native tool calling, plan persistence, schema validation, and telemetry work for free.
6. **Feature flags:** All new workflows ship behind `prworkflow.<name>.enabled=false` (operator opt-in). Only `review` (legacy) defaults to `true`.

---

## Cross-references to existing docs

- 🏗️ **Architecture overview:** [doc/ARCHITECTURE.md](../ARCHITECTURE.md) — system diagram, component responsibilities, request flows.
- 🤖 **Agent infrastructure:** [doc/AGENT.md](../AGENT.md) — coding agent, writer agent, `AgentLoop`, native tool calling, schema validation.
- 🔧 **MCP integration:** [doc/MCP_SERVER_HANDLING.md](../MCP_SERVER_HANDLING.md) — how tools are discovered, whitelisted, and executed.
- ⚙️ **Bot tool whitelisting:** [doc/BOT_TOOL_CONFIGURATIONS.md](../BOT_TOOL_CONFIGURATIONS.md) — the per-bot whitelist model that the new PR-workflow tools reuse.

---

## Common questions

**Q: Will this break my existing bots?**  
A: No. The legacy PR-review workflow becomes `ReviewWorkflow` (M1) and runs by default. New workflows are opt-in per bot.

**Q: Do I need to set up a deployment target to use AI-Git-Bot?**  
A: No. Existing PR-review bots (without a deployment target) work unchanged. The E2E workflow is only triggered if you assign a deployment target to the bot and enable the `e2e-test` workflow.

**Q: Our CI is Jenkins. Can we use AI-Git-Bot's new workflows?**  
A: Yes. Use the **`WebhookTriggerStrategy`** (M3) — the bot will POST to your existing Jenkins job via webhook, await a callback with the preview URL, and proceed with testing.

**Q: What if we already run Vercel/Render/GitLab review apps?**  
A: Use the **`StaticPreviewUrlStrategy`** (M3) — no changes needed. Just configure a URL template, and the bot will resolve it per PR.

**Q: Can the bot use our internal platform MCP server?**  
A: Yes. Use the **`MCPDeploymentStrategy`** (M5) — expose `deploy-pr-preview`, `get-preview-status`, and `teardown-preview` tools on your MCP server, and the bot will call them like any other tool.

**Q: How long does it take to generate and run E2E tests?**  
A: Depends on your app and test framework:
   - AI generation (planner + author): 30–60 seconds (plus token costs).
   - Deployment: depends on your CI (usually 2–5 minutes).
   - Test execution: frameworks vary (Playwright: 5–20 seconds per test; k6: seconds to minutes).
   - Total per PR: typically 5–10 minutes.

**Q: What LLM models should we use for the three agents?**  
A: All reuse the bot's configured AI integration. Optionally, different models per agent (e.g. cheap planner → strong author → fast runner). See `agent.budget.*` in M4 docs.

**Q: Can I regenerate tests if they're failing?**  
A: Yes. Use the slash command `@bot regenerate-tests` (with optional feedback) to re-run the planner and author. M4 docs cover this.

---

## Getting started checklist

- [ ] **Leadership approval:** Confirm this roadmap aligns with your product/roadmap goals.
- [ ] **Stakeholder buy-in:** Identify 1–2 beta customers willing to test M3+M4 during preview phases.
- [ ] **Resource planning:** Assign backend, frontend, and per-provider leads to milestones M1–M7 (see effort estimates).
- [ ] **CI/CD planning:** Decide on one main deployment strategy to support for MVP (recommend `WebhookTriggerStrategy` + `StaticPreviewUrlStrategy`; defer `CiActionTriggerStrategy` to M6 if needed).
- [ ] **Testing environment:** Set up a test instance (Docker Compose) with sample apps for regression testing (M1) and E2E validation (M4).
- [ ] **Security review:** Schedule a threat-model session before M3 ships (callback endpoints, HMAC, preview-env data isolation).
- [ ] **Documentation plan:** Assign a tech writer to convert implementation tasks into operator-facing recipes (M3–M6 docs are critical).
- [ ] **Community comms:** Plan a blog post / release notes structure for the 1.7.0 launch (design, features, example recipes).

---

## Version history

| Date | Version | Changes |
|---|---|---|
| 2026-05-18 | 1.0 | Initial planning package (concept, implementation plan, GitHub issue template). |

---

## Feedback

Found an issue, question, or suggestion? Please:

1. Review the relevant section in the three documents above.
2. If still unclear, open a GitHub issue tagged `documentation` or `roadmap`.
3. For architectural disputes, create a discussion on the main repo, tag stakeholders, and link back to this folder.

---

**Created:** 2026-05-18  
**Maintained by:** AI-Git-Bot core team  
**License:** Same as AI-Git-Bot repository

