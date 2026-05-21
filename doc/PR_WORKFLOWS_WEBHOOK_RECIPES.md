# Webhook recipes for deployment-target callbacks

> Companion to [`PR_WORKFLOWS.md` → Deployment targets](PR_WORKFLOWS.md#deployment-targets-m3).
> All snippets assume the bot's public base URL is `https://bot.acme.io`.

For every recipe the bot POSTs a JSON envelope first, signed with the
target's `sharedSecret` (`X-AI-Bot-Signature: sha256=<hex>` — verify the
header on the CI side if you need defence-in-depth). The CI side is then
responsible for calling back to
`POST /api/workflow-callback/{runId}/{callbackSecret}` once the preview
environment is ready, signing the callback body with the per-run
`callbackSecret` that the bot delivered in the trigger payload — **never the
`sharedSecret`**. Using the per-run secret means a leaked signature cannot
be replayed across runs.

The trigger payload that the bot sends to your CI:

```json
{ "runId": 42, "prNumber": 1234, "sha": "abc…", "branch": "feature/x",
  "repoOwner": "acme", "repoName": "web",
  "callbackUrl":    "https://bot.acme.io/api/workflow-callback/42/<callbackSecret>",
  "callbackSecret": "<callbackSecret>" }
```

---

## 1. Jenkins (declarative pipeline)

Trigger the build via Jenkins' remote-build URL (configured per job, "Trigger
builds remotely → Authentication token"). The pipeline records the bot
callback URL, builds the preview environment, then calls back.

```groovy
pipeline {
    agent any
    parameters {
        string(name: 'callbackUrl',  defaultValue: '')
        string(name: 'callbackSecret', defaultValue: '')
        string(name: 'prNumber',     defaultValue: '')
        string(name: 'sha',          defaultValue: '')
    }
    stages {
        stage('Deploy preview') { steps {
            sh "./scripts/deploy-preview.sh ${params.prNumber} ${params.sha}"
        } }
        stage('Notify bot') { steps {
            script {
                def body  = "{\"status\":\"READY\",\"previewUrl\":\"https://pr-${params.prNumber}.preview.acme.io\"}"
                def sig   = sh(returnStdout: true, script: "printf %s '${body}' | openssl dgst -sha256 -hmac \"${params.callbackSecret}\" -hex | awk '{print \$2}'").trim()
                sh "curl -fsS -X POST -H 'Content-Type: application/json' -H 'X-AI-Bot-Signature: sha256=${sig}' --data '${body}' '${params.callbackUrl}'"
            }
        } }
    }
}
```

The bot's webhook target config:
```json
{ "webhookUrl": "https://jenkins.acme.io/job/preview/buildWithParameters?token=...",
  "sharedSecret": "..." }
```

---

## 2. GitLab CI (pipeline trigger token)

Bot target points at GitLab's `pipeline` trigger; the pipeline reads
`callbackUrl`/`callbackSecret` via `$CALLBACK_URL`/`$CALLBACK_SECRET`
variables.

`.gitlab-ci.yml`:
```yaml
preview:
  stage: deploy
  rules: [{ if: '$CALLBACK_URL' }]
  script:
    - ./scripts/deploy-preview.sh "$PR_NUMBER" "$SHA"
    - >
      BODY="{\"status\":\"READY\",\"previewUrl\":\"https://pr-${PR_NUMBER}.preview.acme.io\"}";
      SIG="sha256=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$CALLBACK_SECRET" -hex | awk '{print $2}')";
      curl -fsS -X POST -H "Content-Type: application/json" -H "X-AI-Bot-Signature: $SIG" --data "$BODY" "$CALLBACK_URL"
```

Bot target config (the webhook URL is GitLab's
`/api/v4/projects/<id>/trigger/pipeline?token=<token>` endpoint; the bot
sends the JSON envelope as the request body):
```json
{ "webhookUrl": "https://gitlab.acme.io/api/v4/projects/42/trigger/pipeline?token=...&ref=main",
  "sharedSecret": "...",
  "headers": { "X-AI-Bot-Mapping": "envelope-as-variables" } }
```
(Map the envelope to GitLab `variables[...]` form in your GitLab adapter, or
front it with a small relay.)

---

## 3. GitHub Actions (`repository_dispatch`)

Bot target dispatches a workflow event; the workflow checks out, deploys,
then calls back.

`.github/workflows/preview.yml`:
```yaml
on:
  repository_dispatch:
    types: [ai-git-bot-preview]
jobs:
  preview:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { ref: ${{ github.event.client_payload.sha }} }
      - run: ./scripts/deploy-preview.sh ${{ github.event.client_payload.prNumber }}
      - name: Notify ai-git-bot
        env:
          CB:      ${{ github.event.client_payload.callbackUrl }}
          SECRET:  ${{ github.event.client_payload.callbackSecret }}
          PR:      ${{ github.event.client_payload.prNumber }}
        run: |
          BODY='{"status":"READY","previewUrl":"https://pr-'"$PR"'.preview.acme.io"}'
          SIG="sha256=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')"
          curl -fsS -X POST -H "Content-Type: application/json" -H "X-AI-Bot-Signature: $SIG" --data "$BODY" "$CB"
```

Bot target config (the bot's envelope is delivered verbatim as
`client_payload` because the bot wraps it for `repository_dispatch`):
```json
{ "webhookUrl": "https://api.github.com/repos/acme/web/dispatches",
  "sharedSecret": "...",
  "headers": { "Accept": "application/vnd.github+json",
               "Authorization": "Bearer <gh-pat>" } }
```

---

## 4. Gitea Actions (`workflow_dispatch`, Gitea ≥ 1.24)

> ⚠️ **Use the `CI_ACTION` strategy for this**, not `WEBHOOK`.
> Gitea's [`ActionsDispatchWorkflow`](https://docs.gitea.com/api/1.25/#tag/repository/operation/ActionsDispatchWorkflow)
> endpoint requires a structured body `{ "ref": "...", "inputs": { ... } }`.
> A `WEBHOOK` target would POST the bot's envelope verbatim and Gitea
> rejects it with `HTTP 422 [Ref]: Required`. The `CI_ACTION` strategy
> formats the body correctly, reuses the `GitIntegration` token the bot
> already has on the repo (no second secret), and polls the dispatched run
> until completion. Recipe verified against Gitea **1.25.5**; full
> reference: [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](PR_WORKFLOWS_CI_ACTIONS.md).

### Where does the `callbackUrl` come from?

The bot owns the per-run callback URL. In `CI_ACTION` mode you simply
declare a `callbackUrl` *input* on the workflow and let the bot inject the
fully-qualified value through the strategy's `inputs` map — the
`{callbackUrl}` placeholder is substituted at dispatch time:

```
                          ┌───────────────────────────────────────────────┐
  bot ── CI_ACTION dispatch│ POST /api/v1/repos/{o}/{r}/actions/workflows/ │
                           │      preview.yml/dispatches                   │
                           │ Body: {"ref":"refs/heads/feature/x",          │
                           │        "inputs": {                            │
                           │          "callbackUrl":                       │
                           │            "https://bot.acme.io/api/          │
                           │             workflow-callback/42/<secret>"    │
                           │ } }                                           │
                           └───────────────────────────────────────────────┘
                                          │
                                          ▼
  workflow run ── ${{ inputs.callbackUrl }} ── curl -d '{"status":"READY",
                                                       "previewUrl":"…"}'
```

### Deployment-target config

In the bot UI: **Strategy = `CI_ACTION`**, then in *Strategy config (JSON)*:

```json
{
  "workflowRef":       "preview.yml",
  "refTemplate":       "refs/heads/{branch}",
  "previewUrlOutput":  "preview_url",
  "pollIntervalSeconds": 15,
  "inputs": {
    "callbackUrl": "{callbackUrl}"
  }
}
```

Notes:
* `workflowRef` is the **filename** of the workflow under `.gitea/workflows/`.
* `refTemplate` must resolve to a real branch — Gitea rejects PR refs
  (`refs/pull/N/head`) at dispatch time. Default `refs/heads/{branch}`
  is normally what you want.
* `inputs.callbackUrl` uses the literal placeholder `{callbackUrl}`; the
  bot replaces it with the per-run URL before dispatching. Other
  available placeholders: `{prNumber}`, `{sha}`, `{branch}`,
  `{callbackSecret}`, `{runId}`.
* No `webhookUrl` / `sharedSecret` here — the bot reuses the
  `GitIntegration` access token configured for the repo.

### `.gitea/workflows/preview.yml`

```yaml
name: preview
on:
  workflow_dispatch:
    inputs:
      callbackUrl:
        description: "ai-git-bot callback URL (auto-injected)"
        required: true

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./scripts/deploy-preview.sh
        id: deploy
      - name: Notify ai-git-bot
        if: always()
        env:
          CB:     ${{ inputs.callbackUrl }}
          URL:    ${{ steps.deploy.outputs.preview_url }}
          STATUS: ${{ job.status == 'success' && 'READY' || 'FAILED' }}
        run: |
          curl -fsS -X POST "$CB" \
            -H "Content-Type: application/json" \
            -d "{\"status\":\"$STATUS\",\"previewUrl\":\"$URL\"}"
```

> ℹ️ **Don't combine `on: pull_request` with the bot's `CI_ACTION` dispatch
> on the same workflow** — the workflow would fire twice per PR (once
> automatically from the push, once from the bot dispatch) and the second
> run would race the first into the same preview slot. If you need a
> manual fallback, keep `workflow_dispatch` (so you can trigger it from
> the Gitea UI) but drop `pull_request` from this file or move the
> automatic path into a separate workflow that *doesn't* call back to the
> bot.

### Pre-flight checklist

* **Gitea Actions enabled** for the repo (`Settings → Actions → enabled`).
* **At least one runner registered** (repo / owner / global). Without a
  runner the dispatched workflow stays queued forever and the bot's
  `timeoutSeconds` guard fails the run. See
  [`systemtest/gitea-runner/config.yaml`](../systemtest/gitea-runner/config.yaml)
  for a minimal local setup.
* **Workflow file committed on the branch you target as `ref`** — Gitea
  resolves the workflow definition from that ref, *not* from the default
  branch. A common gotcha: the operator merges `preview.yml` to `main`
  but the feature branch doesn't have it yet → dispatch returns
  `HTTP 404 workflow not found`.
* **`GitIntegration` access token scope** includes `write:repository` (a
  read-only token returns `HTTP 403`).
* **No `pull_request:` trigger on the same file** when the bot drives
  it via `CI_ACTION` (see note above).

### Common errors

| Symptom | Cause |
|---|---|
| `Webhook returned HTTP 422 [Ref]: Required` | Strategy is `WEBHOOK` — switch to `CI_ACTION`. The bot envelope cannot be reshaped into the `{ref, inputs}` body Gitea wants. |
| `HTTP 404 workflow not found` | Workflow file missing on the dispatched ref (see pre-flight bullet 3). |
| Workflow dispatched, never callback | No runner registered, or the runner's labels don't match `runs-on`. Check `Actions → Runners` in Gitea. |
| `inputs.callbackUrl` is empty in the workflow | The bot's `inputs` map is missing the `"callbackUrl": "{callbackUrl}"` line. |

---

## 5. Argo CD ApplicationSet (PR generator)

Argo CD's PR generator auto-provisions a per-PR app — there is nothing for
the bot to trigger, so the recommended setup is the `STATIC` strategy with
the bot's own readiness probe (see below).

If you do want an explicit callback (instead of polling), have Argo CD's
`PostSync` hook fire a Job that calls back. Because this path does **not**
go through a `WEBHOOK` deployment-target, the bot has not seeded a per-run
`callbackSecret` for the Job — pass the full `callbackUrl` (which embeds
the secret as path segment) into the Job from an external orchestrator and
HMAC-sign the body with that same per-run secret:

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: notify-ai-bot
  annotations:
    argocd.argoproj.io/hook: PostSync
spec:
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: notify
          image: curlimages/curl:8
          env:
            - { name: CB,             value: "$(CALLBACK_URL)" }
            - { name: CALLBACK_SECRET, value: "$(CALLBACK_SECRET)" }
            - { name: URL,            value: "https://pr-$(PR_NUMBER).preview.acme.io" }
          command: ["sh","-c"]
          args:
            - |
              BODY='{"status":"READY","previewUrl":"'"$URL"'"}'
              SIG="sha256=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$CALLBACK_SECRET" -hex | awk '{print $2}')"
              curl -fsS -X POST -H "Content-Type: application/json" -H "X-AI-Bot-Signature: $SIG" --data "$BODY" "$CB"
```

Recommended bot target: use `STATIC` instead of `WEBHOOK` (the
ApplicationSet already provisions) — the readiness probe is then handled by
the bot itself:
```json
{ "healthcheckPath": "/healthz", "expectedStatus": 200, "intervalSeconds": 10 }
```
with `previewUrlTemplate = https://pr-{prNumber}.preview.acme.io`.

---

## Common pitfalls

| Symptom | Likely cause |
|---|---|
| HTTP 401 from callback | Wrong `{secret}` path segment, signature header signed with the target's `sharedSecret` instead of the per-run `callbackSecret`, or body doesn't match the signature byte-for-byte (watch out for trailing newlines added by `echo` — use `printf %s`). |
| HTTP 409 from callback | The run already transitioned to a terminal status (timeout, superseded by a newer PR-synchronize). |
| Bot keeps waiting forever | The trigger response was 2xx but no callback ever arrived. Raise `timeoutSeconds` on the target or check the CI side for swallowed errors. |
| Signature header missing | Optional but recommended; without it the callback only relies on the URL secret. Enforce by setting `requireSignature=true` (M4). |

