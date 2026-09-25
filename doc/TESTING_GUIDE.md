# Testing AI-Git-Bot features safely

This guide is for testers who want to try AI-Git-Bot features without touching
production repositories, production AI credentials, or production deployment
systems.

Most reproducible scenarios live under `systemtest/`. Each one is a small
Docker Compose stack or recipe focused on one feature.

## Quick start pattern

1. Pick one scenario from the index below.
2. Read the linked `systemtest/README*.md` file for credentials and UI steps.
3. Start the compose stack with the one-line command.
4. Run AI-Git-Bot locally or in Docker and connect it to the test stack.
5. Trigger the workflow from the local Git provider or sandbox repository.
6. Stop the stack with `docker compose ... down -v` when done.

## Docker Compose scenario index

| Scenario | What it demonstrates | Bring it up |
|---|---|---|
| Local Gitea | Real local Gitea with a seeded `acme/demo` repo; useful for PR review, webhooks, issue agents, slash commands, and suite promotion. | `docker compose -f systemtest/docker-compose-local-gitea.yml up` |
| Local GitLab | Self-managed GitLab CE for merge-request webhooks, comments, and GitLab-specific artifact behavior. First boot can take several minutes. | `docker compose -f systemtest/docker-compose-local-gitlab.yml up` |
| E2E sample app | Tiny login app used as the preview target for Full-stack QA scenarios. Pair it with Gitea and a deployment-target scenario. | `docker compose -f systemtest/docker-compose-e2e-sample.yml up --build` |
| MCP deployment | Local MCP server that exposes deploy/status/teardown tools for the Full-stack QA deployment strategy. | `docker compose -f systemtest/docker-compose-mcp-deployment.yml up --build` |
| CI_ACTION deployment | Mock GitHub-Actions-style dispatch and polling API plus the sample app; exercises provider-native CI deployment flow. | `docker compose -f systemtest/docker-compose-ci-action.yml up --build` |
| Ollama | Local Ollama daemon for private/offline AI experiments. | `docker compose -f systemtest/docker-compose-ollama.yml up --build -d` |
| llama.cpp | Local llama.cpp server using GGUF models; useful for grammar-constrained local agent experiments. | `docker compose -f systemtest/docker-compose-llamacpp.yml up -d` |

## Recipe-only scenarios

| Scenario | Use it for | Start here |
|---|---|---|
| Suite promotion | Trying E2E lifecycle modes: `ephemeral`, `commit-to-pr`, `offer-as-pr`, and `promote-on-merge`. | [`../systemtest/README-suite-promotion.md`](../systemtest/README-suite-promotion.md) |
| GitHub Copilot MCP | Testing remote MCP discovery/tool execution against GitHub Copilot MCP with a sandbox bot. | [`../systemtest/README-mcp-github.md`](../systemtest/README-mcp-github.md) |
| Webhook deployment | Testing a webhook-driven deployment against your own Jenkins, TeamCity, script, or platform endpoint. | [`PR_WORKFLOWS_WEBHOOK_RECIPES.md`](PR_WORKFLOWS_WEBHOOK_RECIPES.md) |
| Static preview URL | Testing against an existing preview URL convention such as Vercel, Netlify, Render, or GitLab review apps. | [`PR_WORKFLOWS_E2E.md`](PR_WORKFLOWS_E2E.md) |

## Common Full-stack QA test stack

For the most complete local E2E workflow, run four terminals:

```bash
# Terminal A — sample app, used as the preview target
docker compose -f systemtest/docker-compose-e2e-sample.yml up --build

# Terminal B — local Git provider
docker compose -f systemtest/docker-compose-local-gitea.yml up

# Terminal C — deployment strategy, for example MCP
docker compose -f systemtest/docker-compose-mcp-deployment.yml up --build

# Terminal D — AI-Git-Bot from source
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Then follow the deployment-target setup in
[`../systemtest/README-mcp-deployment.md`](../systemtest/README-mcp-deployment.md)
or [`../systemtest/README-ci-action.md`](../systemtest/README-ci-action.md).

## Local LLM options

Use local LLMs when you want to test without cloud AI calls.

### Ollama

Ollama is simplest for local smoke tests and review-only bots.

- Compose: `docker compose -f systemtest/docker-compose-ollama.yml up --build -d`
- Guide: [`OLLAMA.md`](OLLAMA.md)
- Notes: small models are fine for demos and PR comments, but issue agents and
  structured tool workflows are less reliable on small local models.

### llama.cpp

llama.cpp is useful when you want GGUF models and grammar-constrained JSON for
agent workflows.

- Compose: `docker compose -f systemtest/docker-compose-llamacpp.yml up -d`
- Guide: [`LLAMACPP.md`](LLAMACPP.md)
- Notes: bring enough RAM/VRAM for the chosen model; the server decides the
  actual model, while the bot's UI stores the integration settings.

## What to test before reporting a bug

Try to reduce the report to one scenario:

- Git provider: Gitea, GitHub, GitHub Enterprise, GitLab, Bitbucket Cloud, or Azure DevOps.
- Provider version, especially for self-hosted Gitea/GitHub Enterprise/GitLab.
- AI provider and model, including whether it is local Ollama or llama.cpp.
- Workflow: PR review, interactive Q&A, coding agent, writer agent, AI Unit
  Tests, Full-stack QA/E2E, MCP, CI_ACTION, suite promotion, or webhooks.
- Exact trigger: PR opened, reviewer re-requested, issue assigned, comment,
  inline comment, slash command, synchronize/push, close/merge.
- Compose file or sandbox setup used.
- The relevant bot log excerpt, not the entire log.
- The public PR/issue link or a minimal reproduction if you can share it.

## Useful bug-report template

```text
Provider: Gitea 1.22.3
AI provider/model: Ollama qwen2.5-coder:32b
Workflow: Full-stack QA / @bot regenerate-tests
Scenario: systemtest/docker-compose-mcp-deployment.yml + local Gitea
Bot version/commit: <release or git SHA>
Expected: E2E suite regenerates and posts a new report
Actual: workflow stays WAITING_DEPLOY until timeout
Relevant log excerpt:
  <20-80 lines around the error>
```

## Where to go next

- [`../systemtest/README.md`](../systemtest/README.md) is the full scenario index.
- [`PR_WORKFLOWS_E2E.md`](PR_WORKFLOWS_E2E.md) explains the Full-stack QA PR UX.
- [`PR_WORKFLOWS_UNIT_TEST.md`](PR_WORKFLOWS_UNIT_TEST.md) explains AI Unit Tests.
- [`MCP_SERVER_HANDLING.md`](MCP_SERVER_HANDLING.md) explains MCP setup.
- [`GITEA_SETUP.md`](GITEA_SETUP.md), [`GITHUB_SETUP.md`](GITHUB_SETUP.md),
  [`GITLAB_SETUP.md`](GITLAB_SETUP.md), [`BITBUCKET_SETUP.md`](BITBUCKET_SETUP.md), and
  [`AZURE_DEVOPS_SETUP.md`](AZURE_DEVOPS_SETUP.md)
  cover provider-specific sandbox configuration.
