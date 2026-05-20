# M5 — MCP Deployment: concrete user story & benefits

> **Audience:** stakeholders, platform engineers, anyone asking *"why would I
> pick `MCPDeploymentStrategy` over `WEBHOOK` or `CI_ACTION`?"*
> **Companion:** [doc/MCP_SERVER_HANDLING.md § 6](../MCP_SERVER_HANDLING.md#6-exposing-deployment-style-tools-m5)
> for the protocol details and [systemtest/README-mcp-deployment.md](../../systemtest/README-mcp-deployment.md)
> for a runnable scenario on your laptop.

---

## 1. The persona

**Alex** is the **Platform Engineer** at *Acme Corp*. Alex owns Acme's
internal developer-platform service — a small Go binary that already drives
per-PR preview environments on the team's Kubernetes cluster. The platform
exposes its capabilities over **MCP** (Model Context Protocol) because the
same surface is consumed by Cursor / Claude Desktop / internal copilots.

Alex's team has three operations they already publish as MCP tools:

| Existing MCP tool             | What it does                                                      |
|-------------------------------|-------------------------------------------------------------------|
| `platform__deploy_preview`    | Spin up a namespace, deploy the PR build, return a public URL.    |
| `platform__preview_status`    | Return `pending` / `ready` / `failed` + the URL once ready.       |
| `platform__teardown_preview`  | Delete the namespace.                                             |

Alex's developers use AI-Git-Bot for PR review. The team would like the bot
to also drive the **E2E test workflow** (M4) against the same preview
environments — *without* duplicating the deploy logic in a webhook, a
GitHub Action, or a Jenkinsfile.

---

## 2. The pain before M5

Before `MCPDeploymentStrategy` existed Alex had three options, all bad:

1. **`WEBHOOK` strategy.** Stand up *another* HTTP service that wraps the
   MCP tools, signs HMAC callbacks, and POSTs back to the bot. Two systems
   to operate, two audit trails, two failure modes.
2. **`CI_ACTION` strategy.** Re-implement "deploy preview" inside a
   GitHub workflow, duplicating credentials and templating. Drift from
   the canonical platform implementation guaranteed within a sprint.
3. **`STATIC` strategy.** Pre-deploy *every* PR via existing CI and only
   hand the URL to the bot. Burns cluster capacity on PRs that nobody
   asked the bot to test.

Each option re-implements something Alex already exposes correctly over
MCP.

---

## 3. The user story

> **As** Alex the Platform Engineer
> **I want** AI-Git-Bot to drive per-PR preview deployments by calling my
> existing platform MCP tools
> **so that** I don't have to operate a second webhook surface, copy
> credentials into CI, or invent a new deploy path *just* for the bot.

### Acceptance criteria (all shipped in M5)

- [x] An operator can pick **`MCP`** in *Deployment Targets → New* and
      reference an MCP configuration that is already whitelisted under
      *System settings → MCP configurations*.
- [x] The form lets the operator choose **`deployTool` / `statusTool` /
      `teardownTool`** by their qualified `mcp:<server>:<tool>` name.
- [x] The form **refuses to save** when any referenced tool is not on the
      whitelist (`HTTP 400` with an actionable message) — Alex cannot
      accidentally widen the agent's tool surface.
- [x] The bot **polls** `statusTool` on its existing schedule —
      `awaitsCallback() == false` — so Alex does **not** need to expose
      an inbound HMAC endpoint from the cluster back to the bot.
- [x] Whitelist enforcement runs **a second time at runtime**: removing a
      tool after the fact downgrades the run to `REJECTED` instead of
      silently executing.
- [x] An end-to-end recipe is available under `systemtest/` so a developer
      can reproduce the scenario on a laptop in <2 minutes.

---

## 4. Concrete benefits

| Benefit                                | Why it matters to Alex                                                                                                                                                              |
|----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Zero extra integration surface**     | The platform already speaks MCP for Cursor / internal copilots. The bot becomes *just another MCP client* — no new HTTP service, no HMAC fleet, no duplicated secrets.              |
| **One whitelist, one audit trail**     | Tool selection lives where it already lives (*System settings → MCP configurations → Tools*). Every deploy/teardown is logged through `McpOrchestrationService` like any other call. |
| **No inbound callback channel needed** | The bot polls — Alex's cluster does **not** need to reach the bot. Critical when the bot lives in a separate VPC / tenancy.                                                          |
| **Same code path as agents**           | `MCPDeploymentStrategy` reuses `McpOrchestrationService.executeTool(...)`; transport, retries, telemetry come for free.                                                              |
| **Safe by default**                    | Save-time + runtime whitelist checks (`McpToolSelectionService.selectedQualifiedToolNameSet(...)`) ensure that disabling a tool degrades to `REJECTED`, never to a silent execution. |
| **Templating without code**            | `argsTemplate` (Mustache-style placeholders documented in `MCP_SERVER_HANDLING.md` § 6) maps PR metadata onto whatever shape the existing tool already expects.                     |
| **Same lifecycle the team knows**      | `deploy → status (poll) → teardown` mirrors the existing tool semantics — no new "bot deployment" mental model to teach.                                                             |

---

## 5. Before / after

```
┌──────────────────── BEFORE M5 ────────────────────┐    ┌──────────────── AFTER M5 ───────────────┐
│                                                   │    │                                          │
│  AI-Git-Bot ──HMAC POST──▶ Webhook bridge ──▶ MCP │    │  AI-Git-Bot ──MCP tool call──▶ Platform │
│        ▲                          │               │    │        ▲                            │   │
│        └────HMAC callback─────────┘               │    │        └────── poll statusTool ─────┘   │
│                                                   │    │                                          │
│  Components Alex must operate: 2                  │    │  Components Alex must operate: 0 (new)   │
│  Inbound channels into bot's VPC: 1               │    │  Inbound channels into bot's VPC: 0      │
│  Secrets duplicated: HMAC + MCP token             │    │  Secrets duplicated: none                │
└───────────────────────────────────────────────────┘    └──────────────────────────────────────────┘
```

---

## 6. Sequence (happy path)

```
PR opened ──▶ E2ETestWorkflow ──▶ MCPDeploymentStrategy.trigger()
                                       │
                                       ▼
                       McpOrchestrationService.executeTool(deployTool, args)
                                       │
                              ┌────────┴────────┐
                              │                 │
                  returns {previewUrl}     returns {handle}
                              │                 │
                              ▼                 ▼
                       READY immediately   PENDING — DeploymentPoller
                                                 calls statusTool every Ns
                                                 until READY / FAILED.
                                       │
                                       ▼
                       TestPlannerAgent → TestAuthorAgent → TestRunnerAgent
                                       │
                                       ▼
                       PR comment with results + artifact
                                       │
                                       ▼
                       PR closed → MCPDeploymentStrategy.teardown()
                                       │
                                       ▼
                       McpOrchestrationService.executeTool(teardownTool)
```

---

## 7. Try it on your laptop

A runnable scenario lives under
[`systemtest/sample-mcp-deploy-server/`](../../systemtest/sample-mcp-deploy-server)
and is wired up by
[`systemtest/docker-compose-mcp-deployment.yml`](../../systemtest/docker-compose-mcp-deployment.yml).

```bash
docker compose -f systemtest/docker-compose-mcp-deployment.yml up --build
```

This boots two containers on the shared `ai-git-bot-e2e` network:

| Container                        | Role                                                                                          |
|----------------------------------|-----------------------------------------------------------------------------------------------|
| `ai-git-bot-sample-e2e-app`      | The login-form app from M4 wave 2 — represents Alex's "deployed PR build".                    |
| `ai-git-bot-sample-mcp-deploy`   | A ~80-line Node MCP server (streamable HTTP) that simulates Alex's platform tools end-to-end. |

Point an AI-Git-Bot instance at the MCP server, follow the step-by-step UI
walkthrough in
[`systemtest/README-mcp-deployment.md`](../../systemtest/README-mcp-deployment.md),
and watch the bot drive deploy → poll → test → teardown — all without any
HMAC callback, any GitHub Action, or any second HTTP surface.

---

## 8. Out of scope

- Real Kubernetes integration. The sample MCP server hands back a static
  URL pointing at the M4 sample app — proving the *protocol*, not the
  deploy mechanics. Production platform tools are expected to live in
  Alex's own service.
- Multi-tenant routing inside the MCP server. The reference impl returns a
  per-run `deploymentId` only so `statusTool` / `teardownTool` look real.

