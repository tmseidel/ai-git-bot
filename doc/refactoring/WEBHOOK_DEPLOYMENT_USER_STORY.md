# M3 — `WEBHOOK` Deployment: concrete user story & benefits

> **Audience:** stakeholders / DevOps engineers asking *"why would I pick
> `WebhookTriggerStrategy` over `STATIC`, `MCP` (M5), or `CI_ACTION`
> (M6)?"*
> **Companion:** [doc/PR_WORKFLOWS.md → Deployment targets](../PR_WORKFLOWS.md#deployment-targets-m3)
> for the protocol details.

---

## 1. The persona

**Priya** is the **DevOps Engineer** at *Globex Industries*. Globex runs
a heterogeneous CI estate — most builds happen in **self-hosted Jenkins**
on bare metal, a few legacy services still use **TeamCity**, and a brand
new "innovation lab" lives on GitHub Actions. None of these are exposed
to the public internet; everything sits behind Globex's corporate VPN.

Priya owns the Jenkins job `preview/deploy-pr` that already knows how to:

1. Check out the PR branch.
2. Build the Docker image.
3. `kubectl apply` it into the `previews` namespace with a per-PR host.
4. POST a JSON `{previewUrl, status}` blob to whatever URL the caller
   passed in `CALLBACK_URL`.

The job took two sprints to harden — Priya is *not* re-implementing it.

---

## 2. The pain before M3

Without a generic deployment SPI Priya had three options, all bad:

1. **Pre-deploy every PR via a Jenkins post-commit hook** and feed the
   URL to the bot via `STATIC`. Burns cluster capacity even for PRs the
   bot is never asked to test.
2. **Patch the bot** to speak Jenkins' REST API directly. Couples the bot
   to one CI vendor; impossible upstream.
3. **Run a sidecar bridge** that polls Jenkins and pretends to be a
   webhook. Two systems, two failure modes, two on-call rotations.

---

## 3. The user story

> **As** Priya the DevOps Engineer
> **I want** AI-Git-Bot to *trigger my existing Jenkins job* over a
> generic webhook and *await an HMAC-signed callback* with the preview
> URL
> **so that** the same hardened Jenkins pipeline that already deploys PR
> previews is reused unchanged — no new CI surface, no vendor lock-in,
> no duplicated deploy logic.

### Acceptance criteria (all shipped in M3)

- [x] An operator can pick **`WEBHOOK`** in *Deployment Targets → New*
      and configure URL + headers + payload template.
- [x] The bot signs every outbound trigger with **HMAC-SHA256** using the
      target's shared secret (`X-AiGitBot-Signature` header) so the
      receiver can verify provenance.
- [x] The bot exposes
      **`POST /api/workflow-callback/{runId}/{callbackSecret}`** that
      accepts `{previewUrl, status, error?}` and transitions the run to
      `READY` / `FAILED`.
- [x] The callback secret is **per-run** (not per-target) — leaking one
      cannot poison future PRs.
- [x] `WebhookTriggerStrategy.awaitsCallback() == true` — the bot does
      not poll; Priya's Jenkins side controls the timing.
- [x] An operator-facing recipe ships in `doc/PR_WORKFLOWS.md` with a
      working Jenkinsfile snippet.

---

## 4. Concrete benefits

| Benefit                              | Why it matters to Priya                                                                                                                                                                                            |
|--------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **CI-vendor neutral**                | Jenkins today, TeamCity tomorrow, a shell script over SSH the day after. Any system that can `curl` a URL back can play.                                                                                            |
| **Zero changes to existing pipelines** | The existing Jenkins job already accepted `CALLBACK_URL`. One extra `curl -X POST -H "Authorization: Bearer $SECRET"` line at the end is all Priya writes.                                                          |
| **HMAC-signed outbound triggers**    | Jenkins verifies the bot really sent the trigger; the bot verifies the callback secret. Replays past the per-run secret are impossible.                                                                            |
| **On-demand, not pre-deploy**        | Globex pays for preview cluster capacity only when a PR is *actually* sent through the E2E workflow.                                                                                                                |
| **Fits behind corporate firewalls**  | Jenkins lives in the VPN; only one outbound hole (callback POST to the bot) is needed. No inbound port from the bot to the cluster.                                                                                 |
| **Lifecycle hooks for free**         | The PR-close lifecycle hook (M4) also fires a webhook — so Priya can wire `kubectl delete namespace preview-pr-{prNumber}` to the same job's `teardown` action.                                                     |

---

## 5. Before / after

```
┌──────────────────── BEFORE M3 ────────────────────┐    ┌──────────────── AFTER M3 ───────────────┐
│                                                   │    │                                          │
│  Manual: developer pings Priya in chat,           │    │  AI-Git-Bot ──HMAC POST──▶ Jenkins job  │
│  Priya kicks Jenkins job, copy/paste URL          │    │        ▲                          │     │
│  back into PR description, ask bot to retest      │    │        └────HMAC callback─────────┘     │
│                                                   │    │                                          │
│  Mean time PR opened → tests run: ~2 hours        │    │  Mean time PR opened → tests run: ~3min │
│  Manual hand-offs per PR: 3                       │    │  Manual hand-offs per PR: 0              │
└───────────────────────────────────────────────────┘    └──────────────────────────────────────────┘
```

---

## 6. Sequence (happy path)

```
PR opened ──▶ E2ETestWorkflow ──▶ WebhookTriggerStrategy.trigger()
                                       │
                                       ▼
                       POST {webhookUrl} with HMAC signature
                       body: {runId, prNumber, sha, branch,
                              repoOwner, repoName, callbackUrl,
                              callbackSecret}
                                       │
                                       ▼
                       Jenkins job runs, deploys preview, then
                       POST /api/workflow-callback/{runId}/{secret}
                       body: {"previewUrl":"https://pr-7.previews.globex.io",
                              "status":"ready"}
                                       │
                                       ▼
                       Bot transitions run → READY
                                       │
                                       ▼
                       Planner → Author → Runner against the preview URL
                                       │
                                       ▼
                       PR closed ──▶ optional teardown webhook fires
```

---

## 7. When to choose `WEBHOOK`

| Choose `WEBHOOK` when…                                          | Look elsewhere when…                                                                 |
|-----------------------------------------------------------------|--------------------------------------------------------------------------------------|
| Your deploy lives in a CI system the bot doesn't natively speak. | Your CI is GitHub Actions / GitLab CI / Gitea / Bitbucket → consider `CI_ACTION` (M6). |
| You can let the CI side POST back to the bot.                    | The CI side is fire-and-forget with no callback channel → use `MCP` (M5) polling instead. |
| You want full control over deploy timing & payload shape.        | Previews are *already* pre-deployed per PR by Vercel/Netlify → use `STATIC`.         |

