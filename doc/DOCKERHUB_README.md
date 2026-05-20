# AI-Git-Bot

> **Automate the necessary-but-uncomfortable parts of software development — directly inside the Git tools your team already uses.** 🤖🧠

Every team has a list of *"we know we should be doing this"* engineering chores: writing a properly scoped issue before coding, adding a regression E2E test for that login bug, re-reviewing a PR after the third force-push, tearing down stale preview environments. They are **necessary** (skipping them rots the codebase) but **uncomfortable** (they aren't the fun part, and they get cut first under deadline pressure).

AI-Git-Bot turns those chores into **repeatable, automated workflows** that run natively inside **Gitea, GitHub, GitHub Enterprise, GitLab, and Bitbucket Cloud** — triggered by the events your team is already producing (issue assigned, PR opened, reviewer re-requested, `@bot` mentioned).

## 🩹 The pain points it removes

| Uncomfortable chore | What AI-Git-Bot does |
|---|---|
| 🧾 **Writing a good issue** before any code is written | A **writer bot** inspects related issues + the repo (read-only), asks the *minimum* clarifying questions, and produces a structured `AI Created Issue: …` with acceptance criteria. |
| 🔍 **Reviewing PRs consistently** when reviewers are swamped | A **review bot** posts inline + summary review feedback every time it is requested as reviewer; large diffs are chunked, `@bot` mentions keep follow-up Q&A in the PR thread. |
| 🧪 **Writing regression E2E tests** for the bug you just fixed | The **Full-stack QA** workflow plans, authors, deploys, and runs Playwright tests per PR, posts the report as a comment, and tears the environment down on PR close. |
| 🛠️ **Implementing boring follow-up issues** (rename, dep bump, small refactor) | A **coding bot** reads the source, drafts the change in a workspace, validates with the project's own build tooling (Maven / Gradle / npm / Go / Cargo / .NET), and opens a PR. |
| 🔁 **Re-running tests / regenerating coverage** when something flaked | `@bot rerun-tests` re-executes the suite; `@bot regenerate-tests <feedback>` re-plans the suite with operator hints. |
| 🧹 **Tearing down stale preview environments** | The PR-close lifecycle hook calls the deployment target's `teardown` action (webhook, MCP tool, static no-op, or — planned M6 — a CI workflow dispatch). |

> **Pick the chore that hurts most. Wire one bot. Done.** Every workflow is opt-in per bot — nothing changes for repos you don't touch.

## 🧰 The core workflows

| Workflow | Triggered by | Produces | Status |
|---|---|---|---|
| **Review** | PR opened with bot as reviewer (or re-requested) | Inline + summary review comments | ✅ |
| **Issue → Code** (coding agent) | Issue assigned to a coding bot | A pull request implementing the change | ✅ |
| **Issue → Better Issue** (writer agent) | Issue assigned to a writer bot | A structured `AI Created Issue` with acceptance criteria | ✅ |
| **Interactive Q&A** | `@bot` mention in PR / inline review comment | Threaded reply with file + diff context | ✅ |
| **Full-stack QA** (E2E tests) | PR opened on a bot with an `e2e-test` workflow + deployment target | Generated Playwright suite, run report, environment teardown on PR close | ✅ |
| **Suite promotion** | Operator opt-in per suite | Follow-up PR that "graduates" a generated suite into the repo | ⏳ planned |

## 🌍 Where the E2E workflow deploys its preview environment

The Full-stack QA workflow needs a per-PR environment to test against. Teams already have *very* different deploy pipelines — so the bot ships a small `DeploymentStrategy` SPI with four interchangeable implementations:

| Strategy | Best for | Concrete user story |
|---|---|---|
| **`STATIC`** ✅ | Vercel / Netlify / GitLab review apps / Render — anything already creating preview-per-PR at a predictable URL. | [Marco the Frontend Lead](https://github.com/tmseidel/ai-git-bot/blob/main/doc/refactoring/STATIC_DEPLOYMENT_USER_STORY.md) |
| **`WEBHOOK`** ✅ | Jenkins / TeamCity / scripts behind a firewall — anywhere you can `curl` an HMAC-signed callback back to the bot. | [Priya the DevOps Engineer](https://github.com/tmseidel/ai-git-bot/blob/main/doc/refactoring/WEBHOOK_DEPLOYMENT_USER_STORY.md) |
| **`MCP`** ✅ | Internal platform teams already exposing deploy/status/teardown over MCP — zero extra services, single whitelist, no inbound callback. | [Alex the Platform Engineer](https://github.com/tmseidel/ai-git-bot/blob/main/doc/refactoring/MCP_DEPLOYMENT_USER_STORY.md) (laptop reproduction: `systemtest/docker-compose-mcp-deployment.yml`) |
| **`CI_ACTION`** ✅ | Provider-native CI (GitHub Actions / GitLab CI / Bitbucket Pipelines / Gitea Actions) — dispatched via existing repo credentials, no new secrets. | [Sam the SRE](https://github.com/tmseidel/ai-git-bot/blob/main/doc/refactoring/CI_ACTION_DEPLOYMENT_USER_STORY.md) — operator recipes: [`doc/PR_WORKFLOWS_CI_ACTIONS.md`](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_CI_ACTIONS.md) (shipped M6) |

## Quick Start

```bash
docker compose up -d
```

Then:
1. Navigate to `http://localhost:8080`
2. Create your admin account
3. Configure AI and Git integrations via the web UI
4. Create a **Coding bot** or **Writer bot** and configure webhooks in your Git provider
5. (Optional) Add a deployment target and enable the **Full-stack QA** workflow on the bot

## Bot Types

### Coding bot
Reviewer-triggered PR reviews, PR comment conversations, inline review replies, and autonomous issue implementation with feature branch + pull-request output.

### Writer bot
Read-only issue improvement. Inspects related issues and repo context in a sandboxed workspace, asks the minimum clarifying questions, then creates a linked `AI Created Issue: …`. Does **not** modify repo files, run validation tools, or open pull requests.

## 🧱 Under the hood: an AI- and Git-agnostic gateway

The reason a single bot serves four Git platforms and five AI providers is structural — every Git platform plugs in through a `RepositoryApiClient` SPI, every AI provider through an `AiClient` SPI, and tool calls (built-in + MCP) flow through a unified router. That's *enabling infrastructure*, not the headline feature — the headline is the workflows above, which happen to work everywhere because of this design.

- 🔗 One configuration, many repositories
- 🔀 Mix & match any supported AI provider with any supported Git platform
- 🛡️ Centralised control of API keys, tokens, prompts, and tool whitelists
- 🔐 AES-256-GCM at rest for every credential
- 🧩 MCP-ready with per-tool whitelisting
- 📊 Single dashboard with stats + audit across every bot and workflow run
- 🪶 One Docker image + one PostgreSQL database. No Kubernetes required.

## Docker Compose

```yaml
services:
  app:
    image: tmseidel/ai-git-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: giteabot
      DATABASE_PASSWORD: change-me
      APP_ENCRYPTION_KEY: your-secure-encryption-key
    depends_on:
      db:
        condition: service_healthy
    restart: unless-stopped

  db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: giteabot
      POSTGRES_USER: giteabot
      POSTGRES_PASSWORD: change-me
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U giteabot"]
      interval: 5s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  pgdata:
```

## Supported AI Providers

| Provider | Default API URL | Suggested Models |
|----------|-----------------|------------------|
| **Anthropic** | `https://api.anthropic.com` | claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5-20251001 |
| **OpenAI** | `https://api.openai.com` | gpt-5.5, gpt-5.4, gpt-5.4-mini, gpt-5.3-codex |
| **Google AI / Gemini** | `https://generativelanguage.googleapis.com` | gemini-2.5-pro, gemini-2.5-flash, gemini-2.0-flash |
| **Ollama** | `http://localhost:11434` | User-configured local models |
| **llama.cpp** | `http://localhost:8081` | User-configured GGUF models |

All AI configuration (API URLs, keys, models) is managed through the web UI — no environment variables needed.

## Supported Git Providers

| Provider | Description |
|----------|-------------|
| **Gitea** | Self-hosted Gitea instances |
| **GitHub** | github.com |
| **GitHub Enterprise** | Self-hosted GitHub Enterprise Server |
| **GitLab** | gitlab.com and self-managed GitLab CE/EE |
| **Bitbucket Cloud** | bitbucket.org |

> Issue-based agent workflows currently require issue assignment and issue webhook support. In practice this means **Gitea, GitHub, and GitLab**. Bitbucket Cloud remains pull-request-review only.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_ENCRYPTION_KEY` | *(random)* | Encryption key for API keys/tokens. Set for persistence across restarts. |
| `DATABASE_URL` | `jdbc:postgresql://db:5432/giteabot` | JDBC connection URL |
| `DATABASE_USERNAME` | `giteabot` | Database username |
| `DATABASE_PASSWORD` | | Database password |

### Agent Configuration (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `AGENT_MAX_FILES` | `20` | Maximum files the agent can modify per issue |
| `AGENT_MAX_TOKENS` | `32768` | Maximum tokens for AI responses in agent mode |
| `AGENT_BRANCH_PREFIX` | `ai-agent/` | Prefix for branches created by the agent |

## Webhook Setup

Each bot gets a unique webhook URL displayed in the web UI. The same URL format works for all Git providers:

- `/api/webhook/{webhook-secret}`

### Supported Events per Platform

| Event | Gitea | GitHub | GitLab | Bitbucket |
|-------|-------|--------|--------|-----------|
| Pull Request | ✅ | ✅ | ✅ Merge request events | ✅ PR: Created/Updated |
| Comments | ✅ Issue Comment | ✅ Issue comments | ✅ Comments | ✅ PR: Comment created |
| Issues (Coding/Writer agents) | ✅ | ✅ | ✅ Issues events | — |

## Volumes

| Path | Description |
|------|-------------|
| `/app/prompts` | Bundled prompt seed files used to initialize default system prompt entries (optional, mount read-only) |

## Health Check

```
GET http://<host>:8080/actuator/health
```

Built-in health check runs every 30s with a 30s start period.

## Source Code & Documentation

- [GitHub Repository](https://github.com/tmseidel/ai-git-bot)
- [User Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/USER_GUIDE.md)
- [Architecture](https://github.com/tmseidel/ai-git-bot/blob/main/doc/ARCHITECTURE.md)
- [Agent Documentation](https://github.com/tmseidel/ai-git-bot/blob/main/doc/AGENT.md)
- [PR Workflows guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS.md) — review / E2E / deployment targets / slash commands
- [PR Workflows roadmap + user stories](https://github.com/tmseidel/ai-git-bot/blob/main/doc/refactoring/README.md) — persona-driven stories for `STATIC` / `WEBHOOK` / `MCP` / `CI_ACTION`
- [MCP Server Handling](https://github.com/tmseidel/ai-git-bot/blob/main/doc/MCP_SERVER_HANDLING.md)
- [Gitea Setup Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/GITEA_SETUP.md)
- [GitHub Setup Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/GITHUB_SETUP.md)
- [GitLab Setup Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/GITLAB_SETUP.md)
- [Bitbucket Setup Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/BITBUCKET_SETUP.md)
- [Deployment Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/DEPLOYMENT.md)

## License

MIT
