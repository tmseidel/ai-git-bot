# PR Workflows

PR workflows are administrator-configured actions that run from pull-request webhooks. They let one bot perform one or more ordered tasks such as AI review, agentic review, unit-test generation, or E2E testing.

> The PR-workflow subsystem is available since **1.7.0**. Developer architecture notes live in [`agentic-workflows/CONCEPT_AND_ARCHITECTURE.md`](agentic-workflows/CONCEPT_AND_ARCHITECTURE.md) and the source code.

## Built-in workflows

| Key | UI name | Default? | What it does |
|---|---|---|---|
| `review` | PR Review | Enabled on the seeded `Default` configuration | Posts a one-shot AI code review comment and applies the bot's configured post-review action. See [`PR_WORKFLOWS_REVIEW.md`](PR_WORKFLOWS_REVIEW.md). |
| `agentic-review` | Agentic PR Review | Opt-in | Lets the model read repository/MCP context before posting a Markdown review comment. Optionally posts formal review actions (approve/request-changes). See [`PR_WORKFLOWS_AGENTIC_REVIEW.md`](PR_WORKFLOWS_AGENTIC_REVIEW.md). |
| `unit-test-author` | AI Unit Tests | Opt-in | Generates and runs unit tests for the PR diff; can commit passing tests to the PR branch. See [`PR_WORKFLOWS_UNIT_TEST.md`](PR_WORKFLOWS_UNIT_TEST.md). |
| `e2e-test` | E2E Tests | Opt-in | Deploys or locates a PR preview, generates/runs E2E tests, and posts a summary. Requires a deployment target. See [`PR_WORKFLOWS_E2E.md`](PR_WORKFLOWS_E2E.md). |

Developers adding new workflow types should start from the source code and developer architecture notes rather than this operator guide.

## Workflow configurations

A **workflow configuration** is a reusable ordered selection of PR workflows plus per-workflow parameters. Assign one configuration to many bots, or leave a bot on the seeded `Default` configuration.

Admin UI:

1. **System settings → Workflow configurations** — list, add, edit, clone, and delete configurations.
2. **Workflows for «name»** — tick workflow keys and edit their parameter fields. Workflows run sequentially in stable order.
3. **Bots → New / Edit bot → Workflow Configuration** — choose a configuration or leave **(use default)**.

The workflow-selection page shows each workflow's display name, key, category, description, and editable parameter fields. Unavailable saved workflow keys are shown as **Not registered** instead of failing the whole configuration.

## Trigger conditions

PR workflows run only when the webhook is relevant for the bot. Across GitHub, Gitea, GitLab, and Bitbucket, a new/open PR triggers when either condition is true:

| Condition | Default | Effect |
|---|---|---|
| Bot is requested as reviewer | Always checked | Runs when developers explicitly request the bot. |
| **Run workflow when PR is opened** | Off | Runs for every new/open PR even without reviewer assignment. |

The bot form exposes this as **Run workflow when PR is opened**. It applies to PR/MR create/open events only; synchronize/push, review-requested, comment, approval, and close events keep their own handling.

When a new push updates a PR while an older run of the same workflow is still active, the older run is cancelled so comments and actions do not race against stale code.

## Operator-visible status

Workflow runs and steps are persisted and visible through the application UI/logs where supported. Terminal states are success, failed, skipped, waiting for deployment, or cancelled. Long logs stay in application logs; UI excerpts are intentionally short.

Prometheus metrics are available when actuator/Prometheus is enabled for your deployment. Use them for dashboards and alerting, but normal administration should start from the bot comments, run status, and application logs.

## Upgrade behaviour

Existing installations are migrated to a `Default` workflow configuration containing only `review`, preserving previous review behaviour. New review-category workflows may be added to `Default` by migrations/startup initialization, but testing workflows are opt-in. Existing bots with no explicit workflow configuration inherit the default.

Deployment-target migrations add the target tables and optional bot assignment. Existing bots have no deployment target until an operator configures one; preview-aware workflows are skipped with a clear PR comment.

<a id="deployment-targets-m3"></a>
## Deployment targets

Some workflows need a live per-PR preview URL, especially `e2e-test`. A **deployment target** tells the bot how to obtain that preview. Create targets under **System settings → Deployment targets**, then assign one under **Bots → Edit bot → Deployment Target**.

Target form fields:

| Field | Purpose |
|---|---|
| **Name** | Label shown in the bot dropdown. |
| **Strategy** | One of `STATIC`, `WEBHOOK`, `MCP`, `CI_ACTION`. |
| **Preview URL template** | Required for `STATIC`; optional/informational for others. Supports `{prNumber}`, `{sha}`, `{branch}`. |
| **Timeout (seconds)** | Maximum wait for readiness/callback. |
| **Strategy config (JSON)** | Strategy-specific JSON pasted into the UI. |

Configuration JSON is encrypted at rest when `APP_ENCRYPTION_KEY` is configured.

### Strategy overview

| Strategy | Waits for callback? | Use when |
|---|---|---|
| `STATIC` | No | Your platform already creates deterministic PR previews, such as Vercel, Netlify, Render, or GitLab review apps. |
| `WEBHOOK` | Yes | The bot must trigger external CI/CD, and that system will call back with the preview URL. |
| `MCP` | No | An internal MCP server exposes deploy/status/teardown tools selected in the MCP configuration. |
| `CI_ACTION` | Poller-driven, optional callback | The bot should dispatch native GitHub/Gitea Actions, GitLab CI, or Bitbucket Pipelines using the Git integration token. |

### `STATIC` config

Use `STATIC` when the preview URL is predictable from PR data.

```json
{
  "healthcheckPath": "/healthz",
  "expectedStatus": 200,
  "intervalSeconds": 5,
  "extraHeaders": { "X-Probe": "ai-bot" }
}
```

Set **Preview URL template** separately, for example:

```text
https://pr-{prNumber}.preview.example.com
```

Set `"healthcheckPath": ""` to skip probing.

### `WEBHOOK` config

Use `WEBHOOK` when an external system must build/deploy the preview.

```json
{
  "webhookUrl": "https://ci.acme.io/jobs/preview/build",
  "sharedSecret": "hex-or-arbitrary-string-for-hmac",
  "headers": { "X-Trigger-Source": "ai-git-bot" }
}
```

The bot sends a signed JSON envelope containing PR/repo identifiers plus `callbackUrl` and `callbackSecret`. The CI system should verify `X-AI-Bot-Signature` with `sharedSecret`, deploy the preview, then call the callback URL with the per-run callback secret.

### `MCP` config

Use `MCP` when a selected MCP server tool can create or locate the preview.

```json
{
  "mcpConfigurationId": 7,
  "deployTool": "platform/deploy_pr_preview",
  "statusTool": "platform/get_preview_status",
  "teardownTool": "platform/teardown_preview",
  "argsTemplate": {
    "project": "shop-web",
    "branch": "{branch}",
    "ref": "{sha}",
    "pr": "{prNumber}"
  }
}
```

`mcpConfigurationId` points to **System settings → MCP configurations**. Tool names must be whitelisted there. `statusTool` and `teardownTool` are optional. String values in `argsTemplate` support `{prNumber}`, `{sha}`, `{branch}`, `{repoOwner}`, `{repoName}`, `{runId}`, `{callbackUrl}`, and `{callbackSecret}`.

### `CI_ACTION` config

Use `CI_ACTION` to dispatch the Git provider's native CI.

```json
{
  "workflowRef": "preview.yml",
  "refTemplate": "refs/heads/{branch}",
  "previewUrlOutput": "preview_url",
  "pollIntervalSeconds": 15,
  "inputs": {
    "callbackUrl": "{callbackUrl}",
    "prNumber": "{prNumber}",
    "sha": "{sha}"
  }
}
```

`workflowRef` is the workflow file, pipeline trigger token, or provider-specific pipeline pattern. `refTemplate` must usually resolve to an existing branch or tag; GitHub Actions also supports `refs/pull/{prNumber}/head` for fork PRs. See [`PR_WORKFLOWS_CI_ACTIONS.md`](PR_WORKFLOWS_CI_ACTIONS.md) for provider recipes.

## Async deployment callbacks

`WEBHOOK` and some `CI_ACTION` setups call back when the preview is ready:

```text
POST /api/workflow-callback/{runId}/{secret}
Content-Type: application/json
X-AI-Bot-Signature: sha256=<hex>   # recommended

{ "status": "READY", "previewUrl": "https://pr-42.preview.acme.io" }
```

Optional log chunks can be sent to:

```text
POST /api/workflow-log/{runId}/{secret}
Content-Type: text/plain
```

The `{secret}` is the per-run `callbackSecret` delivered in the trigger payload. If you sign callbacks, sign with that callback secret, not the target's `sharedSecret`.

Verification recipe:

```bash
RUN_ID=42
CALLBACK_SECRET="from-trigger-payload"
CALLBACK_URL="https://bot.acme.io/api/workflow-callback/$RUN_ID/$CALLBACK_SECRET"
BODY='{"status":"READY","previewUrl":"https://pr-1234.preview.acme.io"}'
SIG="sha256=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$CALLBACK_SECRET" -hex | awk '{print $2}')"
curl -fsS -X POST \
  -H "Content-Type: application/json" \
  -H "X-AI-Bot-Signature: $SIG" \
  --data "$BODY" \
  "$CALLBACK_URL"
```

## Multi-instance caveat

Deployment callbacks update persisted state, but the in-process waiter is local to one application instance. In multi-instance production deployments, use sticky routing for callback traffic or expect the next webhook/poller cycle to observe the persisted update.

## See also

- [PR Review workflow](PR_WORKFLOWS_REVIEW.md)
- [Agentic PR Review workflow](PR_WORKFLOWS_AGENTIC_REVIEW.md)
- [Unit-Test Author workflow](PR_WORKFLOWS_UNIT_TEST.md)
- [E2E workflow](PR_WORKFLOWS_E2E.md)
- [CI Actions deployment recipes](PR_WORKFLOWS_CI_ACTIONS.md)
- [Webhook recipes for CI systems](PR_WORKFLOWS_WEBHOOK_RECIPES.md)
- [AGENT.md](AGENT.md) — issue coding/writer agents
