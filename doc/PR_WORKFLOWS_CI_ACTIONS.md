# PR Workflows — `CI_ACTION` Deployment Strategy

> Status: shipped in **AI-Git-Bot 1.7.0** (milestone M6 of the PR-review
> agentic workflows refactor). Companion document to
> [`PR_WORKFLOWS.md`](./PR_WORKFLOWS.md) and
> [`PR_WORKFLOWS_WEBHOOK_RECIPES.md`](./PR_WORKFLOWS_WEBHOOK_RECIPES.md).
>
> **Hands-on:** a laptop-runnable scenario (mock GitHub-Actions-style
> dispatch+poll server + docker-compose + walkthrough including the
> failure path and re-dispatch semantics) lives at
> [`systemtest/README-ci-action.md`](../systemtest/README-ci-action.md).

The `CI_ACTION` deployment strategy lets the bot trigger a per-PR preview
deployment by dispatching the Git host's **native** CI system instead of
calling a custom webhook. It works against:

| Provider | Native CI | Dispatch API |
|---|---|---|
| GitHub | GitHub Actions | `POST /repos/{owner}/{repo}/actions/workflows/{file}/dispatches` |
| Gitea (≥ 1.21) | Gitea Actions | same shape under `/api/v1/...` |
| GitLab | GitLab CI | `POST /projects/{path}/trigger/pipeline` |
| Bitbucket Cloud | Bitbucket Pipelines | `POST /2.0/repositories/{owner}/{repo}/pipelines/` |

The bot never needs a new outbound webhook endpoint, a separate secret store
or a second auth dance — every call uses the same `GitIntegration` token the
bot already uses to comment on PRs.

---

## Why pick `CI_ACTION` over `WEBHOOK`?

Choose `CI_ACTION` when **all** of the following hold:

* Your deploy script already lives in the same provider's CI
  (`.github/workflows/preview.yml`, `.gitea/workflows/preview.yml`,
  `.gitlab-ci.yml`, `bitbucket-pipelines.yml`).
* You don't want to expose an extra ingress to the bot (no `webhookUrl`,
  no shared HMAC secret).
* Your operators are happy to manage the CI workflow with the rest of the
  application code, side-by-side with normal pushes.

Pick `WEBHOOK` instead if you ship your deploys from a CI system the bot
doesn't natively speak (Jenkins, CircleCI, Drone, Concourse, …). See
[`WEBHOOK_DEPLOYMENT_USER_STORY.md`](./refactoring/WEBHOOK_DEPLOYMENT_USER_STORY.md)
for that path.

---

## How the bot drives the run

1. **Trigger.** When a workflow asks for a deployment, the bot calls the
   provider's dispatch endpoint with the configured `workflowRef`, the
   resolved `gitRef`, and any operator-defined `inputs`. The provider returns
   a run id (GitHub Actions / Gitea Actions / GitLab) or pipeline UUID
   (Bitbucket); the bot stores it in `pr_workflow_runs.deployment_handle_json`
   together with the `provider`, `owner`, `repo` and `pollIntervalSeconds`.
2. **Wait.** The run is marked `WAITING_DEPLOY`. The orchestrator thread
   blocks on the existing `DeploymentCallbackNotifier` queue exactly as it
   does for `WEBHOOK`-style strategies — see
   [`PR_WORKFLOWS.md` § "Async deployment callbacks"](./PR_WORKFLOWS.md).
3. **Poll.** The scheduled `CiActionPoller` (default tick: 10 s) re-queries
   every active `CI_ACTION` run at its own configured cadence
   (`pollIntervalSeconds`, clamped to 5..120). When the workflow reaches a
   terminal state the poller publishes a `CallbackResult` so the orchestrator
   thread wakes up immediately.
4. **Resolve URL.** On `COMPLETED_SUCCESS` the poller tries, in order:
   1. `getWorkflowRunOutputs(...)` for the configured `previewUrlOutput` key
      (currently only GitLab pipeline variables expose this natively);
   2. the deployment target's `previewUrlTemplate` (placeholders:
      `{prNumber}`, `{sha}`, `{branch}`);
   3. nothing — the workflow succeeded but no preview URL is available;
      preview-dependent downstream workflows treat this as a no-op.

If the workflow itself wants to push back a dynamic preview URL it can do so
the same way `WEBHOOK` strategies do: POST to the bot's `callbackUrl` (passed
through as a workflow input — see the recipes below). The poller is then a
pure failure-detector.

---

## Deployment-target schema

`deployment_targets.config_json` (encrypted at rest):

```jsonc
{
  // REQUIRED. Workflow file (GitHub/Gitea), pipeline trigger token (GitLab)
  // or custom pipeline pattern (Bitbucket).
  "workflowRef": "preview.yml",

  // Optional. Default "refs/heads/{branch}". Supports placeholders
  // {prNumber}, {sha}, {branch}, {repoOwner}, {repoName}.
  "refTemplate": "refs/pull/{prNumber}/head",

  // Optional. Default "preview_url". Key the poller looks for in
  // getWorkflowRunOutputs(...). GitLab returns pipeline variables here;
  // GitHub/Gitea/Bitbucket return an empty map and fall back to the
  // previewUrlTemplate.
  "previewUrlOutput": "preview_url",

  // Optional. Default 15 s. Clamped to [5, 120].
  "pollIntervalSeconds": 15,

  // Optional. Forwarded verbatim to the dispatch as workflow_dispatch
  // inputs (GitHub/Gitea), variables[KEY] (GitLab), or pipeline variables
  // (Bitbucket). Placeholders are applied the same way as refTemplate.
  "inputs": {
    "callback_url":    "{callbackUrl}",
    "callback_secret": "{callbackSecret}",
    "preview_branch":  "pr-{prNumber}"
  }
}
```

On top of `config_json` the deployment target also carries the standard
`previewUrlTemplate` (placeholders documented above) used as the URL fallback
on GitHub / Gitea / Bitbucket.

---

## Recipes per provider

### GitHub Actions

`.github/workflows/preview.yml`:

```yaml
name: preview
on:
  workflow_dispatch:
    inputs:
      callback_url:
        description: "Bot callback URL"
        required: true
      callback_secret:
        description: "Bot callback secret"
        required: true
      preview_branch:
        description: "Synthetic branch name (e.g. pr-42)"
        required: true
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Deploy preview
        id: deploy
        run: |
          ./scripts/deploy-preview.sh "${{ inputs.preview_branch }}"
          echo "preview_url=https://${{ inputs.preview_branch }}.preview.example.com" >> "$GITHUB_OUTPUT"
      - name: Notify bot
        if: always()
        run: |
          STATUS=READY
          [ "${{ job.status }}" != "success" ] && STATUS=FAILED
          curl -fsS -X POST "${{ inputs.callback_url }}" \
            -H "Content-Type: application/json" \
            -H "X-AI-Bot-Signature: $(printf '%s' "$STATUS" | openssl dgst -sha256 -hmac '${{ inputs.callback_secret }}' -binary | xxd -p -c 256 | awk '{print "sha256="$1}')" \
            -d "{\"status\":\"$STATUS\",\"previewUrl\":\"${{ steps.deploy.outputs.preview_url }}\"}"
```

Deployment-target config:

```json
{ "workflowRef": "preview.yml", "refTemplate": "refs/heads/{branch}",
  "inputs": { "callback_url": "{callbackUrl}", "callback_secret": "{callbackSecret}",
              "preview_branch": "pr-{prNumber}" } }
```

> ⚠️ `workflow_dispatch` only works on the branch's default workflow file —
> the file must already be present on the ref you dispatch (typically the PR
> head branch). For forks GitHub silently no-ops the dispatch; in that case
> use `refs/heads/main` and pass the PR's SHA through the inputs.

### Gitea Actions (≥ 1.21)

Identical workflow file structure to GitHub Actions. Place it under
`.gitea/workflows/preview.yml`. The deployment-target config is the same as
GitHub. Make sure Gitea Actions is **enabled per-repo** (`Settings → Repo
units → Actions`) and that the bot's PAT has the `write:repository` scope.

### GitLab CI

`.gitlab-ci.yml` snippet:

```yaml
preview:
  rules:
    - if: $CI_PIPELINE_SOURCE == "trigger"
  script:
    - ./scripts/deploy-preview.sh "$PREVIEW_BRANCH"
    - |
      curl -fsS -X POST "$CALLBACK_URL" \
        -H "Content-Type: application/json" \
        -d "{\"status\":\"READY\",\"previewUrl\":\"https://${PREVIEW_BRANCH}.preview.example.com\"}"
  variables:
    preview_url: "https://${PREVIEW_BRANCH}.preview.example.com"   # exposed via getWorkflowRunOutputs
```

Generate a **pipeline trigger token** under `Settings → CI/CD → Pipeline
triggers` and use it as `workflowRef`. Deployment-target config:

```json
{ "workflowRef": "glptt-…trigger-token…",
  "refTemplate": "{branch}",
  "previewUrlOutput": "preview_url",
  "inputs": { "CALLBACK_URL": "{callbackUrl}", "PREVIEW_BRANCH": "pr-{prNumber}" } }
```

GitLab exposes the pipeline's trigger variables via
`GET /pipelines/:id/variables`, so the bot can read the resolved
`preview_url` even without an explicit callback POST.

### Bitbucket Pipelines

`bitbucket-pipelines.yml`:

```yaml
pipelines:
  custom:
    preview:
      - variables:
          - name: CALLBACK_URL
          - name: CALLBACK_SECRET
          - name: PREVIEW_BRANCH
      - step:
          name: Deploy preview
          script:
            - ./scripts/deploy-preview.sh "$PREVIEW_BRANCH"
            - |
              curl -fsS -X POST "$CALLBACK_URL" \
                -H "Content-Type: application/json" \
                -d "{\"status\":\"READY\",\"previewUrl\":\"https://${PREVIEW_BRANCH}.preview.example.com\"}"
```

Deployment-target config:

```json
{ "workflowRef": "preview",
  "refTemplate": "{branch}",
  "inputs": { "CALLBACK_URL": "{callbackUrl}", "CALLBACK_SECRET": "{callbackSecret}",
              "PREVIEW_BRANCH": "pr-{prNumber}" } }
```

> The bot uses Bitbucket's `pipeline_ref_target` with `ref_type: branch`; if
> you need to run against a SHA instead, set `refTemplate` to
> `"{sha}"`.

---

## Operational tips

* **Polling load.** Each in-flight CI_ACTION run is polled at its own
  `pollIntervalSeconds`. The base scheduler ticks every 10 s
  (`prworkflow.ci-action.poll-interval-ms`) — bump that for very large
  instances if you see provider rate-limit pressure.
* **Multi-instance deployments.** Both `CiActionPoller` and
  `DeploymentCallbackNotifier` are in-process; the same caveat as for the
  `WEBHOOK` strategy applies — a poll started by instance A cannot wake a
  blocked thread on instance B. Single-instance is the supported topology.
* **Timeouts.** The orchestrator's blocking wait honours
  `deployment_targets.timeout_seconds`; if no terminal status arrives within
  that window the run transitions to `FAILED` with a "timed out" message,
  independently of whether the CI workflow itself eventually completes.
* **Secrets.** The bot never stores provider secrets — only the
  `GitIntegration` token already used for PR comments. Workflows that need
  to call back the bot receive the per-run `callbackSecret` as a workflow
  input; never check it into the repository.
* **Disabling.** The strategy is registered automatically by Spring; to
  remove it from the operator UI, override
  `DeploymentTargetService.availableStrategyTypes()` or hide the option in
  `templates/system-settings/deployment-targets/form.html`.

---

## Limitations

* GitHub Actions does not expose per-job `outputs` over the REST API. The
  poller therefore relies on the `previewUrlTemplate` fallback (or an
  explicit `callbackUrl` POST from the workflow) when running against
  GitHub. Same for Gitea Actions and Bitbucket Pipelines.
* `workflow_dispatch` is rate-limited per provider; for very busy
  monorepos prefer `WEBHOOK` with a job queue (Jenkins, Argo Workflows, …).
* The strategy does not stream live logs back to the PR; the dispatched
  workflow is fully autonomous after the initial trigger.

---

## See also

* [`PR_WORKFLOWS.md`](./PR_WORKFLOWS.md) — overall deployment-target model.
* [`PR_WORKFLOWS_WEBHOOK_RECIPES.md`](./PR_WORKFLOWS_WEBHOOK_RECIPES.md) —
  same level of detail for the `WEBHOOK` strategy.
* [`refactoring/CI_ACTION_DEPLOYMENT_USER_STORY.md`](./refactoring/CI_ACTION_DEPLOYMENT_USER_STORY.md)
  — stakeholder rationale (Sam the SRE).
* [`refactoring/PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md` § M6](./refactoring/PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md)
  — implementation plan & acceptance criteria.


