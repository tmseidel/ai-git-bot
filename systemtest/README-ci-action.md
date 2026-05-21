# M6 — `CI_ACTION` deployment scenario (laptop walkthrough)

> Companion to [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../doc/PR_WORKFLOWS_CI_ACTIONS.md)
> (operator recipe) and
> [`doc/agentic-workflows/CI_ACTION_DEPLOYMENT_USER_STORY.md`](../doc/agentic-workflows/CI_ACTION_DEPLOYMENT_USER_STORY.md)
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

## Running against a *real* Gitea / GitLab / Bitbucket

The walkthrough below targets the **mock** (`sample-ci-action-server`) and
therefore needs no provider-side CI runtime. The moment you point a
`CI_ACTION` deployment target at a real provider — Gitea Actions, GitLab
CI or Bitbucket Pipelines — the **CI subsystem must be enabled and a
runner must be registered**, otherwise the dispatch either 404s or the
pipeline stays queued forever and `CiActionPoller` eventually times out.

| Provider             | What must be enabled                                                                                                         | Runner                                                                                              | Compose helper                                                                                       |
|----------------------|------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| **Gitea Actions** (≥ 1.21) | Instance: `[actions] ENABLED=true` (env `GITEA__actions__ENABLED=true`). Repo: *Settings → Units → Actions*.                  | `act_runner` registered against the instance. Without it, dispatches 200/204 but never start.        | [`docker-compose-local-gitea.yml`](docker-compose-local-gitea.yml) ships both pre-wired — see the file header for the one-time runner-registration command. |
| **GitLab CI**         | Enabled by default for new projects. Verify under *Settings → General → Visibility → CI/CD*.                                | At least one GitLab Runner registered as an *instance* / *group* / *project* runner.                 | [`docker-compose-local-gitlab.yml`](docker-compose-local-gitlab.yml) bundles `gitlab-runner` — see the file header for the one-time `gitlab-runner register` command. |
| **Bitbucket Pipelines** | Per-repo: *Repository settings → Pipelines → Settings → Enable Pipelines*.                                                  | Atlassian-hosted by default. Self-hosted runners only needed for custom infra.                       | n/a (cloud).                                                                                          |
| **GitHub Actions**    | Per-repo: *Settings → Actions → General → Allow all actions* (default for new repos).                                        | GitHub-hosted by default; self-hosted runners only if your workflow needs them.                      | n/a (cloud).                                                                                          |

> 💡 If you see `404 page not found` from `GiteaApiClient.dispatchWorkflow`
> or `… workflow runs … workflow=…`, the Actions subsystem isn't enabled
> on the Gitea instance. If you see the dispatch accepted but
> `CiActionPoller` only ever logs `IN_PROGRESS / QUEUED` until timeout,
> there's no registered runner picking up the job. If the job *starts*
> but `actions/checkout` dies with `Could not resolve host: server`,
> your runner is spawning job containers on the wrong network — see the
> header of `systemtest/gitea-runner/config.yaml`. If the job fails on
> `./scripts/deploy-preview.sh: No such file or directory` or
> `xxd: command not found`, you copy-pasted the production-grade
> recipe; use the **minimal self-contained workflow** in
> [`doc/PR_WORKFLOWS_CI_ACTIONS.md` § Gitea Actions](../doc/PR_WORKFLOWS_CI_ACTIONS.md)
> instead. If the *Notify bot* step fails with
> `curl: (7) Failed to connect to localhost port 8080`, the bot's
> `app.public-url` property is still the default `http://localhost:8080`
> — the workflow runs *inside* a job container where `localhost` means
> the container itself, not your host. On Linux / WSL2 set
> `app.public-url=http://172.17.0.1:8080` (the docker0 bridge gateway,
> reachable from any container on any bridge); on macOS / Windows with
> Docker Desktop use `http://host.docker.internal:8080` instead. Restart
> the bot afterwards. If you've already set `app.public-url` to
> `host.docker.internal` and the step hangs and finally dies with
> `curl: (28) Failed to connect to host.docker.internal port 8080`,
> the *job container* either lacks the `host.docker.internal` mapping
> or has it pointing at an unreachable IP — the cleanest fix on Linux
> is to switch to the docker0 IP (`172.17.0.1`) as above; alternatively
> ensure `systemtest/gitea-runner/config.yaml` carries
> `container.options: "--add-host=host.docker.internal:host-gateway"`
> *and* `docker compose ... up -d --force-recreate runner` (a plain
> `restart` is sometimes not enough to refresh the runner's task
> template).

See [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../doc/PR_WORKFLOWS_CI_ACTIONS.md)
for the workflow-file recipes per provider; the rest of this document
focuses on the mock-server scenario.

---

## Step-by-step walkthrough

> **Prerequisite — Git system: the bot must use a GitHub integration.**
> The mock implements only the GitHub-Actions REST shape (no `/api/v1/`
> prefix, no GitLab `/projects/:id/trigger/pipeline`, no Bitbucket
> `/pipelines/`). The override in Step 2 (`github.api-base-url`) is a
> **GitHub-only** property — it has no effect on `GiteaApiClient` /
> `GitLabApiClient` / `BitbucketApiClient`, which talk to their own
> base URLs. So:
>
> | Bot's Git integration | Works against this mock? | What to do instead                                                                  |
> |-----------------------|--------------------------|-------------------------------------------------------------------------------------|
> | **GitHub**            | ✅ — this walkthrough     | Follow the steps below.                                                              |
> | **Gitea**             | ❌                        | Run against a real Gitea (see [`docker-compose-local-gitea.yml`](docker-compose-local-gitea.yml), header doc for runner registration). |
> | **GitLab**            | ❌                        | Run against a real GitLab (see [`docker-compose-local-gitlab.yml`](docker-compose-local-gitlab.yml), header doc for runner registration). |
> | **Bitbucket**         | ❌                        | Run against bitbucket.org with a custom pipeline (see [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../doc/PR_WORKFLOWS_CI_ACTIONS.md) § Bitbucket Pipelines). |
>
> The full per-provider matrix (Actions / Pipelines flag, runner
> requirement) is in the *"Running against a real Gitea / GitLab /
> Bitbucket"* section above.

### 1. Start the scenario

```bash
docker compose -f systemtest/docker-compose-ci-action.yml up --build
```

Sanity-check both containers came up:

```bash
curl -s http://localhost:3030/healthz   # { ok: true } from the sample app
curl -s http://localhost:8091/healthz   # { ok:true, runs:0, ... }
```

### 2. Point AI-Git-Bot at the mock *(GitHub bots only)*

> ⚠️ This step assumes your bot's *Git integration* is of type
> **GitHub**. The property below is a GitHub-client setting and has no
> equivalent for `GiteaApiClient` / `GitLabApiClient` /
> `BitbucketApiClient` — for those providers, follow the *"Running
> against a real Gitea / GitLab / Bitbucket"* section above instead of
> this mock walkthrough.

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

*System Settings → Deployment Targets → New*. The form only exposes the
target-level fields — **Provider** and **Owner / Repo** are *not* fields
here: at dispatch time `CiActionTriggerStrategy` reads the provider from
the bot's `GitIntegration` and the owner/repo from the PR run itself.
All `CI_ACTION`-specific knobs live in the **Strategy config (JSON)**
textarea.

Form fields:

| Field                  | Value                                                                                                     |
|------------------------|-----------------------------------------------------------------------------------------------------------|
| Name                   | `Preview via mock CI Action`                                                                              |
| Strategy               | **`CI_ACTION`**                                                                                            |
| Preview URL template   | `http://sample-e2e-app:3000`  *(the M4 sample app plays the role of the preview build)*                   |
| Timeout (seconds)      | `120`  *(any value ≥ `RUN_DURATION_MS` + a couple of poll intervals works)*                               |
| Strategy config (JSON) | see below                                                                                                  |

Strategy config (paste into the JSON textarea — this is the same shape
the form's own help text shows next to the **CI_ACTION** label):

```json
{
  "workflowRef": "preview.yml",
  "refTemplate": "refs/heads/{branch}",
  "previewUrlOutput": "preview_url",
  "pollIntervalSeconds": 15,
  "inputs": {
    "callbackUrl": "{callbackUrl}"
  }
}
```

> Notes on the config keys (defined in `CiActionTriggerStrategy`):
> - `workflowRef` — **required.** Workflow file (GitHub / Gitea Actions),
>   pipeline trigger token (GitLab CI) or custom pipeline pattern
>   (Bitbucket Pipelines). See
>   [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../doc/PR_WORKFLOWS_CI_ACTIONS.md)
>   for one recipe per provider.
> - `refTemplate` — optional, defaults to `refs/heads/{branch}`. Placeholders
>   `{prNumber}`, `{sha}`, `{branch}`, `{repoOwner}`, `{repoName}`, `{runId}`,
>   `{callbackUrl}`, `{callbackSecret}` are expanded. The ref must resolve
>   to a **branch or tag** that already exists on the remote — Gitea,
>   GitLab and Bitbucket reject pull-request refs (`refs/pull/N/head`)
>   at dispatch time. Only GitHub Actions also accepts
>   `refs/pull/{prNumber}/head` (needed for fork PRs).
> - `previewUrlOutput` — optional. Name of the workflow output that carries
>   the preview URL (used by providers whose `getWorkflowRunOutputs(...)`
>   returns a map, e.g. GitLab). For the GitHub mock the bot falls back to
>   the top-level **Preview URL template**.
> - `pollIntervalSeconds` — optional, default `15`, clamped to `5..120`.
> - `inputs` — optional map forwarded as workflow inputs / pipeline
>   variables; same placeholder set as `refTemplate`. The example above
>   forwards `{callbackUrl}` so the workflow can POST back to the bot when
>   it finishes (instead of waiting for the poll loop).

Save. The `DeploymentTargetService` save-time validator confirms the
config parses; no whitelist check applies to `CI_ACTION` (the bot can
reach any workflow the provider credentials allow). The mock accepts
*any* `:owner/:repo` path, so whatever your bot's PR happens to live in
(e.g. `acme/web`) will just work.

### 4. Wire the target into the bot

The deployment target is bound to a **Bot**, not to a workflow
configuration. Open *Bots → &lt;your bot&gt; → Edit* and set:

| Field             | Value                          |
|-------------------|--------------------------------|
| Deployment Target | `Preview via mock CI Action`   |

Then make sure the workflow that should consume the preview is enabled
for that bot. Open *System Settings → Workflow Configurations* — the page
lists one row per workflow with a toggle and (if applicable) parameter
fields. For this scenario tick **E2E Tests** (the row backed by
`E2ETestWorkflow`); the **PR Review** row can stay on its current
setting. There is intentionally no deployment-target dropdown on this
page — every enabled workflow uses the deployment target configured on
the bot.

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
[`CI_ACTION_DEPLOYMENT_USER_STORY.md` § 8](../doc/agentic-workflows/CI_ACTION_DEPLOYMENT_USER_STORY.md),
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

