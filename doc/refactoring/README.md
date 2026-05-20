# PR-Review Agentic Workflows — Complete Planning Package

This folder contains the complete specification, implementation plan, and GitHub integration guide for **extending AI-Git-Bot's PR review with agentic workflows** (test generation, deployment, execution, reporting).

> **Status:** M1 – M3 ✅ shipped  M4 (wave 1 + wave 2 iterations 1 – 4) ✅ shipped  M5 ✅ shipped  M6 ✅ shipped  M7 ⏳ planned  
> **Target Release:** AI-Git-Bot 1.7.0  
> **Effort:** 8–10 weeks across 7 milestones

---

## Current implementation status

| Milestone | Status | Notes |
|---|---|---|
| **M1** — `PrWorkflow` SPI + `ReviewWorkflow` extraction | ✅ shipped | Legacy review path now flows through `PrWorkflowOrchestrator`; Flyway `V13`. |
| **M2** — Workflow configurations CRUD + bot assignment | ✅ shipped | Operators manage named configurations via the admin UI; Flyway `V14` + `V15`. |
| **M3** — Deployment targets (Webhook + Static) + callback endpoints | ✅ shipped | Both no-extra-deps strategies + HMAC-signed callback channel. |
| **M4 / wave 1** — E2E workflow skeleton | ✅ shipped | Persistence (Flyway `V17`), sandbox workspace, `TestSuiteRunner` SPI, PR comment renderer, PR-close lifecycle hook. |
| **M4 / wave 2, iteration 1** — Tooling layer | ✅ shipped | `ToolKind.PR_WORKFLOW`, 5 built-in tools, `ArtifactCommentRenderer`, provider-agnostic `attachPullRequestArtifact`. Tests: 676. |
| **M4 / wave 2, iteration 2** — Agent layer | ✅ shipped | `TestPlannerAgent` / `TestAuthorAgent` / `TestRunnerAgent`, `E2eAgentRunner`, `PlaywrightTestSuiteRunner`. Tests: 699. |
| **M4 / wave 2, iteration 3** — Operator surface | ✅ shipped | `E2eTestSlashCommandHandler` (`@bot rerun-tests` / `@bot regenerate-tests`) wired into all four provider webhook handlers, Flyway `V18` `Full-stack QA` seed (H2 + PostgreSQL), `SuiteLifecycleMode.EPHEMERAL`-aware teardown. Tests: 705. |
| **M4 / wave 2, iteration 4** — Provider polish + sample app | ✅ shipped | Per-provider native artifact uploads (GitLab `/uploads`, Gitea issue assets, Bitbucket downloads), shared `ArtifactUploadSupport`, operator-feedback threading (`PrWorkflowContext.hints` → planner user message), sample Node app + docker-compose. Tests: 722. |
| **M5** — MCP deployment strategy | ✅ shipped | `MCPDeploymentStrategy` + `McpDeploymentConfig` + `McpDeploymentTemplating`, poll-based (no callback), save-time whitelist enforcement via `McpToolSelectionService`, admin UI form-help for the `MCP` strategy, end-to-end recipe in `doc/PR_WORKFLOWS.md` and `doc/MCP_SERVER_HANDLING.md` § 6. Tests: 754 (+32). |
| **M6** — CI Action strategy | ✅ shipped | `CiActionTriggerStrategy` + `CiActionPoller`, provider-native dispatch for GitHub Actions / Gitea Actions / GitLab CI / Bitbucket Pipelines via three new `RepositoryApiClient` SPI methods (`dispatchWorkflow`, `getWorkflowRun`, `getWorkflowRunOutputs`), per-provider unit-tested status mapping, `@EnableScheduling` on the application, recipes in [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../PR_WORKFLOWS_CI_ACTIONS.md), laptop scenario under `systemtest/sample-ci-action-server/`. (Per-release test count: see version history below.) |
| **M7** — Suite promotion workflow | ⏳ planned (optional) | — |

See [`PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md`](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md) § M4 for the detailed wave 2 iteration breakdown and [`doc/PR_WORKFLOWS_E2E.md`](../PR_WORKFLOWS_E2E.md) for the operator-facing behaviour shipped so far.

---

## Files in this folder

| File | Purpose | Audience |
|---|---|---|
| **[PR_REVIEW_AGENTIC_WORKFLOWS.md](./PR_REVIEW_AGENTIC_WORKFLOWS.md)** | **Concept & Architecture Specification** — the "why" and "what" of the feature. Describes the two new first-class concepts (`PrWorkflow` SPI, `DeploymentTarget` abstraction), data model, deployment strategies, the E2E test workflow, UI sketches, risks, and market research of existing solutions. | Architects, tech leads, stakeholders deciding whether/how to proceed. |
| **[PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md)** | **Detailed Implementation Plan** — breaks down the 7-milestone roadmap into engineering tasks (Java classes, DB migrations, UI components, tests, docs). Includes conventions, cross-cutting work, release strategy, and risk register. | Backend engineers; team leads assigning work; sprint planners. |
| **[MCP_DEPLOYMENT_USER_STORY.md](./MCP_DEPLOYMENT_USER_STORY.md)** | **Concrete M5 user story + benefits walkthrough** — persona-driven motivation for `MCPDeploymentStrategy`, before/after diagram, acceptance criteria, and a pointer to the laptop-runnable scenario under `systemtest/`. | Stakeholders asking "what do I actually get from M5?"; platform engineers evaluating MCP vs. WEBHOOK / CI_ACTION. |
| **[WEBHOOK_DEPLOYMENT_USER_STORY.md](./WEBHOOK_DEPLOYMENT_USER_STORY.md)** | **Concrete M3 user story for `WebhookTriggerStrategy`** — Priya the DevOps engineer at a Jenkins-heavy shop, HMAC-signed trigger + per-run callback channel, benefits matrix, before/after diagram. | Stakeholders / DevOps engineers running heterogeneous CI (Jenkins / TeamCity / scripts). |
| **[STATIC_DEPLOYMENT_USER_STORY.md](./STATIC_DEPLOYMENT_USER_STORY.md)** | **Concrete M3 user story for `StaticPreviewUrlStrategy`** — Marco the frontend lead on Vercel / Netlify review apps, URL-template + healthz-gated readiness, benefits matrix, before/after diagram. | Stakeholders / frontend leads already using preview-per-PR platforms (Vercel, Netlify, GitLab review apps, Render). |
| **[CI_ACTION_DEPLOYMENT_USER_STORY.md](./CI_ACTION_DEPLOYMENT_USER_STORY.md)** | **Shipped M6 user story for `CiActionTriggerStrategy`** — Sam the SRE on GitHub Actions / GitLab CI / Bitbucket Pipelines / Gitea Actions, native dispatch via three new `RepositoryApiClient` SPI methods, scheduled `CiActionPoller`, zero new services or secrets. Design questions resolved inline; links to the laptop-runnable scenario under `systemtest/`. | Stakeholders / SREs running provider-native CI; teams evaluating `CI_ACTION` vs. `WEBHOOK` / `STATIC` / `MCP`. |
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
| 2026-05-19 | 1.1 | Status refresh: M1–M3 + M4 wave 1 shipped; M4 wave 2 iterations 1 + 2 (tooling + agent layer) shipped; iterations 3 + 4 still open. Test bar at 699 green. |
| 2026-05-19 | 1.2 | Status refresh: M4 wave 2 iteration 3 (slash commands, `Full-stack QA` Flyway `V18` seed, `SuiteLifecycleMode`-aware PR-close teardown) confirmed shipped; iteration 4 partially landed (sample app + docker-compose). Per-provider artifact overrides and WireMock integration test remain. Test bar at 705 green. |
| 2026-05-19 | 1.3 | M4 wave 2 iteration 4 shipped: per-provider native artifact uploads (GitLab uploads, Gitea issue assets, Bitbucket downloads) with renderer-fallback + graceful upload-failure degradation, shared `ArtifactUploadSupport` helper, `PrWorkflowContext.hints` map + `PrWorkflowOrchestrator.run(..., hints)` overload threading `@bot regenerate-tests <feedback>` into the planner's user message. Composite WireMock end-to-end system test deliberately deferred (existing unit/integration tests cover the building blocks). Test bar at 722 green. |
| 2026-05-19 | 1.4 | **M5 shipped.** `MCPDeploymentStrategy` lands under `org.remus.giteabot.prworkflow.deployment.mcp` with `McpDeploymentConfig` / `McpDeploymentTemplating` helpers, reusing `McpOrchestrationService.executeTool(...)` (no new transport). Poll-based — `awaitsCallback() == false` — the bot's existing deployment poller calls `statusTool` until `READY`/`FAILED`. Admin UI: `MCP` now appears in the `DeploymentTargetService.availableStrategyTypes()` dropdown and the strategy-config helper text on the deployment-target form documents the JSON schema. Save-time validation parses the config, verifies the referenced `McpConfiguration` exists, and rejects the save when any tool name (`deployTool`, optional `statusTool`/`teardownTool`) is missing from `McpToolSelectionService.selectedQualifiedToolNameSet(...)`. The runtime path performs the same whitelist check, so disabling a tool after the fact safely degrades to a `REJECTED` deployment. New tests: `McpDeploymentTemplatingTest` (6), `McpDeploymentConfigTest` (9), `MCPDeploymentStrategyTest` (13), `DeploymentTargetServiceTest` expanded (+4 MCP cases). Operator docs: new `doc/PR_WORKFLOWS.md` § Deployment targets `MCP` block + `doc/MCP_SERVER_HANDLING.md` § 6 (tool shape, placeholder envelope, whitelisting, polling, `MCP` vs. `WEBHOOK` decision matrix). Test bar at 754 green (+32). |
| 2026-05-19 | 1.5 | M5 stakeholder enablement: added [`MCP_DEPLOYMENT_USER_STORY.md`](./MCP_DEPLOYMENT_USER_STORY.md) (persona-driven user story + benefits + before/after diagram for `MCPDeploymentStrategy`) and a runnable laptop scenario under `systemtest/sample-mcp-deploy-server/` + `systemtest/docker-compose-mcp-deployment.yml` + `systemtest/README-mcp-deployment.md`. The sample MCP server exposes the three expected tools (`platform__deploy_preview` / `platform__preview_status` / `platform__teardown_preview`) over the Streamable HTTP MCP transport, simulates PENDING→READY transitions on a configurable timer, and hands back `http://sample-e2e-app:3000` as the previewUrl so the existing M4 sample app plays the role of the deployed PR build. No production code changes; test bar unchanged at 754 green. |
| 2026-05-19 | 1.6 | Stakeholder enablement for the remaining deployment strategies: added sibling user-story docs [`WEBHOOK_DEPLOYMENT_USER_STORY.md`](./WEBHOOK_DEPLOYMENT_USER_STORY.md) (Priya the DevOps engineer / Jenkins shop), [`STATIC_DEPLOYMENT_USER_STORY.md`](./STATIC_DEPLOYMENT_USER_STORY.md) (Marco the frontend lead / Vercel + Netlify review apps), and [`CI_ACTION_DEPLOYMENT_USER_STORY.md`](./CI_ACTION_DEPLOYMENT_USER_STORY.md) (Sam the SRE / provider-native CI — planned for M6, includes open design questions). Each follows the same template as the M5 story: persona → pain → user story → acceptance criteria → benefits matrix → before/after diagram → sequence → decision matrix. No production code changes. |
| 2026-05-20 | 1.7 | **M6 shipped.** `CiActionTriggerStrategy` + scheduled `CiActionPoller` land under `org.remus.giteabot.prworkflow.deployment`; three new `RepositoryApiClient` SPI methods (`dispatchWorkflow(WorkflowDispatchRequest)`, `getWorkflowRun(...)`, `getWorkflowRunOutputs(...)`) implemented across all four provider clients (GitHub Actions, Gitea Actions, GitLab CI, Bitbucket Pipelines) with a shared `WorkflowRunStatus` mapping. The strategy is callback-aware: the poller publishes a `CallbackResult` through the existing `DeploymentCallbackNotifier` channel so the orchestrator wakes up the moment the run reaches `READY` / `FAILED`. Preview-URL resolution chain: provider-native run outputs (`previewUrlOutput` key) → `previewUrlTemplate` placeholders (`{prNumber}` / `{sha}` / `{branch}` / `{branchSlug}`) → optional M3 HMAC callback. `@EnableScheduling` enabled on the application bootstrap; admin UI gains `CI_ACTION` in the deployment-target dropdown plus form-help. Operator recipes: new [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../PR_WORKFLOWS_CI_ACTIONS.md) (one provider per section) + [`CI_ACTION_DEPLOYMENT_USER_STORY.md`](./CI_ACTION_DEPLOYMENT_USER_STORY.md) flipped from ⏳ planned to ✅ shipped (acceptance criteria checked, design questions resolved). Hands-on scenario added under [`systemtest/sample-ci-action-server/`](../../systemtest/sample-ci-action-server/) + [`systemtest/docker-compose-ci-action.yml`](../../systemtest/docker-compose-ci-action.yml) + [`systemtest/README-ci-action.md`](../../systemtest/README-ci-action.md) (Node mock GitHub-Actions-style dispatch server with in-memory per-run state, configurable PENDING→COMPLETED transitions and a failure switch). Test bar at **798 green (+44 over M5)**, including `CiActionTriggerStrategyTest`, `CiActionPollerTest`, `WorkflowRunStatusMappingTest`, and per-provider `*ApiClientArtifactUploadTest` extensions. |

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

