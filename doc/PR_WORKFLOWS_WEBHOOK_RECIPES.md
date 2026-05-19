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

## 4. Argo CD ApplicationSet (PR generator)

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

