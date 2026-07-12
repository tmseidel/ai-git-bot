# AI-Git-Bot Documentation

Welcome! The documentation is organized by **who you are**. Pick your role and start from there.

---

## 👤 Users — *I work with the Git platform, a bot is already set up for me*

You interact with AI-Git-Bot entirely through Gitea / GitHub / GitLab / Bitbucket — assigning issues, requesting reviews, and mentioning the bot in comments.

| Document | What it covers |
|---|---|
| [Using the Bot](USING_THE_BOT.md) | Requesting reviews, talking to the bot with `@mentions`, slash commands, assigning issues to coding and writer bots, what the test workflows post on your PR |

---

## 🛠️ Administrators — *I set up and maintain the bot, its integrations, and workflows*

You run the software, connect it to your Git hosts and AI providers, and configure bots and workflows through the admin web UI.

### Install & operate

| Document | What it covers |
|---|---|
| [Deployment](DEPLOYMENT.md) | Docker Compose deployment, environment variables, production setup |
| [Admin Guide](USER_GUIDE.md) | The admin web UI: creating bots, AI integrations, Git integrations, workflow configurations |
| [Migration 1.0 → 1.1](MIGRATION_1.0_TO_1.1.md) · [Migration 1.6 → 1.7](MIGRATION_1.6_TO_1.7.md) · [Migration 1.12 → 1.13](MIGRATION_1.12_TO_1.13.md) | Upgrade notes between releases |

### Connect a Git provider

| Document | What it covers |
|---|---|
| [Gitea Setup](GITEA_SETUP.md) | Bot user, permissions, API tokens, webhooks for Gitea |
| [GitHub Setup](GITHUB_SETUP.md) | Bot user, permissions, PAT tokens, webhooks for GitHub / GitHub Enterprise |
| [GitLab Setup](GITLAB_SETUP.md) | Bot user, permissions, PAT tokens, webhooks for GitLab |
| [Bitbucket Setup](BITBUCKET_SETUP.md) | API tokens and webhook configuration for Bitbucket Cloud |

### Connect an AI provider

Cloud providers (Anthropic, OpenAI, Google AI / Gemini) only need an API key — see the [Admin Guide](USER_GUIDE.md). For local LLMs:

| Document | What it covers |
|---|---|
| [Using Ollama](OLLAMA.md) | Running with local LLMs via Ollama |
| [Using llama.cpp](LLAMACPP.md) | Running with llama.cpp and GBNF grammar support |

### Configure bots & workflows

| Document | What it covers |
|---|---|
| [Agents](AGENT.md) | Coding agent and technical-writer agent — setup, configuration, security, limitations |
| [PR Workflows](PR_WORKFLOWS.md) | **Start here.** What each workflow solves and the shared setup: workflow configurations, triggers, running workflows |
| [PR Review](PR_WORKFLOWS_REVIEW.md) | The default, always-on AI code review |
| [Agentic PR Review](PR_WORKFLOWS_AGENTIC_REVIEW.md) | A deeper review where the bot explores the codebase first; optional approve / request-changes |
| [Unit Tests](PR_WORKFLOWS_UNIT_TEST.md) | Auto-generated unit tests for the PR diff, run with your project's own toolchain |
| [Full-Stack QA](PR_WORKFLOWS_E2E.md) | Generated Playwright tests run against a per-PR preview |
| [README Sync](PR_WORKFLOWS_README_SYNC.md) | Keeps README and Markdown docs in sync with code changes |

> Preview/execution environments, MCP servers, and tool-calling are being
> reworked and are intentionally not covered in the workflow guides above.

---

## 🧪 Testers — *I want to try out features without touching production*

| Document | What it covers |
|---|---|
| [Testing Guide](TESTING_GUIDE.md) | Self-contained `docker-compose` system-test stacks, local LLM setups, and how to write useful bug reports |
| [System-test recipes](../systemtest/README.md) | Laptop-runnable stacks: local Gitea, local GitLab, E2E sample app, deployment strategies |

---

## 💻 Developers — *I work with the code*

| Document | What it covers |
|---|---|
| [Local Development](LOCAL_DEVELOPMENT.md) | Building, testing, running natively, adding providers |
| [Architecture](ARCHITECTURE.md) | The gateway concept and top-level system design |
| [Contributing](../CONTRIBUTING.md) | Contribution guidelines and coding conventions |
| [Security Policy](../SECURITY.md) | Vulnerability reporting |

---

## 📚 Project meta

| Document | What it covers |
|---|---|
| [Changelog](../CHANGELOG.md) | Release notes and notable changes |
| [Pitch](pitch/PITCH.md) | Why this project exists and how it compares to alternatives |
| [Code of Conduct](../CODE_OF_CONDUCT.md) | Community standards |
| [Citation Metadata](../CITATION.cff) · [CodeMeta](../codemeta.json) | Citation and machine-readable software metadata |
| [LLM Index](../llms.txt) · [Full LLM Reference](../llms-full.txt) | Entry points for LLMs and RAG systems |
