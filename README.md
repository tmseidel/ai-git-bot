# AI-Git-Bot

[![License: MIT](https://img.shields.io/github/license/tmseidel/ai-git-bot)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/tmseidel/ai-git-bot)](https://hub.docker.com/r/tmseidel/ai-git-bot)
[![GitHub release](https://img.shields.io/github/v/release/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/releases)
[![GitHub stars](https://img.shields.io/github/stars/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/tmseidel/ai-git-bot)](https://github.com/tmseidel/ai-git-bot/issues)


🌐 Languages: **English** · [中文](README.zh.md) · [한국어](README.ko.md) · [日本語](README.ja.md)

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
* Uncomfortable
* Repetitive
* Difficult to prioritize
* Easy to postpone when deadlines get tight

AI-Git-Bot exists to turn these engineering chores into repeatable workflows that happen automatically inside your Git platform.

* No new development process.
* No migration project.
* No vendor lock-in.

Just better engineering hygiene through automation.

---

## Who is AI-Git-Bot for?

You may not fit neatly into a single category — many teams overlap across these concerns. Here are three illustrative examples:

### 🏢 Running Gitea and missing modern AI tooling?

Many teams choose Gitea because they want ownership of their source code and infrastructure.

Unfortunately, most AI products focus primarily on GitHub.

AI-Git-Bot brings:

- AI code reviews
- AI-generated tests
- AI issue authoring
- AI coding workflows
- AI documentation upkeep
- Interactive PR discussions

directly into Gitea.

Use OpenAI, Claude, Gemini or local Ollama models without forcing developers to leave their existing workflows.

👉 Think: **"Copilot-style workflow automation for Gitea."**

---

### 🔒 Need AI but cannot send source code to external services?

Many organizations cannot use cloud-only AI products due to compliance, privacy or contractual requirements.

AI-Git-Bot supports:

- Ollama
- llama.cpp
- Self-hosted Git platforms
- Private networks
- Provider-independent architectures

Source code, prompts, credentials and models remain under your control.

👉 Think: **"AI workflow automation without handing your repositories to a SaaS vendor."**

---

### 🚀 Maintaining repositories with too much engineering overhead?

Every repository accumulates engineering chores:

- Reviews
- Tests
- Documentation
- Acceptance criteria
- Follow-up fixes

AI-Git-Bot turns those activities into repeatable workflows triggered by events your team is already producing:

- Pull request opened
- Pull request reopened
- New commits pushed (opt-in per bot)
- Reviewer requested
- Issue assigned
- `@bot` mentioned

👉 Think: **"The AI teammate that never forgets the boring but important work."**

---

💡 Already using GitHub Copilot?
> **Great.**<br>
> Copilot helps developers write code faster. AI-Git-Bot helps teams automate reviews, tests, issues and pull-request workflows.<br>
> Many teams use both.

## 🔌 Mix any AI provider with any Git platform
| AI providers | Git platforms |
|---|---|
| **Anthropic** (Claude) | **Gitea** (self-hosted) |
| **OpenAI** (+ OpenAI-compatible APIs) | **GitHub** / **GitHub Enterprise** |
| **Google AI / Gemini** | **GitLab** (gitlab.com & self-managed) |
| **Ollama** (local LLMs) | **Bitbucket Cloud** |
| **llama.cpp** (local GGUF models) | |

Unlike most AI coding tools, AI-Git-Bot is not tied to a specific Git platform or AI provider.

**Fully self-hostable. Your code can stay inside your infrastructure.**

<p align="center">
  <img src="doc/images/dashboard_ai_git_bot.PNG" alt="AI-Git-Bot Dashboard" width="800"/>
</p>

---


## See it in action

AI-Git-Bot lives where your developers already work:

- GitHub
- GitHub Enterprise
- Gitea
- GitLab
- Bitbucket Cloud

1. Assign it a review.
2. Assign it an issue.
3. Mention it in a comment.
4. The bot responds directly inside your Git platform.
No extra dashboard.

No browser extension.

No Slack bot to babysit.

> 🎥 **Watch the PR workflows in action:** 
> * [E2E-Testing PR-workflow walkthrough on YouTube](https://www.youtube.com/watch?v=MjFmZHGIO-w)
> * [i18n-Sync PR-workflow walkthrough on YouTube](https://youtu.be/sIpqbOqy1Ek)
> * [Documentation-Sync PR-workflow walkthrough on YouTube](https://youtu.be/JZEhrmVJrqo)

---

## Screenshots

### Pull Request Reviews

AI-Git-Bot reviews pull requests and leaves actionable inline feedback directly on the diff.
<details>
<summary>📸 Screenshots: reviews, conversations, and coding agents across platforms</summary>

**Gitea:** <img src="doc/screenshots/gitea/screenshot_initial_code_review.png" alt="Gitea Code Review" width="600"/>

**GitHub:** <img src="doc/screenshots/github/github_code_review_with_comment.png" alt="GitHub Code Review" width="600"/>

**GitLab:** <img src="doc/screenshots/gitlab/gitlab-pull-request-with-code-review.png" alt="GitLab Code Review" width="600"/>

**Bitbucket:** <img src="doc/screenshots/bitbucket/bitbucket-code-review.png" alt="Bitbucket Code Review" width="600"/>

**Coding agent (GitHub):** <img src="doc/screenshots/github/github_issue_agent_code_implementation.png" alt="GitHub Agent" width="600"/>

</details>


---

### Interactive Discussions

Mention the bot anywhere in a pull request discussion.

```text
@bot can you explain why this implementation might fail?
```

The bot answers directly in the thread and keeps conversation context.

<details>
<summary>📸 Screenshot: Inline comment in Gitea</summary>

**Gitea:** <img src="doc/screenshots/gitea/screenshot_code_review_with_inline_comment.png" alt="Gitea Inline Comments" width="600"/>
</details>

---

### E2E Test Generation

Assign an PR to a bot and it can generate a Playwright test suite for the changes, deploy a preview environment, run the tests against that preview, and post the results back to the PR.

<details>
<summary>📸 Screenshot: E2E test generation as part of a PR</summary>

**Gitlab:** <img src="doc/screenshots/pr-workflow/gitea-pr-with-e2e-test-run.png" alt="E2E Tests in a Pull-Request" width="600"/>
</details>

---

### Coding Agents

Assign an issue to a coding bot and it can create an implementation pull request on your behalf.

<details>
<summary>📸 Screenshot: Issue implementation agent</summary>

**Gitlab:** <img src="doc/screenshots/gitlab/gitlab_issue_agent_code_implementation.png" alt="Coding Agent in Gitlab" width="600"/>
</details>

---

## ✨ What can it do?

| Workflow | Trigger | Result |
|-----------|----------|---------|
| **[PR Review](doc/PR_WORKFLOWS_REVIEW.md)** | PR opened or review re-requested | Review comments and findings |
| **[Interactive Q&A](doc/PR_WORKFLOWS_REVIEW.md)** | `@bot` mention in PR comments | Context-aware conversation |
| **[Issue → Code](doc/CODING_AGENT.md)** | Issue assigned to coding bot | Pull request |
| **[Issue → Better Issue](doc/WRITER_AGENT.md)** | Issue assigned to writer bot | Structured issue with acceptance criteria |
| **[Unit Test Generation](doc/PR_WORKFLOWS_UNIT_TEST.md)** | PR opened or command triggered | Generated tests committed to branch |
| **[Full-Stack QA](doc/PR_WORKFLOWS_E2E.md)** | PR opened | Playwright suite executed against preview environment |
| **[README Sync](doc/PR_WORKFLOWS_README_SYNC.md)** | PR opened or command triggered | Documentation updated to match code changes |
| **[i18n Coverage](doc/PR_WORKFLOWS_I18N_COVERAGE.md)** | PR opened or command triggered | Missing translations drafted across locale files |
| **[PR Re-Review](doc/PR_WORKFLOWS_REVIEW.md)** | Force-push or review request | Updated analysis |
| **[Workflow Automation](doc/PR_WORKFLOWS.md)** | Git events | Automated engineering chores |

---

## What makes AI-Git-Bot different?

Many AI development tools focus on helping developers write code.

AI-Git-Bot focuses on helping teams ship software more consistently.

Instead of only answering:

> "How do we write code faster?"

AI-Git-Bot tries to answer:

> "How do we make sure important engineering work doesn't get skipped?"

Examples include:

- Reviewing every pull request
- Adding regression tests
- Maintaining E2E coverage
- Keeping documentation in sync with the code
- Keeping translations in sync across locale files
- Improving issue quality
- Validating preview deployments
- Automating recurring engineering tasks

---

## Why not just use Copilot?

GitHub Copilot is excellent.

In fact, many teams will use both tools together.

A realistic workflow looks like this:

```text
Developer writes code with Copilot
           ↓
      Pull Request opens
           ↓
   AI-Git-Bot reviews it
           ↓
   AI-Git-Bot generates tests
           ↓
 AI-Git-Bot updates the docs
           ↓
 AI-Git-Bot validates deployment
           ↓
      Findings posted
```

Copilot helps developers write code faster.

AI-Git-Bot helps teams automate the work surrounding the code.

These goals complement each other.

---

## Current Workflows

### 🔍 Pull Request Reviews

Automatically review pull requests and provide:

- Summary findings
- Inline comments
- Suggested improvements
- Follow-up discussions

---

### ✏️ Issue Refinement

Assign a writer bot to an issue.

The bot transforms rough requirements into structured engineering work items containing:

- Background
- Requirements
- Acceptance criteria
- Implementation notes

---

### 🧪 Unit Test Generation

Generate white-box unit tests automatically based on pull request changes.

Tests can be validated using your project's own tooling before being committed.

---

### 🎬 Full-Stack QA

The Full-Stack QA workflow can:

1. Generate Playwright tests
2. Deploy a preview environment
3. Execute the suite
4. Publish the results back to the pull request
5. Clean up resources when the PR is closed

---

### 📝 README Sync

Keep project documentation in step with the code a pull request changes.

The workflow detects when a PR makes the README or other Markdown docs
inaccurate or outdated, then updates, adds, or removes the affected
documentation files within a configured scope and posts a short summary.
Markdown-only; every changed file stays inside the documentation patterns
you configure. Runs on PR open or on `@bot regenerate-readme <instruction>`.

---

### 🌍 i18n Coverage

Keep translations in sync across locale files when a pull request changes
user-facing strings.

The workflow compares every locale file against a configurable baseline locale
and, when a translation is missing keys the baseline defines (added or changed
strings) or still carries keys the baseline deleted, drafts the missing
translations per locale and removes the stale keys. Supports both
`messages_*.properties` and `i18n/*.json` files; every changed file stays inside
the patterns you configure. Runs on PR open or on
`@bot regenerate-i18n <instruction>`.

---
### 🤖 Issue → Pull Request

Assign a coding bot to an issue.

The bot:

1. Reads the issue
2. Clones the repository
3. Implements the change
4. Runs project validation
5. Opens a pull request

---
## Quick Start

Run AI-Git-Bot locally using Docker Compose.

```bash
git clone https://github.com/tmseidel/ai-git-bot.git
cd ai-git-bot
docker compose up --build -d
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

## Pick your path

### 👀 Just evaluating the project?

Start with:

- **[The Pitch](doc/pitch/PITCH.md)**
- **[Architecture Overview](doc/ARCHITECTURE.md)**

---

### 🏢 Running Gitea?

Start with:

- **[Gitea Setup Guide](doc/GITEA_SETUP.md)**
- **[Quick Start](doc/USING_THE_BOT.md)**

---

### 🔒 Looking for self-hosted AI?

Start with:

- **[Deployment Guide](doc/DEPLOYMENT.md)**
- **[Ollama Integration Guide](doc/OLLAMA.md)** (or vLLM with OpenAI-compatible API)

---

### 🤖 Ready to automate workflows?

Start with:

- **[User Guide](doc/USER_GUIDE.md)**
- **[Workflow Documentation](doc/PR_WORKFLOWS.md)**

---

### 🧑‍💻 Want to contribute?

Start with:

- **[Local Development](doc/LOCAL_DEVELOPMENT.md)**
- **[Architecture Documentation](doc/ARCHITECTURE.md)**

---

## 📚 Documentation

The documentation is organized by audience in the **[Documentation Hub](doc/README.md)**:

| You are a… | Start here |
|---|---|
| 👤 **User** — a bot is already set up, you just use the Git platform | [Using the Bot](doc/USING_THE_BOT.md) |
| 🛠️ **Administrator** — you set up the software, bots, and workflows | [Deployment](doc/DEPLOYMENT.md) · [Admin Guide](doc/USER_GUIDE.md) |
| 🧪 **Tester** — you want to try out features safely | [Testing Guide](doc/TESTING_GUIDE.md) |
| 💻 **Developer** — you work with the code | [Local Development](doc/LOCAL_DEVELOPMENT.md) · [Architecture](doc/ARCHITECTURE.md) |

---

## Project Maturity

### Production-ready
* GitHub
* GitHub Enterprise
* Gitea

### 🧪 Community feedback welcome- 
* GitLab
* Bitbucket Cloud

### Experimental Workflows

⚠️ Full-Stack QA / E2E automation

The project ships extensive system tests and sample environments to make validation and troubleshooting easier.

Bug reports are always welcome.

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
## Community
* ⭐ >100 GitHub stars
* 🚀 >15 releases
* 🐳 Docker image available
* 🌍 Users across GitHub, Gitea, GitLab and Bitbucket

## Get started

```bash
docker pull tmseidel/ai-git-bot:latest
```


---

## The bottom line

AI-Git-Bot is not another coding assistant.

It's a self-hosted automation layer for software delivery workflows.

If your team already knows what good engineering practices look like—but struggles to do them consistently—AI-Git-Bot was built for exactly that problem.

Wire one bot.

Let the chores take care of themselves.

🚀 Happy shipping.

## License

[MIT](LICENSE)