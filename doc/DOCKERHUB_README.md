# AI-Git-Bot

[![License: MIT](https://img.shields.io/github/license/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/blob/main/LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/releases)
[![GitHub stars](https://img.shields.io/github/stars/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/stargazers)

> **The self-hosted AI workflow automation platform for Git repositories.**

- 🔍 Review pull requests
- 🧪 Generate tests
- ✏️ Improve issues
- 🤖 Turn issues into pull requests
- 🎬 Create and run E2E tests
- 📝 Keep documentation in sync with the code
- 🌍 Keep translations in sync across locale files
- 💬 Answer questions inside code reviews

## Why does this project exist?

Every engineering team has a list of things they know should happen:

- Pull requests should be reviewed carefully
- Bugs should get regression tests
- Issues should have acceptance criteria
- Documentation should stay up to date with the code
- Preview environments should be cleaned up
- Small maintenance tickets should eventually get implemented

Nobody disagrees with any of those ideas. The problem is that these tasks are:

- Uncomfortable
- Repetitive
- Difficult to prioritize
- Easy to postpone when deadlines get tight

AI-Git-Bot exists to turn these engineering chores into repeatable workflows that
happen automatically inside your Git platform.

- No new development process.
- No migration project.
- No vendor lock-in.

Just better engineering hygiene through automation.

---

## 🔌 Mix any AI provider with any Git platform

| AI providers | Git platforms |
|---|---|
| **Anthropic** (Claude) | **Gitea** (self-hosted) |
| **OpenAI** (+ OpenAI-compatible APIs) | **GitHub** / **GitHub Enterprise** |
| **Google AI / Gemini** | **GitLab** (gitlab.com & self-managed) |
| **Ollama** (local LLMs) | **Bitbucket Cloud** |
| **llama.cpp** (local GGUF models) | |

Unlike most AI coding tools, AI-Git-Bot is not tied to a specific Git platform or
AI provider. **Fully self-hostable. Your code can stay inside your infrastructure.**

---

## ✨ What can it do?

| Workflow | Trigger | Result |
|-----------|----------|---------|
| **[PR Review](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_REVIEW.md)** | PR opened or review re-requested | Review comments and findings |
| **[Interactive Q&A](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_REVIEW.md)** | `@bot` mention in PR comments | Context-aware conversation |
| **[Issue → Code](https://github.com/tmseidel/ai-git-bot/blob/main/doc/CODING_AGENT.md)** | Issue assigned to coding bot | Pull request |
| **[Issue → Better Issue](https://github.com/tmseidel/ai-git-bot/blob/main/doc/WRITER_AGENT.md)** | Issue assigned to writer bot | Structured issue with acceptance criteria |
| **[Unit Test Generation](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_UNIT_TEST.md)** | PR opened or command triggered | Generated tests committed to branch |
| **[Full-Stack QA](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_E2E.md)** | PR opened | Playwright suite executed against preview environment |
| **[README Sync](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_README_SYNC.md)** | PR opened or command triggered | Documentation updated to match code changes |
| **[i18n Coverage](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_I18N_COVERAGE.md)** | PR opened or command triggered | Missing translations drafted across locale files |
| **[PR Re-Review](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_REVIEW.md)** | Force-push or review request | Updated analysis |
| **[Workflow Automation](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS.md)** | Git events | Automated engineering chores |

Every workflow is opt-in per bot — nothing changes for repos you don't touch.

---

## 🤖 Issue Agents: Coding and Writer

Two issue-driven agents let you assign bots to issues:

| Bot type | Trigger | Result |
|---|---|---|
| **Coding bot** | Assign to an issue with Agent Enabled | Feature branch, commit, and pull request |
| **Writer bot** | Assign to an issue | Clarifying questions or a new improved issue |

- **[Coding Agent docs](https://github.com/tmseidel/ai-git-bot/blob/main/doc/CODING_AGENT.md)** — configuration, settings, limitations
- **[Writer Agent docs](https://github.com/tmseidel/ai-git-bot/blob/main/doc/WRITER_AGENT.md)** — configuration, settings, limitations
- **[Full agent reference](https://github.com/tmseidel/ai-git-bot/blob/main/doc/AGENT.md)** — all settings, tool-calling, webhook setup

---

## Quick Start

```bash
docker compose up -d
```

Then:

1. Open `http://localhost:8080`
2. Create your administrator account
3. Create an AI Integration
4. Create a Git Integration
5. Create a Bot
6. Configure the webhook
7. You're done

---

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

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_ENCRYPTION_KEY` | *(random)* | Encryption key for API keys/tokens. Set for persistence across restarts. |
| `APP_PUBLIC_URL` | `http://localhost:8080` | Public base URL of the bot instance. |
| `DATABASE_URL` | `jdbc:postgresql://db:5432/giteabot` | JDBC connection URL |
| `DATABASE_USERNAME` | `giteabot` | Database username |
| `DATABASE_PASSWORD` | | Database password |

### Agent Configuration (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `AGENT_MAX_FILES` | `20` | Maximum files the agent can modify per issue |
| `AGENT_BRANCH_PREFIX` | `ai-agent/` | Prefix for branches created by the agent |

---

## Supported AI Providers

All AI configuration (API URLs, keys, models) is managed through the web UI — no
environment variables needed beyond what's listed above.

| Provider | Default API URL |
|----------|-----------------|
| **Anthropic** | `https://api.anthropic.com` |
| **OpenAI** | `https://api.openai.com` |
| **Google AI / Gemini** | `https://generativelanguage.googleapis.com` |
| **Ollama** | `http://localhost:11434` |
| **llama.cpp** | `http://localhost:8081` |

---

## Supported Git Providers

| Provider | Description |
|----------|-------------|
| **Gitea** | Self-hosted Gitea instances |
| **GitHub** | github.com |
| **GitHub Enterprise** | Self-hosted GitHub Enterprise Server |
| **GitLab** | gitlab.com and self-managed GitLab CE/EE |
| **Bitbucket Cloud** | bitbucket.org |

Issue-based agent workflows (coding and writer) require issue assignment and
webhook support — **Gitea, GitHub, and GitLab**. Bitbucket Cloud is PR-review
only.

---

## Webhook Setup

Each bot gets a unique webhook URL displayed in the web UI:

- `/api/webhook/{webhook-secret}`

### Supported Events per Platform

| Event | Gitea | GitHub | GitLab | Bitbucket |
|-------|-------|--------|--------|-----------|
| Pull Request | ✅ | ✅ | ✅ Merge request events | ✅ PR: Created/Updated |
| Comments | ✅ Issue Comment | ✅ Issue comments | ✅ Comments | ✅ PR: Comment created |
| Issues (agents) | ✅ | ✅ | ✅ Issues events | — |

---

## Health Check

```
GET http://<host>:8080/actuator/health
```

Built-in health check runs every 30s with a 30s start period.

## Metrics (Prometheus)

```
GET http://<host>:8080/actuator/prometheus
```

Disabled by default. Enable with `PROMETHEUS_ENABLED=true`.

Includes PR workflow, AI usage, agent tool-call, review/finding, and error metrics.
Labels are low-cardinality; repository names, PR numbers, session IDs, branch names, and error messages are never used.

See the [Deployment Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/DEPLOYMENT.md) for a full metric table and Prometheus scrape configuration.

---

## Documentation

- [User Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/USER_GUIDE.md)
- [Architecture](https://github.com/tmseidel/ai-git-bot/blob/main/doc/ARCHITECTURE.md)
- [PR Workflows overview](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS.md)
- [PR Review](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_REVIEW.md)
- [Agentic PR Review](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_AGENTIC_REVIEW.md)
- [Unit Tests](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_UNIT_TEST.md)
- [Full-Stack QA / E2E](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_E2E.md)
- [README Sync](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_README_SYNC.md)
- [i18n Coverage](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_I18N_COVERAGE.md)
- [CI Actions / Webhook Recipes](https://github.com/tmseidel/ai-git-bot/blob/main/doc/PR_WORKFLOWS_CI_ACTIONS.md)
- [Coding Agent](https://github.com/tmseidel/ai-git-bot/blob/main/doc/CODING_AGENT.md)
- [Writer Agent](https://github.com/tmseidel/ai-git-bot/blob/main/doc/WRITER_AGENT.md)
- [Issue Agents reference](https://github.com/tmseidel/ai-git-bot/blob/main/doc/AGENT.md)
- [Deployment Guide](https://github.com/tmseidel/ai-git-bot/blob/main/doc/DEPLOYMENT.md)
- [Gitea Setup](https://github.com/tmseidel/ai-git-bot/blob/main/doc/GITEA_SETUP.md)
- [GitHub Setup](https://github.com/tmseidel/ai-git-bot/blob/main/doc/GITHUB_SETUP.md)
- [GitLab Setup](https://github.com/tmseidel/ai-git-bot/blob/main/doc/GITLAB_SETUP.md)
- [Bitbucket Setup](https://github.com/tmseidel/ai-git-bot/blob/main/doc/BITBUCKET_SETUP.md)
- [Security Policy](https://github.com/tmseidel/ai-git-bot/blob/main/SECURITY.md)

---

## Technical Highlights

- 🔒 AES-256-GCM secret encryption
- 🤖 Multi-provider AI support
- 🏢 Multi-platform Git support
- 🧠 Local LLM support
- 🔌 MCP integration
- 🧪 System-tested workflows
- 🐳 Docker-first deployment
- 🌍 Self-hostable end-to-end

---

## License

[MIT](https://github.com/tmseidel/ai-git-bot/blob/main/LICENSE)
