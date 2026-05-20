# MCP Deployment system test (M5)

End-to-end recipe demonstrating `MCPDeploymentStrategy` — the bot drives a
per-PR preview deploy by calling regular MCP tools on a server you
control. Companion to
[`doc/refactoring/MCP_DEPLOYMENT_USER_STORY.md`](../doc/refactoring/MCP_DEPLOYMENT_USER_STORY.md)
(the *why*) and
[`doc/MCP_SERVER_HANDLING.md` § 6](../doc/MCP_SERVER_HANDLING.md#6-exposing-deployment-style-tools-m5)
(the *protocol*).

## 1. Boot the scenario

```bash
docker compose -f systemtest/docker-compose-mcp-deployment.yml up --build
```

Two containers come up on the shared `ai-git-bot-e2e` network:

| Container                       | Port  | Role                                                                                |
|---------------------------------|-------|-------------------------------------------------------------------------------------|
| `ai-git-bot-sample-e2e-app`     | 3030  | The M4 sample login app — plays the "deployed PR build".                            |
| `ai-git-bot-sample-mcp-deploy`  | 8090  | MCP server exposing `platform__deploy_preview` / `platform__preview_status` / `platform__teardown_preview`. |

Smoke-check the MCP server is alive:

```bash
curl -s http://localhost:8090/healthz
# {"status":"ok","tools":3}
```

## 2. Register the MCP configuration in AI-Git-Bot

In the bot's web UI:

1. **System settings → MCP configurations → Add**, paste:

   ```json
   [
     {
       "name": "platform",
       "type": "url",
       "url": "http://sample-mcp-deploy:8090/mcp"
     }
   ]
   ```

   (use `http://host.docker.internal:8090/mcp` instead if the bot runs
   outside the `ai-git-bot-e2e` network).

2. Click **Save and select tools**, tick:
   - `platform__deploy_preview`
   - `platform__preview_status`
   - `platform__teardown_preview`

   then **Save selection**.

## 3. Create the MCP deployment target

1. **Deployment targets → New**.
2. Strategy = **MCP**.
3. MCP configuration = `platform` (the one you just saved).
4. JSON config:

   ```json
   {
     "mcpConfigurationId": <id>,
     "deployTool":   "mcp:platform:platform__deploy_preview",
     "statusTool":   "mcp:platform:platform__preview_status",
     "teardownTool": "mcp:platform:platform__teardown_preview",
     "argsTemplate": {
       "runId":     "{runId}",
       "prNumber":  "{prNumber}",
       "repoOwner": "{repoOwner}",
       "repoName":  "{repoName}",
       "sha":       "{sha}",
       "branch":    "{branch}"
     },
     "pollIntervalSeconds": 2,
     "timeoutSeconds":      60
   }
   ```

5. Click **Save**.
   - If any tool name above is not on the whitelist the save returns
     `HTTP 400` with an actionable message — that is the M5 guard rail.

## 4. Wire the target to a bot

Pick (or create) a bot configured against any provider, assign the
`Full-stack QA` workflow configuration (Flyway `V18` seeded it), and
select the new MCP deployment target.

## 5. Trigger the full pipeline

Open a PR in the bot's target repo. You should observe in the
`sample-mcp-deploy` container logs:

```
[deploy] runId=42 pr=7 repo=acme/web deploymentId=dep-1a2b3c4d
[status] runId=42 → READY (http://sample-e2e-app:3000)
```

…then in the bot logs the E2E workflow proceeds (planner → author →
runner) against `http://sample-e2e-app:3000`, posts a results comment on
the PR, and finally on PR close:

```
[teardown] runId=42 deploymentId=dep-1a2b3c4d
```

## 6. Failure-path smoke test

Untick `platform__teardown_preview` in *MCP configurations → Tools* and
re-save. Re-trigger the workflow: the deploy still succeeds but the
PR-close teardown safely degrades to a `REJECTED` log line — proving the
runtime whitelist check enforced by `MCPDeploymentStrategy`.

## 7. Cleanup

```bash
docker compose -f systemtest/docker-compose-mcp-deployment.yml down -v
```

