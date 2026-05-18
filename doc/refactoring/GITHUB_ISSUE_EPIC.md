# Epic: PR-Review Agentic Workflows — Extend reviews with test generation, deployment, and E2E execution

**Status:** 🔵 Planning  
**Target Version:** AI-Git-Bot 1.7.0  
**Effort Estimate:** 8–10 weeks  
**Stakeholders:** Core team, beta users with complex PR testing needs

---

## Summary

Currently, AI-Git-Bot's PR-review workflow is linear: fetch diff → send to LLM → post review comment. This epic extends the bot with a pluggable **workflow orchestration system** that lets operators chain multiple follow-up actions:

- **Generate E2E tests** from the PR diff (intelligent test planning).
- **Deploy** to a preview/test environment (callback-driven, strategy-agnostic).
- **Execute tests** against the live preview (using the AI agent + Playwright MCP).
- **Report results** back as PR comments and status checks.
- **Future workflows:** security scans, performance smoke tests, doc diffs, migration plans (enabled by the extensible `PrWorkflow` SPI).

This is **backwards-compatible** — existing bots continue to work unchanged; new workflows are opt-in per bot via the UI.

## Problem Statement

1. **PR reviews are passive:** Today's bot only *comments* on code changes; it doesn't verify the changes work end-to-end.
2. **Testing is manual:** Teams must write E2E tests manually, run them against a deployed version, and reconcile results.
3. **Deployment is operator-specific:** There's no standard way to trigger a preview environment; each company has a different CI/CD setup (Jenkins, GitHub Actions, Argo, Vercel, etc.).
4. **No observability:** Operators have no centralized place to see which workflows ran on which PRs and why they passed or failed.

## Solution Overview

We introduce two new first-class concepts:

1. **`PrWorkflow`** — a reusable, configurable follow-up action that runs on PR open/sync (registry-based SPI, like `AiProviderRegistry`).
2. **`DeploymentTarget`** — an abstraction layer for hooking into existing deployment infrastructure (webhook trigger, GitHub Actions dispatch, MCP tool, or static preview URL).

All new workflows are **built on the existing `AgentLoop` infrastructure** (see [AGENT.md](../ARCHITECTURE.md)) so the AI can iteratively generate, validate, and refine E2E tests using tool calls.

**Key design decisions:**
- The legacy review becomes the first `ReviewWorkflow` (refactored in M1). No behaviour change for existing bots.
- Per-PR test suites live in the database, not the repo. They can optionally "graduate" to the repo via a follow-up PR (M7).
- Deployment is non-invasive: the bot **triggers** existing CI, then **awaits a callback** with the preview URL. No custom deploy logic needed.

## Related Documents

- 📋 **Concept & Architecture:** [PR_REVIEW_AGENTIC_WORKFLOWS.md](./PR_REVIEW_AGENTIC_WORKFLOWS.md)
- 🛠️ **Implementation Plan:** [PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md)
- 🏗️ **Architecture Overview:** [doc/ARCHITECTURE.md](../ARCHITECTURE.md)
- 🤖 **Agent Infrastructure:** [doc/AGENT.md](../AGENT.md)
- 🔧 **MCP Server Handling:** [doc/MCP_SERVER_HANDLING.md](../MCP_SERVER_HANDLING.md)
- ⚙️ **Bot Tool Configurations:** [doc/BOT_TOOL_CONFIGURATIONS.md](../BOT_TOOL_CONFIGURATIONS.md)

---

## Acceptance Criteria (Epic-level)

- [ ] A new `PrWorkflow` SPI is in place and accessible to future implementors.
- [ ] The legacy PR-review code is refactored into a `ReviewWorkflow` with **zero** behaviour change (regression tests pass).
- [ ] Operators can create workflow configurations (named whitelists of workflows) and assign them to bots via the UI.
- [ ] Operators can create deployment targets and hook them to bots (webhook, CI native, MCP, or static URLs).
- [ ] The first new workflow (`E2ETestWorkflow`) successfully generates, deploys, runs, and reports E2E tests on a sample app in CI.
- [ ] Metrics and observability are in place (Prometheus counters + timers for all workflow runs).
- [ ] All new code has ≥80% line coverage. Flyway migrations work on both H2 and Postgres.
- [ ] Comprehensive docs and recipes exist for each deployment strategy.
- [ ] No breaking changes to existing bot configurations, AI integrations, or Git integrations.

---

## Milestones (7 sequential deliverables)

### ✅ M1 — Foundation: `PrWorkflow` SPI + `ReviewWorkflow` extraction
**Effort:** 1–2 weeks | **Status:** 🔵 Not started

Extract the existing PR-review logic into a pluggable `PrWorkflow` interface and registry. Refactor `BotWebhookService.reviewPullRequest()` to use the new orchestrator.

**Key Deliverables:**
- `PrWorkflow` interface + `PrWorkflowRegistry` (Spring DI auto-collection)
- `PrWorkflowOrchestrator` with lifecycle management (transition matrix: RUNNING → WAITING_DEPLOY → RUNNING → SUCCESS/FAILED, plus CANCELLED on supersession)
- `ReviewWorkflow` extracted 1:1 from existing code
- Flyway `V13__prworkflow_runs.sql` (tables: `pr_workflow_runs`, `pr_workflow_steps`)
- Micrometer metrics: `prworkflow.run_total{workflow,status}` + `prworkflow.run_duration_seconds{workflow}`
- Regression tests: verify existing `BotWebhookServiceTest` suite passes unchanged

**Definition of Done:**
- Code merged; tests ≥80% coverage
- Flyway migration runs clean on H2 + Postgres
- Demo: PR opened → `ReviewWorkflow` runs → comment posted → metrics visible at `/actuator/prometheus`

**Related Issue:** (auto-generated from this epic, linked below as M1-#XYZ)

---

### ✅ M2 — Workflow Configurations CRUD + Bot Assignment
**Effort:** 1 week | **Status:** 🔵 Not started

Operators create reusable workflow configurations (named whitelists of workflows + per-workflow params) and assign them to bots.

**Key Deliverables:**
- `WorkflowConfiguration` + `WorkflowSelection` JPA entities (mirrors `BotToolConfiguration`)
- Flyway `V14__workflow_configurations.sql` (tables + FK) and
  `V15__workflow_configurations_default.sql` (idempotent seed of the
  `Default` configuration, enables the built-in `review` workflow on it, and
  backfills existing bots — no startup-time Java bootstrap)
- `WorkflowConfigurationService` (CRUD + clone + guards)
- New UI: **System settings → Workflow configurations** (list, new, edit, delete, clone, "select workflows" sub-page)
- Updated bot form: new dropdown **Workflow configuration** + **Details** modal
- Per-workflow params form (JSON Schema → HTML inputs)

**Definition of Done:**
- Operators can create/clone/delete workflow configs
- New bots default to `Default`; existing bots backfilled on startup
- Orchestrator iterates workflows in deterministic order

**Related Issue:** M2-#XYZ

---

### ✅ M3 — Deployment Targets (Webhook + Static) + Callback Endpoints
**Effort:** 1–2 weeks | **Status:** 🔵 Not started

Provide the deployment SPI and the two simplest strategies (webhook trigger + static preview URL). Add the async callback channel.

**Key Deliverables:**
- `DeploymentStrategy` interface (methods: `trigger()`, `poll()`, `teardown()`, `awaitsCallback()`)
- `WebhookTriggerStrategy` (signed POST with HMAC-SHA256; awaits callback)
- `StaticPreviewUrlStrategy` (template resolution; optional readiness probe)
- `WorkflowCallbackController` endpoints:
  - `POST /api/workflow-callback/{runId}/{secret}` (verify HMAC, resume orchestrator)
  - `POST /api/workflow-log/{runId}/{secret}` (optional log stream append)
- Flyway `V15__deployment_targets.sql` (tables: `deployment_targets` with encrypted `config_json`)
- New UI: **System settings → Deployment Targets** (list, new, edit, delete, per-strategy form)
- Updated bot form: dropdown **Deployment target** + **Details** modal
- Callback URL recipe snippet (copy-to-clipboard) + operator docs

**Tests:**
- `WebhookTriggerStrategyTest` (WireMock-based)
- `StaticPreviewUrlStrategyTest` (template + probe)
- `WorkflowCallbackControllerTest` (HMAC validation, replay protection)

**Documentation:**
- `doc/PR_WORKFLOWS.md` § "Deployment targets" (JSON schemas, HMAC recipe)
- `doc/PR_WORKFLOWS_WEBHOOK_RECIPES.md` (Jenkins, GitLab, Argo, GitHub Actions)

**Definition of Done:**
- A test webhook callback completes a `PrWorkflowRun` transition
- Forged callbacks rejected with 401
- Operators can copy/paste the callback URL into their CI system

**Related Issue:** M3-#XYZ

---

### ✅ M4 — E2E Test Workflow MVP (first real new workflow)
**Effort:** 2–3 weeks | **Status:** 🔵 Not started

Build the first new workflow: generate → deploy → test → report E2E tests for a PR.

**Key Deliverables:**
- `PrTestWorkspaceManager` (temp sandbox for test files; path traversal guards)
- Flyway `V16__pr_test_suites.sql` (tables: `pr_test_suites`, `pr_test_cases`)
- 5 new built-in tools (category `PR_WORKFLOW`):
  - `pr-test-write(path, content)` — write test file into workspace
  - `pr-test-run(framework, args[])` — execute suite (Playwright/Cypress/k6)
  - `preview-url()` — return current preview URL
  - `preview-status()` — health probe
  - `attach-artifact(path)` — upload screenshots/videos to PR comment
- Three new `AgentStrategy` subclasses:
  - `TestPlannerAgent` (which journeys to test, framework choice)
  - `TestAuthorAgent` (generate test code)
  - `TestRunnerAgent` (execute tests, retry flaky ones, report)
- `E2ETestWorkflow implements PrWorkflow` (orchestrates the three agents, posts markdown summary comment)
- Slash commands: `@bot rerun-tests`, `@bot regenerate-tests [feedback]`
- Lifecycle hook on PR close: teardown deployment
- Cost guards: `maxTestCases` param, token budget from `agent.budget.*`

**Tests:**
- `PrTestWorkspaceManagerTest`
- Per-agent tests: `TestPlannerAgentTest`, `TestAuthorAgentTest`, `TestRunnerAgentTest`
- `E2ETestWorkflowIntegrationTest` (end-to-end with WireMock + test fixtures)

**Documentation:**
- `doc/PR_WORKFLOWS_E2E.md` (recipe, example comment, what to configure)
- Docker Compose snippet bundling Playwright MCP server

**Definition of Done:**
- Sample app in `systemtest/` → PR opens → tests generated + deployed + executed + reported
- Failed tests result in `FAILED` status + clear comment
- Flaky tests tagged `FLAKY` after N retries

**Related Issue:** M4-#XYZ

---

### ✅ M5 — MCP Deployment Strategy
**Effort:** 1 week | **Status:** 🔵 Not started

Allow operators with an internal platform MCP server to use it for deployment orchestration.

**Key Deliverables:**
- `MCPDeploymentStrategy` (reuses `McpOrchestrationService`)
- Config schema: reference MCP config + tool names + args template
- UI: MCP config dropdown + tool-name pickers + args editor
- Whitelist enforcement: save fails if referenced tools aren't whitelisted

**Tests:**
- `MCPDeploymentStrategyTest` (fake MCP server)
- Whitelist enforcement wiring test

**Documentation:**
- Extend `doc/PR_WORKFLOWS.md` with MCP strategy recipe
- Extend `doc/MCP_SERVER_HANDLING.md` about deployment-style tools

**Definition of Done:**
- A `DeploymentTarget` of type `MCP` drives a full workflow run using only MCP tools

**Related Issue:** M5-#XYZ

---

### ✅ M6 — CI Action Strategy (GitHub/Gitea/GitLab/Bitbucket native)
**Effort:** 1 week | **Status:** 🔵 Not started

Reuse each Git provider's native CI instead of a generic webhook.

**Key Deliverables:**
- New `RepositoryApiClient` methods per provider:
  - `dispatchWorkflow(req)` → provider run id
  - `getWorkflowRun(runId)` → status
  - `getWorkflowRunOutputs(runId)` → extract preview URL
- Per-provider implementations (GitHub/Gitea/GitLab/Bitbucket)
- `CiActionTriggerStrategy` (poll-based, not callback-based)
- `CiActionPoller` scheduled task (scans all `WAITING_DEPLOY` runs)
- Per-provider recipe docs (exact step markup to expose `preview_url` output)

**Tests:**
- `CiActionTriggerStrategyTest` per provider (WireMock fixtures)
- `CiActionPollerTest` (many-in-flight, timeouts, status transitions)

**Documentation:**
- `doc/PR_WORKFLOWS_CI_ACTIONS.md` (GitHub, Gitea, GitLab, Bitbucket recipes)

**Definition of Done:**
- GitHub PR triggers a workflow dispatch → bot polls until complete → consumes preview URL → proceeds to testing

**Related Issue:** M6-#XYZ

---

### ✅ M7 — Suite Promotion Workflow (optional terminal feature)
**Effort:** 1 week | **Status:** 🔵 Not started

Let per-PR generated test suites graduate from ephemeral to permanent (repo-level) status.

**Key Deliverables:**
- New `E2ETestWorkflow` param: `suiteLifecycle` ∈ {`ephemeral`, `offer-as-pr`, `promote-on-merge`}
- `SuitePromotionService`:
  - `offer-as-pr`: create follow-up PR with tests under `tests/e2e/pr-{n}/`
  - `promote-on-merge`: on parent PR merge, create PR against default branch (conflict handling: suffix numeric suffix)
- Nightly GC job (delete orphaned `PrTestSuite` rows after 30 days)
- UI: radio group for suite lifecycle in workflow params; link to generated follow-up PR on workflow-run detail view
- Security notes in docs (promoted tests run in standard CI; may need manual secret review)

**Tests:**
- `SuitePromotionServiceTest` (branch creation, conflict handling, idempotency)
- Integration: PR open → tests pass → merged → follow-up PR created exactly once

**Documentation:**
- Extend `doc/PR_WORKFLOWS_E2E.md` (lifecycle modes, security note)

**Definition of Done:**
- Merged PR with `promote-on-merge` results in exactly one follow-up PR containing generated tests
- Ephemeral run leaves no DB rows or temp files after PR close

**Related Issue:** M7-#XYZ

---

## Cross-cutting work (in parallel, not blocking any milestone)

| Task | Target Milestone | Owner | Notes |
|---|---|---|---|
| **Security review** | Before M3 ships | Sec lead | Threat-model callback endpoints, rate-limit `/api/workflow-callback/*`, HMAC strength. |
| **Audit log** | M4 | Backend | Add `PrWorkflowAuditEvent` table for compliance. |
| **RBAC** | M3 | Backend | Operators can create workflow configs; only super-admins can create deployment targets. |
| **Observability** | M4 | DevOps | Grafana dashboard JSON (new Prometheus metrics). |
| **Docker bundling** | M4 | DevOps | Decide: bundle Playwright in image or sidecar? Recommendation: sidecar. |
| **i18n** | per milestone | Translation | All new UI strings via `messages_*.properties`. |
| **Migration guide** | M7 | Docs | New file `doc/MIGRATION_1.6_TO_1.7.md` (additive, backwards-compatible). |

---

## Release Strategy

1. **1.7.0-preview1** (after M1 + M2): Refactored orchestrator + UI. No new workflows exposed. Safe for any deployment.
2. **1.7.0-preview2** (after M3 + M4): E2E workflow usable behind `prworkflow.e2e-test.enabled=false` (default off).
3. **1.7.0 GA** (after M5 + M6 + M7): Full feature set. Flip default to `true` after proven on a real preview environment.

---

## Risk Register

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| AI tool-calling differences across providers (Anthropic vs OpenAI vs Ollama) | Medium | High | Reuse existing fallback (`agent.use_legacy_tool_calling`). M4 validates all three agent types on ≥2 providers. |
| Playwright runtime not in operator's container | Medium | Medium | Ship sidecar example; recommend MCP-based execution. Documented in M4. |
| Callback endpoint abuse / DDoS | Medium | High | Per-run HMAC secret, per-IP rate-limit, one-time token after terminal state. M3 security review. |
| Long `WAITING_DEPLOY` blocking resources | Low | Medium | All waits non-blocking; callback resumes via `TaskExecutor`. M3 timeout guard. |
| Flyway H2 ↔ Postgres divergence | Medium | Medium | Explicit M1–M7 integration tests on both; existing system-test pattern. |
| Operator misconfigures webhook URL / MCP tools | High | Low | Validation at save-time; clear error messages; recipe docs per strategy. |

---

## Success Metrics

By the end of 1.7.0 GA:

- ✅ Zero regression in existing PR-review functionality (test coverage parity).
- ✅ ≥2 sample apps (Java + Node.js) in `systemtest/` demonstrate full E2E workflow.
- ✅ ≥1 cloud provider (GitHub, Gitea, or GitLab) ships native action-dispatch support.
- ✅ Docs cover all four deployment strategies + complete E2E recipe.
- ✅ Metrics dashboard public and consumable.
- ✅ Community feedback from beta testers (expected 3–5 before GA).

---

## Checklist: How to use this issue

1. **Epic parent:** This issue serves as the parent epic. Create 7 child issues (one per milestone), each linked with `relates to` + labeled `milestone-M1`, `milestone-M2`, etc.
2. **Assignment:** Assign milestones to team members based on expertise:
   - **M1–M2:** Backend lead (core architecture + UI integration).
   - **M3:** Backend + DevOps (deployment SPI integration).
   - **M4:** Agent specialist + backend (three new agents on existing infrastructure).
   - **M5:** Backend + Platform team (MCP integration).
   - **M6:** Per-provider lead (GitHub, Gitea, GitLab, Bitbucket experts).
   - **M7:** Backend (optional, can be post-GA if needed).
3. **Tracking:** Mark milestones `In Progress` as code merges. Update this epic with completed/blocked/at-risk status weekly.
4. **Release gating:** M1 + M2 must be done before `1.7.0-preview1` tag; M3 + M4 before `preview2`; M5 + M6 + M7 before GA.

---

## Questions?

- 📖 **Design questions:** See [PR_REVIEW_AGENTIC_WORKFLOWS.md](./PR_REVIEW_AGENTIC_WORKFLOWS.md).
- 🛠️ **Implementation details:** See [PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md).
- 🏗️ **Architecture context:** See [ARCHITECTURE.md](../ARCHITECTURE.md) + [AGENT.md](../AGENT.md).

**Created:** 2026-05-18  
**Last updated:** 2026-05-18

