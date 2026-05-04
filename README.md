# AI-Git-Bot

[![License: MIT](https://img.shields.io/github/license/tmseidel/ai-git-bot)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/tmseidel/ai-git-bot)](https://hub.docker.com/r/tmseidel/ai-git-bot)
[![GitHub release](https://img.shields.io/github/v/release/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/releases)
[![GitHub stars](https://img.shields.io/github/stars/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/issues)

> **Your intelligent gateway between Git and AI — Half Bot, half Agent.** 🤖🧠

AI-Git-Bot is a lightweight, self-hostable **gateway application** that connects your Git platforms with AI providers. As a central hub it receives webhooks from **Gitea, GitHub, GitHub Enterprise, GitLab, and Bitbucket Cloud**, routes them to configurable AI providers, and writes the results back as code reviews, technical-writing issue drafts, comments, or even entire pull requests — fully automated.

<p align="center">
  <img src="doc/images/schema.png" alt="AI-Git-Bot Architecture Schema" width="800"/>
</p>

## 🎯 Who is AI-Git-Bot for?

| Audience | Benefit |
|----------|---------|
| 🧑‍💻 **Developers who want a personalized code AI** | Configure your own AI with custom system prompts — for code reviews that match your tech stack and coding standards. |
| 🔄 **Teams with multiple projects & Git systems** | Define an AI configuration once and reuse it across any number of repositories, projects, and Git platforms — through a single gateway. |
| 👥 **Multi-pass reviews with different personas** | Create multiple bots with different prompts: a security reviewer, a performance expert, a junior mentor — all on the same PR. |
| 🧾 **Product owners, maintainers, and triagers** | Use writer bots to turn vague issue reports into actionable, testable implementation-ready issues without leaving the Git platform. |
| 🧠 **Teams that need better issue quality before coding starts** | Let a technical-writer agent check completeness, contradictions, assumptions, and acceptance criteria before engineering work begins. |
| 🔒 **Self-hosters with compliance requirements** | Run everything on-premise with local LLMs (Ollama, llama.cpp). No code leaves your infrastructure — ideal for regulatory and compliance needs. |
| ⚡ **Lightweight AI implementation** | A single Docker image, one PostgreSQL database — done. No complex infrastructure, no Kubernetes clusters required. |

## 🤖🧠 Half Bot, Half Agent

AI-Git-Bot unites two worlds:

- **As a Bot** it automatically reacts to pull requests, answers questions in comments, and delivers context-aware inline reviews — like a reliable code-review partner that never sleeps.
- **As an Agent** it autonomously takes on entire issues in two ways: a **coding agent** implements the change and opens a pull request, while a **writer agent** improves vague issues into structured, implementation-ready follow-up issues.

> More than a bot. More than an agent. **The intelligent gateway for your entire code review and implementation workflow.**

<p align="center">
  <img src="doc/images/dashboard_ai_git_bot.PNG" alt="AI-Git-Bot Dashboard" width="800"/>
</p>

## 🌉 The Gateway Principle

AI-Git-Bot acts as a **central gateway** between your Git systems and AI providers:

```mermaid
graph LR
    subgraph Git Platforms
        Gitea
        GitHub
        GitLab
        Bitbucket
    end

    subgraph AI Providers
        Anthropic
        OpenAI
        Ollama
        llama.cpp
    end

    Gateway["🌉 AI-Git-Bot\n(Gateway)"]
    DB["🗄️ PostgreSQL\n(Config & Sessions)"]

    Gitea <--> Gateway
    GitHub <--> Gateway
    GitLab <--> Gateway
    Bitbucket <--> Gateway
    Gateway <--> Anthropic
    Gateway <--> OpenAI
    Gateway <--> Ollama
    Gateway <--> llama.cpp
    Gateway --> DB
```

**Benefits of the gateway approach:**

- 🔗 **One configuration, many repositories** — Set up once, use everywhere
- 🔀 **Mix & match** — Combine different AI providers with different Git platforms
- 🛡️ **Centralized control** — Manage API keys, tokens, and prompts in one place
- 📊 **Unified monitoring** — Dashboard with statistics across all bots
- 🔐 **Encrypted secrets** — API keys and tokens are stored with AES-256-GCM encryption

## Features

### 🔍 Automatic PR Code Reviews

When a pull request is opened or updated, the bot automatically reviews the diff and posts feedback as a review comment. Large diffs are intelligently split into chunks with automatic retry on token limits.

<details>
<summary>📸 Screenshots: Code reviews across platforms</summary>

**Gitea:**
<img src="doc/screenshots/gitea/screenshot_initial_code_review.png" alt="Gitea Code Review" width="600"/>

**GitHub:**
<img src="doc/screenshots/github/github_code_review_with_comment.png" alt="GitHub Code Review" width="600"/>

**GitLab:**
<img src="doc/screenshots/gitlab/gitlab-pull-request-with-code-review.png" alt="GitLab Code Review" width="600"/>

**Bitbucket:**
<img src="doc/screenshots/bitbucket/bitbucket-code-review.png" alt="Bitbucket Code Review" width="600"/>

</details>

### 💬 Interactive Bot Commands

Mention the bot (e.g. `@ai_bot`) in any PR comment to ask questions or request additional analysis. The bot acknowledges with 👀 and responds using the full conversation history.

<img src="doc/screenshots/gitea/screenshot_code_review_with_comment.png" alt="Code Review with Comment" width="600"/>

### 📝 Inline Review Comment Responses

Mention the bot in an inline review comment on a specific code line. The bot includes the file context and diff hunk when generating its answer and replies directly inline.

<img src="doc/screenshots/gitea/screenshot_code_review_with_inline_comment.png" alt="Code Review with Inline Comment" width="600"/>

### 🤖 Autonomous Issue Implementation Agent

Assign the bot to an issue — it analyzes the task, reads the source code, generates an implementation, validates with build tools, and creates a finished pull request. Fully autonomous.

<details>
<summary>📸 Screenshots: Agent across platforms</summary>

**GitHub:**
<img src="doc/screenshots/github/github_issue_agent_code_implementation.png" alt="GitHub Agent" width="600"/>

**GitLab:**
<img src="doc/screenshots/gitlab/gitlab_issue_agent_code_implementation.png" alt="GitLab Agent" width="600"/>

</details>

See the [Agent Documentation](doc/AGENT.md) for details.

### ✍️ Technical Writer Agent for Better Issues

Assign a **Writer bot** to an issue when you want the bot to improve the problem statement instead of changing code. The writer agent can inspect related issues and explore the repository in a **read-only workspace** to understand naming, affected components, and constraints before it drafts a better issue.

Typical writer-bot use cases:

- turn vague bug reports into reproducible, testable issues
- rewrite feature requests into structured engineering work items
- identify missing acceptance criteria, contradictions, and open questions
- ask the original issue author only the minimum critical follow-up questions
- create a linked `AI Created Issue: ...` once enough context is available

Writer bots are currently intended for providers with issue-assignment workflows such as **Gitea, GitHub, and GitLab**. They ignore pull-request review events and never modify repository files.

### 🖥️ Web-Based Management

All configuration is managed through a **web-based UI** — no environment variables needed for AI providers, Git connections, or bot settings:

- Create multiple **AI Integrations** (Anthropic, OpenAI, Ollama, llama.cpp)
- Create multiple **Git Integrations** (Gitea, GitHub, GitHub Enterprise, GitLab, Bitbucket Cloud)
- Create multiple **Bots**, each with its own webhook URL, AI provider, and system prompt
- Dashboard with statistics and monitoring

### 🔌 Supported AI Providers

| Provider | Default API URL | Suggested Models |
|----------|-----------------|------------------|
| **Anthropic** | `https://api.anthropic.com` | claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001 |
| **OpenAI** | `https://api.openai.com` | gpt-5.4, gpt-5.3-codex, gpt-5.1-codex-max, gpt-5-codex |
| **Ollama** | `http://localhost:11434` | User-configured local models |
| **llama.cpp** | `http://localhost:8081` | User-configured GGUF models |

### 🌐 Supported Git Platforms

| Provider | Description |
|----------|-------------|
| **Gitea** | Self-hosted Gitea instances |
| **GitHub** | github.com |
| **GitHub Enterprise** | Self-hosted GitHub Enterprise Server |
| **GitLab** | gitlab.com and self-managed GitLab CE/EE |
| **Bitbucket Cloud** | bitbucket.org |

### More Features

- **Session Management** — Maintains conversation history per PR, persisted in the database, enabling context-aware follow-up reviews
- **Configurable System Prompts** — Manage reusable review, coding-agent, and writer-agent prompt entries in System settings and assign them per bot
- **AI-Driven Code Validation** — The agent validates generated code with build tools (Maven, Gradle, npm, Go, Cargo, etc.)
- **Health Endpoint** — `/actuator/health` for monitoring and orchestration

## Docker

The bot is available as a Docker image on [Docker Hub](https://hub.docker.com/r/tmseidel/anthropic-gitea-bot).

```yaml
services:
  app:
    image: tmseidel/ai-git-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: ${DATABASE_USERNAME:-giteabot}
      DATABASE_PASSWORD: ${DATABASE_PASSWORD:-giteabot}
      APP_ENCRYPTION_KEY: ${APP_ENCRYPTION_KEY:-change-me}
    depends_on:
      db:
        condition: service_healthy
    restart: unless-stopped

  db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: giteabot
      POSTGRES_USER: ${DATABASE_USERNAME:-giteabot}
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-giteabot}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DATABASE_USERNAME:-giteabot}"]
      interval: 5s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  pgdata:
```

## Quick Start

### 1. Start the Application

```bash
docker compose up --build -d
```

This starts:
- The bot application on port **8080**
- A **PostgreSQL 17** database for persistence

### 2. Initial Setup

1. Navigate to `http://localhost:8080`
2. Create your administrator account
3. Log in to access the management dashboard

### 3. Configure Integrations

1. **Create an AI Integration:**
   - Go to **AI Integrations → New Integration**
   - Select a provider (e.g. "anthropic")
   - The API URL is auto-filled with the provider's default
   - Select a model from the dropdown or enter a custom model name
   - Enter your API key
   - OpenAI-compatible providers can often be configured by selecting "openai" and entering the provider's custom API URL, API key, and model; see the [User Guide](doc/USER_GUIDE.md#openai-compatible-apis)

2. **Create a Git Integration:**
   - Go to **Git Integrations → New Integration**
   - Select your provider (Gitea, GitHub, GitLab, or Bitbucket)
   - Enter your Git server URL and API token
   - See [Gitea Setup](doc/GITEA_SETUP.md), [GitHub Setup](doc/GITHUB_SETUP.md), [GitLab Setup](doc/GITLAB_SETUP.md), or [Bitbucket Setup](doc/BITBUCKET_SETUP.md)

3. **Create a Bot:**
   - Go to **Bots → New Bot**
   - Choose **Coding bot** for pull-request review/issue implementation, or **Writer bot** for technical-writing issue drafts
   - Select your AI and Git integrations
   - Select a system prompt entry from **System settings**
   - Copy the generated **Webhook URL**

### 4. Configure Webhooks

Configure webhooks in your Git provider to notify the bot about PR events.

- **Gitea:** See [Gitea Setup](doc/GITEA_SETUP.md#4-configure-webhooks)
- **GitHub:** See [GitHub Setup](doc/GITHUB_SETUP.md#4-configure-webhooks)
- **GitLab:** See [GitLab Setup](doc/GITLAB_SETUP.md#4-configure-webhooks)
- **Bitbucket Cloud:** See [Bitbucket Setup](doc/BITBUCKET_SETUP.md#step-4-configure-the-webhook-in-bitbucket)

See the [User Guide](doc/USER_GUIDE.md) for detailed instructions.

## Architecture Overview

```mermaid
graph LR
    Git["Git Platform<br/>(Gitea / GitHub / GitLab / Bitbucket)"]
    Bot["AI-Git-Bot<br/>(Gateway)"]
    AI["AI Provider<br/>(Anthropic / OpenAI / Ollama / llama.cpp)"]
    DB["PostgreSQL"]

    Git -- "Webhooks" --> Bot
    Bot -- "Fetch diff, post reviews" --> Git
    Bot -- "AI review requests" --> AI
    Bot -- "Configuration & Sessions" --> DB
```

The bot receives webhooks from your Git provider, fetches PR diffs, sends them to the configured AI provider for review, and posts the results back. All configuration (AI integrations, Git integrations, bots) and conversation sessions are persisted in the database.

➡️ See the [Architecture Documentation](doc/ARCHITECTURE.md) for detailed component diagrams and request flows.

## Documentation

| Document | Description |
|----------|-------------|
| [User Guide](doc/USER_GUIDE.md) | Web UI usage, creating bots and integrations |
| [Architecture](doc/ARCHITECTURE.md) | Component diagrams, request flows, webhook routing |
| [Agent](doc/AGENT.md) | Coding agent and technical writer agent — setup, tools, and workflows |
| **Git Provider Setup** | |
| [Gitea Setup](doc/GITEA_SETUP.md) | Bot user creation, permissions, API tokens for Gitea |
| [GitHub Setup](doc/GITHUB_SETUP.md) | Bot user creation, permissions, PAT tokens for GitHub |
| [GitLab Setup](doc/GITLAB_SETUP.md) | Bot user creation, permissions, PAT tokens for GitLab |
| [Bitbucket Setup](doc/BITBUCKET_SETUP.md) | API tokens and webhook configuration for Bitbucket Cloud |
| **AI Provider Setup** | |
| [Using Ollama](doc/OLLAMA.md) | Running with local LLMs via Ollama |
| [Using llama.cpp](doc/LLAMACPP.md) | Running with llama.cpp and GBNF grammar support |
| **Deployment** | |
| [Deployment](doc/DEPLOYMENT.md) | Docker Compose deployment, environment variables |
| [Local Development](doc/LOCAL_DEVELOPMENT.md) | Building, testing, project structure |
| **Community** | |
| [Contributing](CONTRIBUTING.md) | Contribution guidelines, coding conventions |
| [Code of Conduct](CODE_OF_CONDUCT.md) | Community standards |

## License

[MIT](LICENSE)
