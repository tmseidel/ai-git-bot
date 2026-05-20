# M6 — `CI_ACTION` deployment scenario (laptop walkthrough)

> Companion to [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../doc/PR_WORKFLOWS_CI_ACTIONS.md)
> (operator recipe) and
> [`doc/refactoring/CI_ACTION_DEPLOYMENT_USER_STORY.md`](../doc/refactoring/CI_ACTION_DEPLOYMENT_USER_STORY.md)
> (persona / benefits). This file is the runnable counterpart that lets
> you actually see `CiActionTriggerStrategy` + `CiActionPoller` move a
> deployment from `WAITING_DEPLOY` → `READY` (or → `FAILED`) on your
> laptop without touching real `github.com`.

## What this scenario boots

| Container                   | Image                                  | Port  | Role                                                                                                                |
|-----------------------------|----------------------------------------|-------|----------------------------------------------------------------------------------------------------------------------|
| `sample-e2e-app`            | `ai-git-bot/sample-e2e-app:dev`        | 3030  | The M4 login app. Plays the role of the already-deployed PR preview build (target of `previewUrlTemplate`).         |
| `sample-ci-action-server`   | `ai-git-bot/sample-ci-action-server:dev` | 8091 | Mock GitHub-Actions-style REST API. Implements **only** the three endpoints the bot actually calls. In-memory state. |

Both join the shared `ai-git-bot-e2e` Docker network so an already-running
AI-Git-Bot can be attached to the same network and point its
`github.api-base-url` at `http://sample-ci-action-server:8091`.

## What the mock exposes

GitHub-Actions–compatible endpoints (what `GitHubApiClient` actually uses):

```
POST /repos/:owner/:repo/actions/workflows/:workflow/dispatches
     body: { "ref": "...", "inputs": { ... } }   -> 204
GET  /repos/:owner/:repo/actions/workflows/:workflow/runs?per_page=1
                                                  -> { workflow_runs:[{id}] }
GET  /repos/:owner/:repo/actions/runs/:run_id     -> { status, conclusion }
```

Operator helpers (not part of GitHub's real API — for the walkthrough):

```
GET  /_admin/runs           list in-memory runs with ageMs
POST /_admin/fail-next      next dispatch resolves to conclusion="failure"
POST /_admin/reset          clear all runs
GET  /healthz               { ok, runs, failNext, failAll, runDurationMs }
```

Env knobs (see `docker-compose-ci-action.yml`):

| Var               | Default | Effect                                                                  |
|-------------------|---------|--------------------------------------------------------------------------|
| `RUN_DURATION_MS` | `5000`  | How long a run stays `in_progress` before flipping to `completed`.       |
| `FAIL_ALL`        | `false` | When `true`, every run finishes with `conclusion: "failure"`.            |

`RUN_DURATION_MS=5000` is intentional: with `CiActionPoller` ticking every
10s and a default `pollIntervalSeconds=15`, you get **one or two
`WAITING_DEPLOY` polls of `IN_PROGRESS` before the transition to `READY`** —
exactly the lifecycle the orchestrator was built to demonstrate.

---

## Step-by-step walkthrough

### 1. Start the scenario

```bash
docker compose -f systemtest/docker-compose-ci-action.yml up --build
```

Sanity-check both containers came up:

```bash
curl -s http://localhost:3030/healthz   # { ok: true } from the sample app
curl -s http://localhost:8091/healthz   # { ok:true, runs:0, ... }
```

### 2. Point AI-Git-Bot at the mock

In your local `application-dev.properties` (or however you run the bot):

```properties
# Override the GitHub API base URL so GitHubApiClient hits the mock
# instead of api.github.com. Keep the rest of your GitHub integration
# (token, webhook secret, …) as-is.
github.api-base-url=http://sample-ci-action-server:8091
```

If the bot itself is *not* in Docker, drop the override and use
`http://localhost:8091` — the mock listens on the host port too.

### 3. Configure a `CI_ACTION` deployment target in the UI

*System Settings → Deployment Targets → New*. Fields:

| Field                      | Value                                                                                  |
|----------------------------|----------------------------------------------------------------------------------------|
| Name                       | `Preview via mock CI Action`                                                            |
| Strategy type              | **`CI_ACTION`**                                                                         |
| Provider                   | `GITHUB`                                                                                |
| Owner / Repo               | `acme` / `web`  *(any string is fine — the mock accepts anything)*                       |
| `workflowRef`              | `preview.yml`                                                                           |
| `gitRefTemplate`           | `refs/pull/{prNumber}/head`                                                              |
| `inputs` (JSON)            | `{ "pr_number": "{prNumber}", "branch": "{branch}" }`                                   |
| `previewUrlTemplate`       | `http://sample-e2e-app:3000`  *(the M4 sample app plays the role of the preview build)* |
| `pollIntervalSeconds`      | `15`                                                                                    |

Save. The `DeploymentTargetService` save-time validator confirms the
config parses; no whitelist check applies to `CI_ACTION` (the bot can
reach any workflow the provider credentials allow).

### 4. Wire the target into a PR workflow configuration

*System Settings → Workflow Configurations → Full-stack QA* (or any
configuration that runs `E2ETestWorkflow`) → set **Deployment Target =
`Preview via mock CI Action`**.

### 5. Trigger a run

Open a PR on a repo the bot owns and either let it auto-trigger or
comment `@bot rerun-tests`. Watch the bot's log for:

```
o.r.g.p.d.CiActionTriggerStrategy : CI_ACTION dispatched workflow 'preview.yml'
    (run id=1000001) for PR #N on acme/web (ref=refs/pull/N/head)
```

…and the mock's log for the matching `dispatch` line. The `PrWorkflowRun`
moves to `WAITING_DEPLOY`.

### 6. Observe the poll loop

Wait ~15s. `CiActionPoller` calls
`GET /repos/acme/web/actions/runs/1000001` and sees `status: "in_progress"`
once or twice, then `status: "completed", conclusion: "success"`. The
poller publishes a `CallbackResult` and the bot's log shows:

```
o.r.g.p.deployment.CiActionPoller : CiActionPoller terminal status=READY
    delivered=true for run id=N (preview=http://sample-e2e-app:3000)
```

The orchestrator wakes up, `E2ETestWorkflow` runs `Planner → Author →
Runner` against `http://sample-e2e-app:3000` (your sample app), and posts
the result back as a PR comment.

You can also peek at the mock's in-memory state at any time:

```bash
curl -s http://localhost:8091/_admin/runs | jq
```

### 7. Failure-path smoke test

Force the next dispatch to fail:

```bash
curl -X POST http://localhost:8091/_admin/fail-next
```

Trigger another run (`@bot rerun-tests`). After the same ~5s window,
`getWorkflowRun` returns `status: "completed", conclusion: "failure"`.
The poller logs:

```
o.r.g.p.deployment.CiActionPoller : CiActionPoller terminal status=FAILED
    delivered=true for run id=N (preview=null)
```

…and the orchestrator marks the `PrWorkflowRun` as `FAILED` without ever
invoking the planner agent — exactly the `WorkflowRunStatusMappingTest`
contract.

### 8. Re-dispatch semantics

Per the resolution to design question #3 in
[`CI_ACTION_DEPLOYMENT_USER_STORY.md` § 8](../doc/refactoring/CI_ACTION_DEPLOYMENT_USER_STORY.md),
`@bot rerun-tests` re-enters `E2ETestWorkflow` which calls
`trigger(...)` again — i.e. the mock receives a fresh dispatch with a
new `run id`, and the poller picks up the new handle. Verify in
`/_admin/runs`: you'll see two entries for the same PR, the second
strictly newer.

### 9. Tear down

```bash
docker compose -f systemtest/docker-compose-ci-action.yml down -v
```

---

## Mapping to the production code

| Bot component                       | What the mock simulates                                                   |
|-------------------------------------|---------------------------------------------------------------------------|
| `CiActionTriggerStrategy.trigger()` | `POST .../dispatches` + 15s "wait for new run id" loop                    |
| `CiActionPoller.tick()`             | `GET .../actions/runs/:id` every `pollIntervalSeconds`                    |
| `GitHubApiClient.dispatchWorkflow`  | The two endpoints above, end-to-end                                       |
| `GitHubApiClient.getWorkflowRun`    | The status/conclusion mapping covered by `WorkflowRunStatusMappingTest`    |
| `previewUrlTemplate`                | The placeholder-expansion fallback when `getWorkflowRunOutputs == {}`     |
| `DeploymentCallbackNotifier`        | How the poller wakes the orchestrator without operator-visible plumbing   |

## Caveats

* The mock does **not** implement GitHub's auth (`Authorization` header
  is ignored). It is a laptop scenario, not a security-test target.
* `getWorkflowRunOutputs` is not exercised here — `GitHubApiClient`
  intentionally returns `Map.of()` for GitHub. To exercise the
  outputs-resolution path use the GitLab or Bitbucket recipe in
  [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../doc/PR_WORKFLOWS_CI_ACTIONS.md).
* The mock only speaks the GitHub-Actions shape; pointing a `GITLAB` /
  `BITBUCKET` / `GITEA` deployment target at it will hit endpoints the
  mock doesn't implement and surface 404s from the corresponding API
  client — which is itself a useful smoke test for the per-provider
  client wiring.

