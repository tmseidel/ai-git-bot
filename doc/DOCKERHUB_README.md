# AI-Git-Bot

> **Half Bot, half Agent** — The intelligent Gateway between Git platforms and AI providers. 🤖🧠

AI-Git-Bot is a lightweight, self-hostable **Gateway application** for AI-powered code reviews, issue implementation, and technical-writing issue drafting. Connects **Gitea, GitHub, GitHub Enterprise, GitLab, and Bitbucket Cloud** with **Anthropic Claude, OpenAI, Google AI / Gemini, Ollama (local LLMs), and llama.cpp** — all managed through a **web-based UI**.

## Features

- **Gateway Architecture** — Central hub connecting any Git platform with any AI provider
- **Web-Based Management** — Configure bots, AI providers, and Git connections through a browser UI
- **Multi-Bot Support** — Create multiple bots with different AI providers, prompts, and personas
- **Multiple Git Providers** — Gitea, GitHub, GitHub Enterprise, GitLab, and Bitbucket Cloud support
- **Multiple AI Providers** — Anthropic, OpenAI, Google AI / Gemini, Ollama, and llama.cpp support
- **Reviewer-Triggered PR Reviews** — Reviews diffs when the bot is assigned or re-requested as reviewer
- **Interactive Bot Commands** — Mention the bot in PR comments to ask questions
- **Inline Review Comments** — Context-aware answers to code-level review comments
- **Coding Agent** — Assign a coding bot to an issue for autonomous code generation and PR creation
- **Technical Writer Agent** — Assign a writer bot to improve vague issues into actionable, testable follow-up issues
- **AI-Driven Code Validation** — Agent validates generated code with build tools (Maven, Gradle, npm, etc.)
- **Read-Only Issue Drafting Workflow** — Writer bots can inspect repository context without modifying repository files
- **Session Management** — Maintains conversation history per PR
- **Smart Diff Chunking** — Splits large diffs into chunks with retry on token limits
- **Encrypted Secrets** — API keys and tokens are encrypted at rest (AES-256-GCM)
- **Self-Host Friendly** — Run everything on-premise with local LLMs for compliance requirements

## Quick Start

```bash
docker compose up -d
```

Then:
1. Navigate to `http://localhost:8080`
2. Create your admin account
3. Configure AI and Git integrations via the web UI
4. Create a **Coding bot** or **Writer bot** and configure webhooks in your Git provider

## Bot Types

### Coding bot

Use coding bots for:

- reviewer-triggered pull-request reviews
- PR comment conversations
- inline review replies
- autonomous issue implementation with feature branch + pull request output

### Writer bot

Use writer bots when an issue is too vague to implement directly. Writer bots:

- ignore pull-request review events
- inspect related issues and repository context in a **read-only** workspace
- ask the original issue author the minimum necessary clarifying questions
- create a linked `AI Created Issue: ...` when enough context is available

Writer bots do **not** modify repository files, run validation tools, or open pull requests.

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

- [GitHub Repository](https://github.com/tmseidel/anthropic-gitea-bot)
- [User Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/USER_GUIDE.md)
- [Architecture](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/ARCHITECTURE.md)
- [Agent Documentation](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/AGENT.md)
- [Gitea Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITEA_SETUP.md)
- [GitHub Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITHUB_SETUP.md)
- [GitLab Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITLAB_SETUP.md)
- [Bitbucket Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/BITBUCKET_SETUP.md)
- [Deployment Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/DEPLOYMENT.md)

## License

MIT
