# PR-Review Agentic Workflows — Detailed Implementation Plan

> Companion to [PR_REVIEW_AGENTIC_WORKFLOWS.md](PR_REVIEW_AGENTIC_WORKFLOWS.md).
> This document breaks the seven roadmap milestones into actionable engineering
> tasks (code, database, UI, tests, docs).
> Target version: **AI-Git-Bot 1.7.0**.

---

## Conventions for all milestones

| Aspect | Rule |
|---|---|
| Package root | `org.remus.giteabot.prworkflow` (services, registry, orchestrator). Strategy implementations live under `org.remus.giteabot.prworkflow.deployment.*` and `org.remus.giteabot.prworkflow.e2e.*`. |
| Persistence | New Flyway migrations under `src/main/resources/db/migration/`. Migration numbering continues from the latest existing `V12__bot_tool_configuration_fk.sql` → starts at `V13__`. |
| Encryption | Any new secret column (webhook secrets, deployment-target tokens, callback secrets) **must** be stored encrypted via `EncryptionService`. |
| Backwards compatibility | Every new column on `bots` is **nullable**. Existing bots without a `WorkflowConfiguration` run exactly the legacy `ReviewWorkflow`. |
| Tests | For every new service: unit tests under `src/test/java/...`. For every new controller: MockMvc test mirroring `AdminControllerTest` style. New integration test class per milestone. Target coverage parity with existing modules. |
| Docs | Every milestone updates `doc/USER_GUIDE.md` (operator-facing) and adds/updates the references in this folder. |
| Feature flags | All new workflows ship behind `prworkflow.<name>.enabled` properties, defaulting to `false`. Only `review` (legacy) defaults to `true`. |
| Telemetry | Add Micrometer counters `prworkflow.run_total{workflow,status}` and timer `prworkflow.run_duration_seconds{workflow}` from M1 onwards. |
| Definition of Done (per milestone) | (1) Code merged with tests ≥ 80 % line coverage on new classes, (2) Flyway migration runs clean on H2 and Postgres, (3) docs updated, (4) demo recording / screenshot added under `doc/screenshots/prworkflow/`. |

---

## M1 — Foundation: `PrWorkflow` SPI + `ReviewWorkflow` extraction (1–2 weeks)

### Goal
Refactor the existing PR-review path into a pluggable architecture **without
changing any observable behaviour**.

### Tasks

**1.1 SPI**
- Create `PrWorkflow` interface in `org.remus.giteabot.prworkflow`:
  ```
  String key();                       // e.g. "review"
  String displayName();
  PrWorkflowCategory category();      // REVIEW | TESTING | SECURITY | DOCS | CUSTOM
  WorkflowParamsSchema paramsSchema();
  WorkflowResult run(PrWorkflowContext ctx);
  default boolean supportsCallback() { return false; }
  default WorkflowResult onCallback(PrWorkflowContext ctx, CallbackPayload payload) { ... }
  ```
- Create `PrWorkflowContext` (immutable record): bot, pull request payload, repo
  client, AI client, `PrWorkflowRun` id, allowed built-in tools set, MCP catalog,
  deployment target (nullable), workflow params (JSON node).
- Create `PrWorkflowRegistry` (`@Service`) — Spring DI auto-collects all
  `PrWorkflow` beans; lookup by `key()`. Mirrors `AiProviderRegistry`.
- Add `PrWorkflowCategory` enum and `WorkflowResult` (status + summary +
  artifacts list).

**1.2 Extract `ReviewWorkflow`**
- Move the body of `BotWebhookService.reviewPullRequest(...)` into a new
  `ReviewWorkflow implements PrWorkflow` under
  `org.remus.giteabot.prworkflow.review`. Keep all existing chunking, retry,
  comment-posting logic.
- `BotWebhookService.reviewPullRequest(...)` becomes a thin call to
  `PrWorkflowOrchestrator.run(bot, payload, "review")`.

**1.3 Orchestrator**
- New `PrWorkflowOrchestrator` (`@Service`):
  - Resolves the bot's `WorkflowConfiguration` (M2 adds the column; for now,
    default to `["review"]`).
  - Persists `PrWorkflowRun` rows (status transitions).
  - Catches exceptions per workflow, persists `FAILED` + first 2 KB of stack
    trace into `PrWorkflowStep.logExcerpt`.
  - Emits Micrometer metrics.
- Concurrency: per `(botId, repoOwner, repoName, prNumber, workflowKey)` only
  one `RUNNING`/`WAITING_DEPLOY` row at a time. On a new PR-synchronize event,
  cancel the previous run (transition to `CANCELLED`).

**1.4 Persistence**
- Flyway `V13__prworkflow_runs.sql` creates tables `pr_workflow_runs`,
  `pr_workflow_steps` (see ER diagram in concept doc).
- JPA entities `PrWorkflowRun`, `PrWorkflowStep`; repositories with finders by
  `(botId, repoOwner, repoName, prNumber, status)`.
- `PrWorkflowRunService` with `start()`, `appendStep()`, `complete()`,
  `fail()`, `cancel()`.

**1.5 Tests**
- `PrWorkflowOrchestratorTest`: registry lookup, exception handling, metrics,
  cancel-on-resync semantics.
- `ReviewWorkflowTest`: assert byte-for-byte identical comment payload as the
  pre-refactor `BotWebhookService` for a fixture diff.
- Regression: enable the full existing `BotWebhookServiceTest` suite —
  expectation is **zero changes** to those tests.

**1.6 Docs**
- New `doc/PR_WORKFLOWS.md` (operator-facing top-level doc).
- Update `doc/ARCHITECTURE.md` § "Components" with a paragraph + diagram node
  for `PrWorkflowOrchestrator` + `PrWorkflowRegistry`.

### Acceptance criteria
- All existing tests pass unchanged.
- The legacy PR-review behaviour now flows through the orchestrator.
- `pr_workflow_runs` rows are written for every PR review.
- New metrics visible at `/actuator/prometheus`.

---

## M2 — Workflow Configurations CRUD + Bot Assignment (1 week)

### Goal
Operators can create reusable workflow configurations (named whitelists of
workflow keys + params) and assign them to bots.

### Tasks

**2.1 Persistence**
- Flyway `V14__workflow_configurations.sql`:
  - `workflow_configurations(id, name UK, default_entry, created_at, updated_at)`
  - `workflow_selections(id, workflow_configuration_id FK, workflow_key, params_json)`
    UNIQUE `(workflow_configuration_id, workflow_key)`
  - `bots.workflow_configuration_id` (nullable FK)
- Entities + repositories under `org.remus.giteabot.prworkflow.config`.

**2.2 Services**
- `WorkflowConfigurationService`: CRUD + clone (mirrors
  `BotToolConfigurationService`).
  - Guard: cannot delete a configuration in use by any bot.
  - Guard: cannot delete or rename the `defaultEntry`.
- `WorkflowSelectionService`: add/remove/update selections, JSON-schema
  validation of `params_json` against `PrWorkflow.paramsSchema()`.
- Default-config bootstrap is **purely SQL**: Flyway
  `V15__workflow_configurations_default.sql` seeds the `Default`
  configuration, enables the built-in `review` workflow on it, and backfills
  every existing bot whose `workflow_configuration_id` is still null after
  V14. Any future REVIEW workflow that should be enabled on the Default must
  ship its own follow-up migration; workflows in other categories must be
  opted in via the admin UI — the application never auto-extends the Default
  at runtime.

**2.3 Orchestrator integration**
- `PrWorkflowOrchestrator.runAll(bot, payload)` iterates over the bot's
  `WorkflowConfiguration → WorkflowSelection` rows and invokes each
  `PrWorkflow` sequentially. Order: stable by `workflow_key`.
- If `Bot.workflowConfigurationId` is null, behave like Default.

**2.4 Controllers + UI**
- `WorkflowConfigurationController` under `/system-settings/workflow-configurations`:
  list, new, edit, delete, clone, "select workflows" sub-page (mirror MCP UX).
- Thymeleaf templates under `src/main/resources/templates/system-settings/workflow-configurations/`.
- Per-workflow params editor: dynamic form fields rendered from
  `paramsSchema()` (JSON Schema → simple HTML inputs).
- Update `bots/new.html` and `bots/edit.html`: new dropdown
  **Workflow configuration** + **Details** modal showing enabled workflow keys
  and their params (secrets masked).
- Backfill on upgrade: Flyway sets `workflow_configuration_id` of all existing
  bots to the Default row created by the V15 seed migration.

**2.5 Tests**
- `WorkflowConfigurationServiceTest`: CRUD, guards, clone.
- `WorkflowConfigurationControllerTest`: MockMvc happy-path + validation errors.
- `WorkflowSelectionServiceTest`: schema validation, enable/disable, deterministic ordering.

**2.6 Docs**
- `doc/PR_WORKFLOWS.md` § "Workflow configurations".
- Screenshot for `doc/screenshots/prworkflow/workflow-configurations.png`.

### Acceptance criteria
- Operators can create/clone/edit/delete workflow configurations via the UI.
- New bots default to the Default configuration; existing bots are backfilled
  on startup.
- The orchestrator iterates over selected workflows in deterministic order.

---

## M3 — Deployment Targets (Webhook + Static) + Callback Endpoints (1–2 weeks)

### Goal
Provide the deployment SPI and the two no-extra-deps strategies, including the
async callback channel.

### Tasks

**3.1 SPI**
- Interface `DeploymentStrategy` under
  `org.remus.giteabot.prworkflow.deployment`:
  ```
  String typeKey();                         // WEBHOOK | STATIC | CI_ACTION | MCP
  DeploymentSchema configSchema();
  DeploymentHandle trigger(DeploymentRequest req);   // returns handle (sync or pending)
  DeploymentStatus poll(DeploymentHandle handle);    // optional polling
  void teardown(DeploymentHandle handle);            // optional, may be no-op
  default boolean awaitsCallback() { return false; }
  ```
- `DeploymentRequest`: prNumber, sha, branch, callbackUrl, callbackSecret,
  runId, repo metadata.
- `DeploymentResult` lifecycle states match `PrWorkflowRun.status` for
  `WAITING_DEPLOY`/`RUNNING`/`FAILED`.

**3.2 Persistence**
- Flyway `V15__deployment_targets.sql`:
  - `deployment_targets(id, name UK, strategy_type, config_json_encrypted,
    preview_url_template, timeout_seconds, created_at, updated_at)`
  - `bots.deployment_target_id` (nullable FK)
  - Add columns to `pr_workflow_runs`: `preview_url VARCHAR(2048)`,
    `callback_secret VARCHAR(128)`, `deployment_handle_json TEXT`.
- Entities + repositories.

**3.3 Strategies**
- `WebhookTriggerStrategy`:
  - POST to configured URL with JSON `{prNumber, sha, branch, callbackUrl,
    callbackSecret, runId}`.
  - Sign payload with `X-AI-Bot-Signature: sha256=<hex>` (HMAC over body using
    a per-target shared secret).
  - Returns `WAITING_DEPLOY` immediately (`awaitsCallback() == true`).
- `StaticPreviewUrlStrategy`:
  - Resolves the URL template (`{prNumber}`, `{sha}`, `{branch}` placeholders).
  - Optional readiness probe GET `…/healthz` with retry/backoff up to
    `timeoutSeconds`; on success returns `RUNNING` synchronously.

**3.4 Callback endpoint**
- `WorkflowCallbackController` adds:
  - `POST /api/workflow-callback/{runId}/{secret}` — body
    `{status, previewUrl, logsUrl?, errorMessage?}`.
  - `POST /api/workflow-log/{runId}/{secret}` — appends to
    `PrWorkflowStep.logExcerpt` (truncated 4 KB).
  - HMAC verification using the per-run `callback_secret`.
  - Resumes the orchestrator: transitions `WAITING_DEPLOY → RUNNING` and
    notifies any waiting workflow via a `BlockingQueue` keyed by `runId`
    (in-memory, time-bounded by `timeoutSeconds`).

**3.5 Services + UI**
- `DeploymentTargetService`: CRUD with same guards as workflow configs;
  encrypted persistence of `config_json` via `EncryptionService`.
- `DeploymentTargetController` under `/system-settings/deployment-targets`:
  list, new, edit, delete, with per-strategy dynamic form.
- Bot form: new optional dropdown **Deployment target** + Details modal.
- Show a copy-to-clipboard snippet of the **callback URL pattern** on the
  deployment-target detail page so operators can configure their CI side.

**3.6 Tests**
- `WebhookTriggerStrategyTest` (WireMock-based): signature, payload, timeout.
- `StaticPreviewUrlStrategyTest`: template resolution, readiness probe.
- `WorkflowCallbackControllerTest`: HMAC validation, run resumption, replay
  protection (reject duplicate `status=SUCCESS` after terminal state).
- Integration test: orchestrator drives `WAITING_DEPLOY → RUNNING → SUCCESS`
  via a synthetic callback POST.

**3.7 Docs**
- `doc/PR_WORKFLOWS.md` § "Deployment targets" with:
  - JSON payload schema sent to webhook,
  - JSON callback schema,
  - HMAC verification recipe,
  - example shell `curl` for both directions.
- New `doc/PR_WORKFLOWS_WEBHOOK_RECIPES.md` with concrete recipes for
  Jenkins, GitLab pipeline trigger, Argo CD ApplicationSet, GitHub Actions
  step.

### Acceptance criteria
- A test deployment-target webhook can be triggered and its callback completes
  a `PrWorkflowRun`.
- Replay/forged callbacks are rejected with 401.

---

## M4 — E2E Test Workflow MVP (2–3 weeks)

> **Status — wave 1 ✅ landed.** Workflow skeleton, persistence (Flyway
> `V17__pr_test_suites.sql`), sandbox workspace, pluggable
> `TestSuiteRunner` SPI, default `NoopTestSuiteRunner`, PR comment
> rendering and the `BotWebhookService.handlePrClosed` lifecycle hook
> (`E2eTestPrCloseHandler`) are merged. Test bar: 656/656 green.
>
> **Wave 2 — in progress (iterations 1 – 4 ✅ landed, deferred items remain).**
> The work has been broken into four iterations so each delivery stays
> reviewable; the running test totals reflect what is currently on
> `main`.
>
> | Iteration | Status | Scope | Tests |
> |---|---|---|---|
> | **Wave 2 / Iteration 1** — Tooling layer | ✅ landed | `ToolKind.PR_WORKFLOW`, new `E2E` role in `ToolCatalog`, the 5 built-in tools from §4.3 (`pr-test-write`, `pr-test-run`, `preview-url`, `preview-status`, `attach-artifact`), the provider-agnostic `ArtifactCommentRenderer`, `RepositoryApiClient.attachPullRequestArtifact` default, `PreviewHttpProbe`, `WorkspaceProcessRunner`, `PrWorkflowToolContext`, `PrWorkflowToolExecutor`, `PrTestCaseRepository` query additions. | **676** (+20) |
> | **Wave 2 / Iteration 2** — Agent layer  | ✅ landed | `E2eAgentRunner` (slim tool-calling loop), `E2ePromptLibrary`, `TestPlan` / `TestPlanParser` (tolerant JSON parsing with cost-cap), `TestPlannerAgent` / `TestAuthorAgent` / `TestRunnerAgent`, `PlaywrightTestSuiteRunner` orchestrating planner → author → runner with DB-derived per-case status. `NoopTestSuiteRunner` retained but no longer a Spring bean. | **699** (+23) |
> | **Wave 2 / Iteration 3** — Operator surface | ✅ landed | `E2eTestSlashCommandHandler` dispatching `@bot rerun-tests` / `@bot regenerate-tests [feedback]`, wired into `BotWebhookService.handleBotCommand` + `handlePrComment` and reachable from all four provider webhook handlers (Gitea / GitHub / GitLab / Bitbucket). Flyway `V18__workflow_configurations_full_stack_qa.sql` seeds the opt-in `Full-stack QA` configuration (H2 + PostgreSQL, idempotent, not the default entry). `E2eTestPrCloseHandler` honours `SuiteLifecycleMode.EPHEMERAL` and broadcasts `DeploymentStrategy.teardown(...)` per registered strategy. | **705** (+6) |
> | **Wave 2 / Iteration 4** — Provider polish + sample app | ✅ landed | Per-provider native `attachPullRequestArtifact` overrides for GitLab (`/projects/:id/uploads`), Gitea (`/repos/:o/:r/issues/:n/assets`) and Bitbucket Cloud (`/repositories/:ws/:r/downloads`) with renderer-fallback policy and graceful upload-failure degradation; GitHub deliberately keeps the inline default (no first-class PR-attachment API). Shared `ArtifactUploadSupport` helper. Sample Node app under `systemtest/sample-e2e-app/` + `systemtest/docker-compose-e2e-sample.yml`. Operator-feedback threading: `PrWorkflowContext.hints` map + `PrWorkflowOrchestrator.run(..., hints)` overload + `TestPlannerAgent.PlannerInput.feedback` propagated end-to-end from `@bot regenerate-tests <feedback>` into the planner's user message. | **722** (+17) |
>
> Deliberate simplifications retained from wave 2 (deferred to a future
> wave / iteration):
>
> - The planner only sees the PR diff / title / body / operator feedback —
>   no `cat` / `rg` / `tree` / `get-issue` repo-file-access tools yet.
> - The E2E agents use a dedicated lightweight `E2eAgentRunner` rather
>   than reusing the full `AgentLoop` / Session / Branch infrastructure.
> - A full WireMock-driven integration test exercising the complete
>   planner → deploy → author → run → comment pipeline against the
>   sample app is still open (the existing `E2ETestWorkflowTest`,
>   `PlaywrightTestSuiteRunnerTest` and per-agent unit tests already
>   cover the building blocks; a single composite system test would
>   complement them).
> - Optional Playwright-MCP integration is deferred to post-wave-2.
>
> See [`doc/PR_WORKFLOWS_E2E.md`](../PR_WORKFLOWS_E2E.md) for the
> shipped operator-facing behaviour.

### Goal
First real new workflow: generate, deploy, run, report E2E tests for a PR.

### Tasks

**4.1 PR test workspace**
- New `PrTestWorkspaceManager`:
  - Allocates a temp directory `${java.io.tmpdir}/ai-bot-pr-tests/run-<id>/`.
  - Initializes a minimal scaffolding per chosen framework
    (`package.json` + Playwright config; or `pytest`/`k6` equivalents).
  - Returns a sandboxed path that **never** maps into the repo workspace —
    path traversal guards mirror `WorkspaceFileTools`.

**4.2 Persistence**
- Flyway `V16__pr_test_suites.sql`:
  - `pr_test_suites(id, run_id FK, pr_number, framework, source_tree_ref,
    created_at)`
  - `pr_test_cases(id, suite_id FK, path, content TEXT, last_status,
    last_run_at, last_log TEXT)`
- Entities + repositories under `org.remus.giteabot.prworkflow.e2e`.

**4.3 New built-in tools (category `PR_WORKFLOW`)**
- Implement in `org.remus.giteabot.agent.tools.prworkflow`:
  - `pr-test-write(path, content)` — writes into the workspace, persists/updates
    a `PrTestCase` row.
  - `pr-test-run(framework, args[])` — exec via the existing `ProcessRunner`
    used by `mvn`/`npm` tools; capture stdout/stderr; update `last_status` per
    test if framework exposes structured output (Playwright JSON reporter).
  - `preview-url()` — returns the context's `previewUrl`.
  - `preview-status()` — HTTP probe (configurable path).
  - `attach-artifact(path)` — uploads file as a comment attachment using the
    `RepositoryApiClient` (per-provider implementation; Bitbucket may fall
    back to base64-inlined images).
- Register in `ToolCatalog` with category `PR_WORKFLOW`.
- `DefaultBotToolConfigurationInitializer` adds them additively, but they are
  **disabled by default** for the `Default` workflow configuration — operators
  opt them in via the `Full-stack QA` style configuration.

**4.4 The three agents**
- `TestPlannerAgent extends AgentStrategy` (in
  `org.remus.giteabot.prworkflow.e2e.planner`):
  - Plan schema in `src/main/resources/agent/schemas/e2e-plan.schema.json`:
    `{ "framework": "...", "journeys": [{"id","title","steps":[...],"assertions":[...]}], "maxRetries": N }`
  - Tools: `cat`, `rg`, `tree`, `get-issue`.
  - Output persisted into `PrTestSuite` + skeleton `PrTestCase` rows.
- `TestAuthorAgent` (`org.remus.giteabot.prworkflow.e2e.author`):
  - Tools: `cat`, `pr-test-write`, `patch-file` (workspace-scoped guard).
  - Iterates per journey; writes one test file per `PrTestCase`.
- `TestRunnerAgent` (`org.remus.giteabot.prworkflow.e2e.runner`):
  - Tools: `pr-test-run`, `preview-url`, `preview-status`, `attach-artifact`,
    optionally MCP `playwright_*` tools (when whitelisted on the bot's MCP
    config).
  - Re-run failed tests up to `params.maxRetries`; tag `FLAKY` if flapping.
- All three plug into the existing `AgentLoop` + `chatWithTools` mechanisms;
  native function calling falls back automatically per provider.

**4.5 `E2ETestWorkflow` workflow class**
- Implements `PrWorkflow`:
  1. Resolve `DeploymentTarget` from bot; abort cleanly with PR comment if none.
  2. Run `TestPlannerAgent` against the diff.
  3. Trigger deployment; await `RUNNING` (timeout from target).
  4. Run `TestAuthorAgent` to materialize test files.
  5. Run `TestRunnerAgent` against the preview URL.
  6. Post a Markdown summary comment to the PR:
     ```
     ## E2E Test Run for PR #N
     | Test | Status | Duration |
     | ... | ✅ | 1.2s |
     ```
  7. Optional: post a PR status check `ai-bot/e2e-test` (where supported).
  8. Persist final `PrWorkflowRun.status` and per-`PrTestCase` `last_status`.

**4.6 Slash commands**
- Extend `BotWebhookService.handleBotCommand`:
  - `@bot rerun-tests` → re-trigger the last `E2ETestWorkflow` `PrWorkflowRun`
    for the PR (skipping plan + author).
  - `@bot regenerate-tests [feedback...]` → re-run the full workflow; feedback
    is appended to the planner's user message.

**4.7 Lifecycle on PR close**
- Hook into `BotWebhookService.handlePrClosed`:
  - For each active `PrWorkflowRun` of the PR: call `DeploymentStrategy.teardown`.
  - Delete `PrTestSuite` rows according to the configured suite lifecycle
    (`ephemeral` default; other modes added in M7).

**4.8 Tests**
- `PrTestWorkspaceManagerTest`: path traversal, cleanup.
- `TestPlannerAgentTest`, `TestAuthorAgentTest`, `TestRunnerAgentTest`: each
  with a stub `AiClient` returning canned tool calls.
- `E2ETestWorkflowIntegrationTest`: end-to-end with WireMock for the deployment
  callback and a stub `pr-test-run` that returns a Playwright-style JSON
  report fixture; verifies PR comment markdown.
- Cost guard: an explicit test that aborts when `params.maxTestCases` is
  exceeded.

**4.9 Docs**
- `doc/PR_WORKFLOWS_E2E.md`: end-to-end recipe (configure bot, deployment
  target, Playwright workflow params, what the comment looks like).
- Add a "Try it out" section with a Docker Compose snippet that bundles the
  Playwright MCP server.

### Acceptance criteria
- For a sample app (added to `systemtest/`) a PR opens → bot generates tests
  → triggers deployment (mocked) → executes tests → comments results.
- Failing tests result in `PrWorkflowRun.status = FAILED` and a clearly
  formatted comment.

---

## M5 — MCP Deployment Strategy (1 week)

> **Status — ✅ shipped.** `MCPDeploymentStrategy` (under
> `org.remus.giteabot.prworkflow.deployment.mcp`) plus the
> `McpDeploymentConfig` / `McpDeploymentTemplating` helpers landed
> together with admin-UI integration and save-time whitelist
> enforcement. The strategy is **poll-based** —
> `awaitsCallback() == false` — and reuses the existing
> `McpOrchestrationService.executeTool(...)` path, so no new transport
> code was needed. Operator-facing recipe in
> [`doc/PR_WORKFLOWS.md` § Deployment targets](../PR_WORKFLOWS.md#deployment-targets-m3)
> and [`doc/MCP_SERVER_HANDLING.md` § 6](../MCP_SERVER_HANDLING.md#6-exposing-deployment-style-tools-m5).
> Test bar: **754** green (+32 vs. M4 wave 2 iteration 4).

### Goal
Allow operators with an existing platform MCP server to use it instead of (or
in addition to) webhook/static strategies.

### Tasks

**5.1 Strategy**
- `MCPDeploymentStrategy implements DeploymentStrategy` under
  `org.remus.giteabot.prworkflow.deployment.mcp`.
- Config JSON schema:
  ```
  { "mcpConfigurationId": <long>,
    "deployToolName": "deploy-pr-preview",
    "statusToolName": "get-preview-status",
    "teardownToolName": "teardown-preview",
    "argsTemplate": { "prNumber": "{prNumber}", "sha": "{sha}", ... } }
  ```
- `trigger()`: resolves the referenced `McpConfiguration`, invokes the tool via
  the existing `McpOrchestrationService.callTool(...)` path, parses the
  response into a `DeploymentHandle`.
- `poll()` / `teardown()`: same pattern for `statusToolName`/`teardownToolName`.

**5.2 UI**
- Deployment-target edit form: when `strategyType = MCP`, the form shows:
  - MCP configuration dropdown (filtered to those exposing the selected tools).
  - Tool-name pickers populated from the MCP configuration's whitelist.
  - Args-template editor (JSON with placeholder hints).

**5.3 Validation**
- Service-level check: the referenced MCP tools must be in the MCP
  configuration's selected-tools whitelist (`McpToolSelectionService`),
  otherwise the save is rejected with an explanatory message.

**5.4 Tests**
- `MCPDeploymentStrategyTest` against a fake MCP server.
- Wiring test: the strategy honours the MCP whitelist enforcement
  (rejects an unlisted tool name at runtime).

**5.5 Docs**
- Extend `doc/PR_WORKFLOWS.md` § "Deployment targets" with the MCP strategy
  recipe.
- Add a section to `doc/MCP_SERVER_HANDLING.md` about exposing
  deployment-style tools.

### Acceptance criteria
- A `DeploymentTarget` of type `MCP` can drive a full `PrWorkflowRun` using
  only MCP tools.

---

## M6 — CI Action Strategy (1 week)

> **Status:** ✅ shipped. See
> [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../PR_WORKFLOWS_CI_ACTIONS.md) for the
> operator-facing recipes (one per provider) and
> `org.remus.giteabot.prworkflow.deployment.CiActionTriggerStrategy` /
> `CiActionPoller` for the implementation.

### Goal
Reuse the Git provider's native CI (GitHub Actions, Gitea Actions, GitLab CI,
Bitbucket Pipelines) instead of a custom webhook endpoint.

### Tasks

**6.1 Provider extensions**
- Extend `RepositoryApiClient` with new methods (default impls throwing
  `UnsupportedOperationException`):
  ```
  String dispatchWorkflow(WorkflowDispatchRequest req);  // returns provider run id
  WorkflowRunStatus getWorkflowRun(String runId);        // queued/in_progress/completed
  Map<String,String> getWorkflowRunOutputs(String runId);// for preview_url extraction
  ```
- Per-provider implementations:
  - `GitHubApiClient`: `POST /repos/{o}/{r}/actions/workflows/{id}/dispatches`,
    poll `GET .../actions/runs/...`, read `outputs` of last job.
  - `GiteaApiClient`: same path (Gitea Actions ≥ 1.21); gate behind a feature
    probe.
  - `GitLabApiClient`: `POST /projects/{id}/trigger/pipeline`; poll
    `/pipelines/{id}`; read pipeline variables for preview URL.
  - `BitbucketApiClient`: `POST .../pipelines/` with `selector: { type:
    "custom", pattern: "<name>" }`; poll pipeline status.

**6.2 Strategy**
- `CiActionTriggerStrategy implements DeploymentStrategy`:
  - Config: workflow file path / pipeline name, ref convention
    (`refs/pull/{n}/head`), output variable name for `preview_url`, polling
    interval, timeout.
  - `trigger()`: calls `dispatchWorkflow(...)`, stores provider run id in
    `deployment_handle_json`.
  - `poll()`: scheduled via `@Scheduled(fixedDelay = ...)` in
    `CiActionPoller` (one task scans all `WAITING_DEPLOY` runs of type
    `CI_ACTION`); updates status when complete; extracts `preview_url` from
    outputs.
  - `awaitsCallback() == false`; this strategy is poll-based.

**6.3 UI**
- Deployment-target form: when `strategyType = CI_ACTION`, fields adapt to the
  selected Git provider (workflow file vs. pipeline name).

**6.4 Tests**
- `CiActionTriggerStrategyTest` per provider via WireMock fixtures.
- `CiActionPollerTest`: many-in-flight handling, status transitions, timeout
  → `FAILED`.

**6.5 Docs**
- `doc/PR_WORKFLOWS_CI_ACTIONS.md` with one recipe per provider, including
  the exact step a workflow author must add to expose `preview_url` as output.

### Acceptance criteria
- A GitHub-hosted PR triggers a workflow run via the bot, the bot polls until
  completion and consumes the `preview_url` output to proceed with
  `E2ETestWorkflow`. ✅
- The strategy is operator-selectable from the deployment-target form. ✅
- Per-provider unit tests cover the status mapping (`mapGitHubStatus`,
  `mapGitLabStatus`, `mapBitbucketStatus`) and the dispatch happy path /
  rejection paths in `CiActionTriggerStrategyTest`. ✅
- `CiActionPoller` survives orphaned handles (non-CI_ACTION JSON, missing
  integration, provider 404) without poisoning other in-flight runs;
  covered by `CiActionPollerTest`. ✅

---

## M7 — Suite Promotion Workflow (optional, 1 week)

### Goal
Allow the per-PR generated test suite to leave its ephemeral status and become
part of the repository under operator control.

### Tasks

**7.1 New workflow params**
- `E2ETestWorkflow.paramsSchema()` gains:
  ```
  "suiteLifecycle": { "enum": ["ephemeral", "offer-as-pr", "promote-on-merge"] }
  ```

**7.2 `offer-as-pr` mode**
- After a successful run, create a follow-up branch
  `ai-tests/pr-{n}` based on the feature branch.
- Write all `PrTestCase` files under `tests/e2e/pr-{n}/`.
- Reuse `RepositoryApiClient.createBranch`/`commitFile`/`createPullRequest`
  (already used by the coding agent).
- The follow-up PR title: `Add E2E tests for #N`, body links the original PR
  and lists tests.
- Persist the follow-up PR number on the `PrWorkflowRun`.

**7.3 `promote-on-merge` mode**
- Listen for PR `closed && merged == true` events on the parent PR.
- If a `PrWorkflowRun` of type `e2e-test` with status `SUCCESS` exists, open
  a follow-up PR against the **default branch** with the tests under
  `tests/e2e/`.
- Conflict policy: if `tests/e2e/<file>` already exists, the bot appends a
  numeric suffix `_2`, `_3`, … and notes it in the PR description.

**7.4 Teardown semantics**
- `ephemeral`: delete workspace + DB rows immediately on PR close.
- `offer-as-pr`/`promote-on-merge`: keep `PrTestSuite`/`PrTestCase` rows until
  the follow-up PR is merged or closed; then garbage-collect via a nightly
  `@Scheduled` job.

**7.5 UI**
- Add a `Suite lifecycle` radio group to the `e2e-test` workflow params form.
- Workflow-run detail view: link to the generated follow-up PR (if any).

**7.6 Tests**
- `SuitePromotionServiceTest`: branch creation, conflict handling, idempotency
  on retries.
- Integration: PR opened → tests generated and pass → PR merged →
  promotion-mode triggers follow-up PR exactly once.

**7.7 Docs**
- Extend `doc/PR_WORKFLOWS_E2E.md` with the three lifecycle modes, including
  a security note that promoted tests run in the standard CI and may need
  manual secret review.

### Acceptance criteria
- A merged PR with `promote-on-merge` results in exactly one follow-up PR
  containing the generated tests.
- An ephemeral run leaves no DB rows or temp files behind after PR close.

---

## Cross-cutting work (parallel to M3+)

These tasks do not block a single milestone but should be picked up early.

| Topic | Description | When |
|---|---|---|
| **Security review** | Threat-model the new callback endpoints and webhook signing. Rate-limit `/api/workflow-callback/...` per IP + per runId. | Before M3 ships |
| **Audit log** | Add `PrWorkflowAuditEvent` table capturing who triggered which workflow run, for compliance environments. | M4 |
| **Permissions** | Extend admin-role checks so that *operators* (not just super-admins) can create/edit `WorkflowConfiguration` but only super-admins can create `DeploymentTarget`. | M3 |
| **Observability** | Grafana dashboard JSON for the new Prometheus metrics, shipped under `doc/observability/`. | M4 |
| **Docker image size** | Decide whether to bundle Playwright in the base image or document a sidecar approach (Playwright MCP container). Recommendation: sidecar — keeps the main image lean. | M4 |
| **i18n** | All new UI strings go through the existing `messages_*.properties`. | per milestone |
| **Migration guide** | Update `doc/MIGRATION_1.0_TO_1.1.md` style — new file `doc/MIGRATION_1.6_TO_1.7.md` describing the additive nature of all changes. | M7 (or first GA release containing the feature) |

---

## Release strategy

- M1 + M2 ship together as **1.7.0-preview1** (architecture refactor + UI; no
  user-visible new workflow, safe to deploy).
- M3 + M4 ship as **1.7.0-preview2** (first new workflow usable behind a flag).
- M5 + M6 + M7 ship as **1.7.0** GA.
- Throughout the preview phase, the `e2e-test` workflow stays behind
  `prworkflow.e2e-test.enabled=false` by default. GA flips the default to
  `true` only after at least one full M4-style integration test against a
  real preview environment.

---

## Risk register (implementation-time)

| Risk | Owner | Mitigation |
|---|---|---|
| Native tool calling differs subtly per AI provider when used for the three new agents | Agent maintainer | Reuse existing `AgentLoop` legacy fallback + `agent.use_legacy_tool_calling` toggle described in [AGENT.md](../AGENT.md). |
| Playwright runtime not present in operator's runtime | Ops | Ship sidecar Docker Compose example; document required system packages; recommend MCP-based execution to externalise the runtime. |
| Callback endpoint scanning / abuse | Sec | HMAC + per-run secret (rotated, single-use post-terminal), per-IP rate-limit. |
| Long-running `WAITING_DEPLOY` runs block resources | Perf | All waits are non-blocking (orchestrator returns after `trigger()`; callback resumes work via a `TaskExecutor`). |
| Flyway migration on Postgres vs. H2 divergence (existing setup uses both) | Backend | Add explicit migration tests for both, mirroring `BotServiceTest`'s H2 setup and the Postgres-based system tests under `systemtest/`. |

