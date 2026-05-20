# M6 — `CI_ACTION` Deployment: concrete user story & benefits

> **Status:** ✅ shipped in AI-Git-Bot 1.7.0. This document captures the
> user story the milestone targeted; for the operator-facing recipes see
> [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../PR_WORKFLOWS_CI_ACTIONS.md).
>
> **Audience:** stakeholders / SREs asking *"why would I pick
> `CiActionTriggerStrategy` over `WEBHOOK`, `STATIC`, or `MCP`?"*
> **Companion:** [PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md § M6](./PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md)
> for the engineering tasks and acceptance criteria.

---

## 1. The persona

**Sam** is the **SRE** at *Stark Industries Software*. Stark has
standardised hard on **GitHub Actions** for every repo (with a handful
of legacy projects on **GitLab CI** and one acquisition still on
**Bitbucket Pipelines** + a Gitea mirror used by the embedded team).

Every repo already has a reusable workflow:

```
.github/workflows/preview.yml   # workflow_dispatch trigger
.gitlab-ci.yml                  # manual job "preview"
bitbucket-pipelines.yml         # custom pipeline "preview"
```

Sam doesn't want a separate webhook bridge — the bot **already has API
clients** for all four providers (GitHub / GitLab / Gitea / Bitbucket).
Triggering a workflow run is *one extra REST call* away.

---

## 2. The pain today (before M6)

With only `WEBHOOK` and `STATIC` shipped, Sam's options are:

1. **`WEBHOOK`** — duplicate every secret (provider PAT, runner tokens)
   into a second bridge service that just turns webhook calls into
   `workflow_dispatch` API calls. Adds an operational component for
   logic the bot could do itself.
2. **`STATIC`** — Stark's deploys are not preview-per-PR; they only run
   when explicitly requested. Pre-deploying every PR is wasteful and
   doesn't fit the team's cost model.
3. **`MCP`** (M5) — would work, but requires standing up a fresh MCP
   server *only* to wrap calls that the bot's existing
   `RepositoryApiClient` could make directly.

---

## 3. The user story

> **As** Sam the SRE
> **I want** AI-Git-Bot to **dispatch the repo's existing CI workflow**
> (`workflow_dispatch` on GitHub, manual job trigger on GitLab, custom
> pipeline on Bitbucket, Gitea Actions equivalent) using the same API
> credentials the bot already holds
> **so that** zero new services and zero new secrets are required to
> reuse Stark's standard preview pipelines.

### Acceptance criteria (M6 definition of done — all met ✅)

- [x] An operator can pick **`CI_ACTION`** in *Deployment Targets →
      New* and configure: `workflowRef` (e.g. `preview.yml` / GitLab job
      name / Bitbucket pipeline name), `gitRefTemplate`, default `inputs`
      (`{prNumber}` / `{sha}` / `{branch}` / `{branchSlug}` placeholders),
      `previewUrlTemplate`, and `pollIntervalSeconds`.
- [x] Three new SPI methods on `RepositoryApiClient` —
      `dispatchWorkflow(WorkflowDispatchRequest)`,
      `getWorkflowRun(...)`, `getWorkflowRunOutputs(...)` — implemented by
      all four provider clients (`GitHubApiClient`, `GiteaApiClient`,
      `GitLabApiClient`, `BitbucketApiClient`) with WireMock-style unit
      tests and a shared `WorkflowRunStatus` mapping
      (`WorkflowRunStatusMappingTest`).
- [x] `CiActionTriggerStrategy.awaitsCallback() == true` — the strategy
      records the run handle and a scheduled **`CiActionPoller`** queries
      the provider for run status until `READY` / `FAILED`, then publishes
      a `CallbackResult` through the existing `DeploymentCallbackNotifier`
      channel so the orchestrator thread wakes up immediately.
- [x] Result resolution: the poller tries `getWorkflowRunOutputs(...)`
      for the configured `previewUrlOutput` key first (currently used by
      GitLab pipeline variables), then falls back to the deployment
      target's `previewUrlTemplate`. Workflows that prefer to push the
      URL back through the M3 HMAC callback endpoint can still do so —
      the poller then degrades to a pure failure-detector.
- [x] End-to-end operator recipes ship in
      [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](../PR_WORKFLOWS_CI_ACTIONS.md)
      (one section per provider, with the corresponding workflow YAML).
- [x] Reuses existing provider credentials — the bot calls the dispatch
      endpoint with the same `GitIntegration` token it already uses to
      comment on PRs. No new secret per target.
- [x] Test bar: the full Maven test suite is green and includes
      `CiActionTriggerStrategyTest`, `CiActionPollerTest`,
      `WorkflowRunStatusMappingTest`, and per-provider
      `*ApiClientArtifactUploadTest` extensions. For the exact
      regression-test count at the time of release see the version
      history in [`doc/refactoring/README.md`](./README.md).

---

## 4. Concrete benefits

| Benefit                                | Why it matters to Sam                                                                                                                                                |
|----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Zero new services**                  | Trigger flows through the bot's existing `RepositoryApiClient` — no bridge container, no MCP server.                                                                  |
| **Zero new secrets**                   | Uses the bot's existing per-repo provider credentials. No second PAT/token fleet to rotate.                                                                           |
| **Provider-native UX**                 | The CI run shows up in the GitHub Checks tab / GitLab pipeline view / Bitbucket runs UI exactly like a human-triggered run. Familiar dashboards, familiar logs.       |
| **Reuses existing pipelines**          | The `preview.yml` Sam already maintains is the canonical deploy path; the bot does not introduce a parallel one that can drift.                                       |
| **Cost-aware**                         | Only triggers a run when the workflow actually fires (PR opened *and* configured for E2E). No pre-deploy waste.                                                       |
| **Audit native**                       | Every run is traceable in the provider's own audit log — security review-friendly.                                                                                    |
| **Cross-provider portability**         | The same `CI_ACTION` strategy works against all four providers (GitHub / GitLab / Gitea / Bitbucket). Migrating between providers does not require new bot plumbing.  |

---

## 5. Before / after (shipped in 1.7.0)

```
┌──────────────────── BEFORE M6 ────────────────────┐    ┌──────────────── AFTER M6 ───────────────┐
│                                                   │    │                                          │
│  Two options today:                               │    │  AI-Git-Bot ──REST dispatch──▶ provider │
│   • WEBHOOK bridge wrapping workflow_dispatch     │    │        ▲                          │     │
│   • MCP server wrapping workflow_dispatch         │    │        └────── poll run status ──┘     │
│                                                   │    │                                          │
│  Net-new services:        1                       │    │  Net-new services:        0              │
│  Net-new secret fleets:   1                       │    │  Net-new secret fleets:   0              │
└───────────────────────────────────────────────────┘    └──────────────────────────────────────────┘
```

---

## 6. Sequence (happy path, as shipped)

```
PR opened ──▶ E2ETestWorkflow ──▶ CiActionTriggerStrategy.trigger()
                                       │
                                       ▼
                       RepositoryApiClient.dispatchWorkflow(
                         ref="preview.yml",
                         inputs={prNumber, sha, branch})
                                       │
                                       ▼
                       Provider runs the workflow; CiActionPoller queries
                       run status (and optionally `output.text` /
                       `environment.url`) every Ns.
                                       │
                                       ▼
                       On success: parse previewUrl from annotation
                       (or accept callback) → READY
                                       │
                                       ▼
                       Planner → Author → Runner against the preview URL
                                       │
                                       ▼
                       PR closed ──▶ optional dispatchWorkflow(teardown.yml)
```

---

## 7. When to choose `CI_ACTION`

| Choose `CI_ACTION` when…                                                  | Look elsewhere when…                                                                                  |
|---------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| Your CI is GitHub / GitLab / Gitea / Bitbucket and you already have a "deploy preview" workflow there. | Your CI is Jenkins / TeamCity / something else → use `WEBHOOK`.                                        |
| You want the deploy to show up in the provider's native run UI.           | The preview is *always already there* (Vercel, Netlify) → use `STATIC`.                                |
| You want to avoid introducing any extra service or secret store.          | You operate an internal platform MCP server you'd rather route through → use `MCP` (M5).               |
| Annotation-based or callback-based result resolution both work for you.   | Your CI cannot expose either an annotation *or* a callback → you'll need a `WEBHOOK` bridge instead.   |

---

## 8. Design questions — how they were resolved

1. **Annotation contract.** Resolved by **per-target
   `previewUrlOutput`** (a string key into provider-native run outputs,
   currently honoured for GitLab pipeline variables) **with fallback to
   `previewUrlTemplate`** for the common case where the workflow can
   simply expose a predictable URL pattern (`pr-{prNumber}.preview.io`).
   Workflows that need full flexibility can still POST back through the
   M3 HMAC callback channel.
2. **Rate limiting.** Resolved at the **poller** level: `CiActionPoller`
   ticks every 10 s globally and re-queries each active run only when
   its own `pollIntervalSeconds` (clamped 5..120 s, default 15 s) has
   elapsed. The poller batches per provider so a noisy repo never
   starves another.
3. **Re-dispatch semantics.** `@bot rerun-tests` re-enters
   `E2ETestWorkflow` which calls `trigger(...)` again — i.e. a fresh
   `workflow_dispatch` is issued, the previous handle is replaced, and
   the poller picks up the new run. This is the simpler model and
   matches operator expectations from the `WEBHOOK` path.

---

## 9. Hands-on scenario

A laptop-runnable scenario (mock GitHub Actions–style dispatch server +
docker-compose + walkthrough) lives under
[`systemtest/sample-ci-action-server/`](../../systemtest/sample-ci-action-server/)
with the operator walkthrough in
[`systemtest/README-ci-action.md`](../../systemtest/README-ci-action.md).
It exercises the happy path, the `WAITING_DEPLOY → READY` transition
through `CiActionPoller`, and the `failure` and `re-dispatch` paths.

