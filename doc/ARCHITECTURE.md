# Architecture — AI-Git-Bot

This document describes the high-level architecture of the AI-Git-Bot, the intelligent **Gateway** between Git platforms and AI providers. It covers component responsibilities, the Gateway design pattern, and request flows.

## The Gateway Concept

AI-Git-Bot acts as a **central gateway** that decouples Git hosting platforms from AI providers. This means:

- **Any Git platform** (Gitea, GitHub, GitLab, Bitbucket) can be connected with **any AI provider** (Anthropic, OpenAI, Ollama, llama.cpp)
- Multiple bots with different configurations can run in parallel
- All webhook routing, session management, and credential handling is centralized in a single application
- The same AI configuration can serve multiple repositories across different Git platforms

## System Overview

```mermaid
graph LR
    Git["Git Platform<br/>(Gitea / GitHub / GitLab / Bitbucket)"]
    Bot["AI-Git-Bot<br/>(Gateway)"]
    AI["AI Provider<br/>(Anthropic / OpenAI / Ollama / llama.cpp)"]
    DB["PostgreSQL Database"]

    Git -- "Webhook (PR/Comment/Review event)" --> Bot
    Bot -- "Fetch PR diff" --> Git
    Bot -- "Post review/comment" --> Git
    Bot -- "Fetch reviews & comments" --> Git
    Bot -- "Add reaction" --> Git
    Bot -- "Review diff / Chat" --> AI
    AI -- "Review text" --> Bot
    Bot -- "Config & Sessions" --> DB
```

The gateway sits between Git hosting platforms (Gitea, GitHub, GitLab, or Bitbucket) and configurable AI providers. When a pull request is opened with the bot as reviewer, or the bot is later added/re-requested as reviewer, the Git provider sends a webhook to the gateway. The gateway fetches the diff, sends it to the configured AI provider for review, and posts the review back as a PR comment. All configuration (AI integrations, Git integrations, bots) and conversation sessions are persisted in a database.

The gateway also responds to inline review comments and submitted reviews containing bot mentions by fetching the relevant review data from the Git API and posting context-aware replies. In **agent mode**, it supports two issue-based workflows: a **coding agent** that implements issues and opens pull requests, and a **technical writer agent** that improves vague issues into structured, implementation-ready follow-up issues.

## Component Diagram

```mermaid
graph TD
    subgraph "Spring Boot Application"
        subgraph "Web Layer"
            GiteaWebhookController["GiteaWebhookController<br/><i>Gitea webhook endpoints</i>"]
            GitHubWebhookController["GitHubWebhookController<br/><i>GitHub webhook endpoints</i>"]
            AdminControllers["Admin Controllers<br/><i>Dashboard, Bots, Integrations</i>"]
            SetupController["SetupController<br/><i>Initial setup</i>"]
        end
        
        subgraph "Service Layer"
            BotService["BotService<br/><i>Bot CRUD</i>"]
            BotWebhookService["BotWebhookService<br/><i>Webhook processing</i>"]
            AiIntegrationService["AiIntegrationService<br/><i>AI config CRUD</i>"]
            GitIntegrationService["GitIntegrationService<br/><i>Git config CRUD</i>"]
            SessionService["SessionService<br/><i>Session lifecycle</i>"]
            EncryptionService["EncryptionService<br/><i>API key encryption</i>"]
        end

        subgraph "AI Provider Layer"
            AiClientFactory["AiClientFactory<br/><i>Client creation & caching</i>"]
            AiProviderRegistry["AiProviderRegistry<br/><i>Provider discovery</i>"]
            subgraph "Provider Metadata"
                AnthropicMeta["AnthropicProviderMetadata"]
                OpenAiMeta["OpenAiProviderMetadata"]
                OllamaMeta["OllamaProviderMetadata"]
                LlamaCppMeta["LlamaCppProviderMetadata"]
            end
            subgraph "AI Clients"
                AiInterface["AiClient<br/><i>Interface</i>"]
                AbstractClient["AbstractAiClient<br/><i>Chunking & retry</i>"]
                AnthropicImpl["AnthropicAiClient"]
                OpenAiImpl["OpenAiClient"]
                OllamaImpl["OllamaClient"]
                LlamaCppImpl["LlamaCppClient"]
            end
        end

        subgraph "Repository Provider Layer"
            RepoClientFactory["RepositoryClientFactory<br/><i>Client creation</i>"]
            RepoProviderRegistry["RepositoryProviderRegistry<br/><i>Provider discovery</i>"]
            subgraph "Repository Provider Metadata"
                GiteaMeta["GiteaProviderMetadata"]
                GitHubMeta["GitHubProviderMetadata"]
                GitLabMeta["GitLabProviderMetadata"]
                BitbucketMeta["BitbucketProviderMetadata"]
            end
            subgraph "Repository Clients"
                RepoInterface["RepositoryApiClient<br/><i>Interface</i>"]
                GiteaClient["GiteaApiClient"]
                GitHubClient["GitHubApiClient"]
                GitLabClient["GitLabApiClient"]
                BitbucketClient["BitbucketApiClient"]
            end
        end

        subgraph "Repository Layer"
            BotRepo["BotRepository"]
            AiIntegrationRepo["AiIntegrationRepository"]
            GitIntegrationRepo["GitIntegrationRepository"]
            SessionRepo["ReviewSessionRepository"]
            AdminRepo["AdminUserRepository"]
        end
    end

    subgraph "External"
        Gitea["Gitea"]
        GitHub["GitHub / GitHub Enterprise"]
        GitLab["GitLab / GitLab CE/EE"]
        Bitbucket["Bitbucket Cloud"]
        Anthropic["Anthropic API"]
        OpenAI["OpenAI API"]
        Ollama["Ollama (local)"]
        LlamaCpp["llama.cpp (local)"]
        PromptFiles["Prompt Files<br/><i>prompts/*.md</i>"]
        DB["Database<br/><i>PostgreSQL / H2</i>"]
    end

    GiteaWebhookController --> BotService
    GiteaWebhookController --> BotWebhookService
    GitHubWebhookController --> BotService
    GitHubWebhookController --> BotWebhookService
    BotWebhookService --> AiClientFactory
    BotWebhookService --> RepoClientFactory
    BotWebhookService --> SessionService
    AiClientFactory --> AiProviderRegistry
    AiClientFactory --> AiIntegrationService
    RepoClientFactory --> RepoProviderRegistry
    RepoClientFactory --> GitIntegrationService
    AiProviderRegistry --> AnthropicMeta
    AiProviderRegistry --> OpenAiMeta
    AiProviderRegistry --> OllamaMeta
    AiProviderRegistry --> LlamaCppMeta
    RepoProviderRegistry --> GiteaMeta
    RepoProviderRegistry --> GitHubMeta
    RepoProviderRegistry --> GitLabMeta
    RepoProviderRegistry --> BitbucketMeta
    AnthropicMeta --> AnthropicImpl
    OpenAiMeta --> OpenAiImpl
    OllamaMeta --> OllamaImpl
    LlamaCppMeta --> LlamaCppImpl
    GiteaMeta --> GiteaClient
    GitHubMeta --> GitHubClient
    GitLabMeta --> GitLabClient
    BitbucketMeta --> BitbucketClient
    AiInterface -.-> AbstractClient
    AbstractClient -.-> AnthropicImpl
    AbstractClient -.-> OpenAiImpl
    AbstractClient -.-> OllamaImpl
    AbstractClient -.-> LlamaCppImpl
    RepoInterface -.-> GiteaClient
    RepoInterface -.-> GitHubClient
    RepoInterface -.-> GitLabClient
    RepoInterface -.-> BitbucketClient
    AnthropicImpl --> Anthropic
    OpenAiImpl --> OpenAI
    OllamaImpl --> Ollama
    LlamaCppImpl --> LlamaCpp
    GiteaClient --> Gitea
    GitHubClient --> GitHub
    GitLabClient --> GitLab
    BitbucketClient --> Bitbucket
    BotRepo --> DB
    SessionRepo --> DB
```

## AI Provider Architecture

The bot uses a **provider-agnostic abstraction layer** with metadata-driven configuration:

### AiProviderMetadata Interface

Each AI provider implements `AiProviderMetadata` to define:
- Provider type identifier (e.g., "anthropic", "openai")
- Default API URL
- Suggested models list
- Whether API key is required
- How to build the `RestClient`
- How to create the `AiClient` instance

```
AiProviderMetadata (interface)
 ├── AnthropicProviderMetadata
 │    └── Default URL: https://api.anthropic.com
 │    └── Models: claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001
 ├── OpenAiProviderMetadata
 │    └── Default URL: https://api.openai.com
 │    └── Models: gpt-5.4, gpt-5.3-codex, gpt-5.1-codex-max, gpt-5-codex
 ├── OllamaProviderMetadata
 │    └── Default URL: http://localhost:11434
 │    └── Models: (user-configured)
 └── LlamaCppProviderMetadata
      └── Default URL: http://localhost:8081
      └── Models: (user-configured)
```

### AiProviderRegistry

Spring `@Service` that collects all `AiProviderMetadata` beans and provides:
- List of available provider types
- Lookup by provider type
- Maps of default API URLs and suggested models (for UI)

### AiClientFactory

Creates and caches `AiClient` instances per `AiIntegration`:
- Uses `AiProviderRegistry` to find the correct metadata
- Delegates to metadata for `RestClient` and `AiClient` creation
- Caches clients by integration ID + `updatedAt` timestamp
- Automatically rebuilds clients when configuration changes

### AiClient Hierarchy

```
AiClient (interface)
 └── AbstractAiClient (abstract class — chunking, retry, message building)
      ├── AnthropicAiClient (Anthropic Messages API)
      ├── OpenAiClient (OpenAI Chat Completions API)
      ├── OllamaClient (Ollama /api/chat)
      └── LlamaCppClient (llama.cpp /v1/chat/completions with GBNF grammar)
```

### Provider Differences

| Feature | Anthropic | OpenAI | Ollama | llama.cpp |
|---------|-----------|--------|--------|-----------|
| System prompt | Top-level `system` field | `role: "system"` message | `role: "system"` message | `role: "system"` message |
| Endpoint | `/v1/messages` | `/v1/chat/completions` | `/api/chat` | `/v1/chat/completions` |
| Auth | `x-api-key` header | `Bearer` token | None | None |
| Streaming | Not used | Not used | Disabled (`stream: false`) | Disabled (`stream: false`) |
| JSON Mode | N/A | N/A | `format: "json"` | GBNF grammar |

## Repository Provider Architecture

The bot uses a similar **provider-agnostic abstraction layer** for Git hosting platforms:

### RepositoryProviderMetadata Interface

Each Git provider implements `RepositoryProviderMetadata` to define:
- Provider type identifier (e.g., "gitea", "github")
- Default web URL
- How to resolve API URLs from web URLs
- How to resolve clone URLs
- How to build the authorization header
- How to build the `RestClient`
- How to create the `RepositoryApiClient` instance

```
RepositoryProviderMetadata (interface)
 ├── GiteaProviderMetadata
 │    └── Default URL: https://gitea.example.com
 │    └── Auth: token <token>
 │    └── API: Same base URL with /api/v1 paths
 ├── GitHubProviderMetadata
 │    └── Default URL: https://github.com
 │    └── Auth: Bearer <token>
 │    └── API: api.github.com (public) or <host>/api/v3 (Enterprise)
 ├── GitLabProviderMetadata
 │    └── Default URL: https://gitlab.com
 │    └── Auth: PRIVATE-TOKEN <token>
 │    └── API: Same base URL with /api/v4 paths
 └── BitbucketProviderMetadata
      └── Default URL: https://bitbucket.org
      └── Auth: Basic <username:token> or Bearer <token>
      └── API: api.bitbucket.org/2.0
```

### RepositoryProviderRegistry

Spring `@Service` that collects all `RepositoryProviderMetadata` beans and provides:
- List of available provider types
- Lookup by provider type
- Maps of default URLs (for UI)

### RepositoryApiClient Interface

All Git provider clients implement this interface:

```
RepositoryApiClient (interface)
 ├── GiteaApiClient
 ├── GitHubApiClient
 ├── GitLabApiClient
 └── BitbucketApiClient
```

Methods include:
- `getPullRequestDiff()` — Fetch PR diff
- `postComment()` — Post PR comment
- `postReviewComment()` — Post review with body
- `addReaction()` — Add emoji reaction
- `getFileContent()` — Get file content for context
- `getIssueDetails()` / `searchIssues()` — Issue context for coding and writer agents
- `getRepositoryTree()` / `getDefaultBranch()` — Repository context bootstrap
- `createBranch()` / `commitFile()` / `createPullRequest()` — Coding-agent operations
- `createIssue()` — Writer-agent output creation

### Provider Differences

| Feature | Gitea | GitHub | GitLab | Bitbucket Cloud |
|---------|-------|--------|--------|-----------------|
| Auth Header | `token <token>` | `Bearer <token>` | `PRIVATE-TOKEN: <token>` | `Basic` or `Bearer` |
| API Base | `<url>/api/v1` | `api.github.com` or `<host>/api/v3` | `<url>/api/v4` | `api.bitbucket.org/2.0` |
| PR Diff | `/repos/{owner}/{repo}/pulls/{pr}/diff` | `/repos/{owner}/{repo}/pulls/{pr}` with `Accept: diff` | `/projects/{id}/repository/compare` | `/repositories/{workspace}/{repo}/pullrequests/{pr}/diff` |
| Reactions | Text-based (`:eyes:`) | Text-based (`eyes`) | Not supported (no-op) | Not supported |
| Project ID | `{owner}/{repo}` | `{owner}/{repo}` | URL-encoded `{owner}%2F{repo}` | `{workspace}/{repo}` |

## Entity Model

```mermaid
erDiagram
    AdminUser {
        Long id PK
        String username UK
        String passwordHash
        Instant createdAt
    }
    
    AiIntegration {
        Long id PK
        String name UK
        String providerType
        String apiUrl
        String apiKey
        String apiVersion
        String model
        int maxTokens
        int maxDiffCharsPerChunk
        int maxDiffChunks
        int retryTruncatedChunkChars
        Instant createdAt
        Instant updatedAt
    }
    
    GitIntegration {
        Long id PK
        String name UK
        RepositoryType providerType
        String url
        String token
        Instant createdAt
        Instant updatedAt
    }
    
    Bot {
        Long id PK
        String name UK
        String username
        BotType botType
        String prompt
        String webhookSecret UK
        boolean enabled
        boolean agentEnabled
        long webhookCallCount
        Instant lastWebhookAt
        String lastError
        Instant lastErrorAt
        Instant createdAt
        Instant updatedAt
    }
    
    ReviewSession {
        Long id PK
        String repoOwner
        String repoName
        int prNumber
        Instant createdAt
        Instant updatedAt
    }
    
    ConversationMessage {
        Long id PK
        String role
        String content
        Instant createdAt
    }
    
    Bot ||--o{ AiIntegration : "uses"
    Bot ||--o{ GitIntegration : "uses"
    ReviewSession ||--|{ ConversationMessage : "contains"
```

## Components

### Webhook Controllers

#### GiteaWebhookController

- **Package:** `org.remus.giteabot.gitea`
- **Endpoint:** `POST /api/webhook/{webhookSecret}`
- Receives Gitea webhook payloads for pull request, issue comment, and review comment events
- Looks up Bot by webhook secret
- Routes events based on payload structure to `BotWebhookService`

#### GitHubWebhookController

- **Package:** `org.remus.giteabot.github`
- **Endpoint:** `POST /api/github-webhook/{webhookSecret}`
- Receives GitHub webhook payloads for pull request, issue comment, and review comment events
- Looks up Bot by webhook secret
- Converts GitHub payload format to common event model
- Routes events to `BotWebhookService`

### BotWebhookService

- **Package:** `org.remus.giteabot.admin`
- Processes webhook events for a specific bot
- Gets AI client from `AiClientFactory` using bot's `AiIntegration`
- Creates Git client using bot's `GitIntegration`
- Routes issue workflows by `BotType`:
  - `CODING` → `IssueImplementationService` (when `agentEnabled` is true)
  - `WRITER` → `WriterAgentService`
- Handles:
  - PR reviews (opened, synchronized)
  - Bot commands (PR comments with mention)
  - Inline review comments
  - Review submitted events
  - Issue assignments and issue-comment follow-ups for both issue-based agent modes

### IssueImplementationService

- **Package:** `org.remus.giteabot.agent`
- Runs the coding-agent workflow for assigned issues
- Prepares a writable workspace and executes file + validation tools
- Creates feature branches, commits changes, and opens pull requests
- Stores lifecycle state such as `PR_CREATED`, `UPDATING`, and `FAILED` in `AgentSession`

### WriterAgentService

- **Package:** `org.remus.giteabot.agent.writerimpl`
- Runs the technical-writer workflow for assigned issues
- Prepares a **read-only** workspace for repository exploration
- Uses repository context tools and issue tools (`get-issue`, `search-issues`) to improve issue quality
- Restricts follow-up continuation to the original issue author when clarifying questions are pending
- Creates a linked `AI Created Issue: ...` instead of a pull request

### AiClientFactory

- **Package:** `org.remus.giteabot.admin`
- Creates and caches `AiClient` instances
- Uses `AiProviderRegistry` for provider lookup
- Rebuilds clients when integration config changes

### AiProviderRegistry

- **Package:** `org.remus.giteabot.ai`
- Collects all `AiProviderMetadata` implementations via Spring DI
- Provides provider lookup and metadata access

### AiProviderMetadata Implementations

- **Packages:** `org.remus.giteabot.ai.{anthropic,openai,ollama,llamacpp}`
- Define provider-specific defaults and client creation logic
- Registered as `@Component` beans

### RepositoryProviderMetadata Implementations

- **Package:** `org.remus.giteabot.repository`
- `GiteaProviderMetadata` — Gitea API client factory
- `GitHubProviderMetadata` — GitHub API client factory
- `GitLabProviderMetadata` — GitLab API client factory (uses `PRIVATE-TOKEN` header, URL-encoded project paths)
- `BitbucketProviderMetadata` — Bitbucket Cloud API client factory
- Define provider-specific URL resolution and client creation
- Registered as `@Component` beans

### SessionService

- **Package:** `org.remus.giteabot.session`
- Manages the lifecycle of review sessions per PR
- Stores conversation messages for context
- Sessions identified by (repoOwner, repoName, prNumber)

### EncryptionService

- **Package:** `org.remus.giteabot.admin`
- Encrypts API keys and tokens using AES-256-GCM
- Uses `APP_ENCRYPTION_KEY` environment variable

## Request Flows

### Per-Bot Webhook Flow

```mermaid
sequenceDiagram
    participant Git as Git Provider
    participant Controller as WebhookController
    participant BotService
    participant BotWebhook as BotWebhookService
    participant AiFactory as AiClientFactory
    participant RepoFactory as RepositoryClientFactory
    participant AI as AiClient
    participant GitAPI as Git API

    Git->>Controller: POST /api/webhook/{secret}
    Controller->>BotService: findByWebhookSecret(secret)
    BotService-->>Controller: Bot
    Controller->>BotWebhook: handleBotWebhookEvent(bot, payload)
    BotWebhook->>AiFactory: getClient(bot.aiIntegration)
    AiFactory-->>BotWebhook: AiClient (cached)
    BotWebhook->>RepoFactory: getClient(bot.gitIntegration)
    RepoFactory-->>BotWebhook: RepositoryApiClient
    BotWebhook->>GitAPI: getPullRequestDiff()
    GitAPI-->>BotWebhook: diff
    BotWebhook->>AI: reviewDiff(diff, prompt)
    AI-->>BotWebhook: review text
    BotWebhook->>GitAPI: postReviewComment(review)
```

### Bot Command Flow

```mermaid
sequenceDiagram
    participant User
    participant Git as Git Provider
    participant Controller as WebhookController
    participant BotWebhook as BotWebhookService
    participant Session as SessionService
    participant Factory as AiClientFactory
    participant AI as AiClient
    participant GitAPI as Git API

    User->>Git: Comment: "@ai_bot explain this"
    Git->>Controller: POST /api/webhook/{secret}
    Controller->>BotWebhook: handleBotCommand(bot, payload)
    BotWebhook->>GitAPI: addReaction(commentId, "eyes")
    BotWebhook->>Session: getOrCreateSession(owner, repo, pr)
    Session-->>BotWebhook: session (with history)
    BotWebhook->>Factory: getClient(bot.aiIntegration)
    Factory-->>BotWebhook: AiClient
    BotWebhook->>AI: chat(history, comment, prompt)
    AI-->>BotWebhook: response
    BotWebhook->>Session: addMessage("user", comment)
    BotWebhook->>Session: addMessage("assistant", response)
    BotWebhook->>GitAPI: postComment(response)
```

### Technical Writer Flow

```mermaid
sequenceDiagram
    participant User
    participant Git as Git Provider
    participant Controller as WebhookController
    participant BotWebhook as BotWebhookService
    participant Writer as WriterAgentService
    participant Session as AgentSessionService
    participant AI as AiClient
    participant GitAPI as Git API

    User->>Git: Assign Writer bot to issue
    Git->>Controller: Issue webhook
    Controller->>BotWebhook: handleIssueAssigned(bot, payload)
    BotWebhook->>Writer: handleIssueAssigned(payload)
    Writer->>Session: create writer AgentSession
    Writer->>GitAPI: post "reviewing" comment
    Writer->>GitAPI: getIssueDetails + getRepositoryTree
    Writer->>AI: Ask for clarifying questions or revised draft
    alt Critical details missing
        AI-->>Writer: clarifyingQuestions
        Writer->>GitAPI: post questions
    else Enough information available
        AI-->>Writer: revisedIssueDraft + readyToCreate=true
        Writer->>GitAPI: createIssue("AI Created Issue: ...")
        Writer->>Session: mark ISSUE_CREATED
    end
```

## Webhook Routing Flow

```mermaid
flowchart TD
    A["Webhook received at /api/webhook/{secret}"] --> B{Bot found?}
    B -- No --> Z["404 Not Found"]
    B -- Yes --> C{Bot enabled?}
    C -- No --> Y["200 'bot disabled'"]
    C -- Yes --> D{Is bot's own action?}
    D -- Yes --> X["200 'ignored'"]
    D -- No --> E{comment with path?}
    E -- Yes --> F["handleInlineComment()"]
    E -- No --> G{comment + issue?}
    G -- Yes --> H{Bot mentioned?}
    H -- No --> X
    H -- Yes --> I{Is PR?}
    I -- Yes --> J["handleBotCommand()"]
    I -- No --> K["handleIssueComment() → writer or coding issue flow"]
    G -- No --> L{Issue assigned to bot?}
    L -- Yes --> M["handleIssueAssigned() → writer or coding issue flow"]
    L -- No --> N{pullRequest present?}
    N -- No --> X
    N -- Yes --> O{action = reviewed?}
    O -- Yes --> P["handleReviewSubmitted()"]
    O -- No --> Q{action = closed?}
    Q -- Yes --> R["handlePrClosed()"]
    Q -- No --> S{action = opened/synchronized?}
    S -- Yes --> T["reviewPullRequest()"]
    S -- No --> X
```

The issue paths above are resolved inside `BotWebhookService` by `botType`. Writer bots ignore PR-review-related handlers and only participate in issue-assignment and issue-comment flows.

## Agent Session Model

Issue-based workflows share the `AgentSession` entity, which stores:

- repository identity (`repoOwner`, `repoName`, `issueNumber`)
- current base branch (`branchName`)
- the outcome reference (`prNumber` for coding agent, `generatedIssueNumber` for writer agent)
- the original issue author (`issueAuthorUsername`) for writer follow-up authorization
- the workflow discriminator `sessionType` (`CODING` or `WRITER`)
- the lifecycle status (`IN_PROGRESS`, `UPDATING`, `PR_CREATED`, `ISSUE_CREATED`, `FAILED`, `COMPLETED`)

## Docker Deployment

```mermaid
graph LR
    subgraph "Docker Compose"
        subgraph "App Container"
            App["app.jar<br/>(Spring Boot)"]
            Prompts["/app/prompts/<br/>Prompt templates"]
        end
        subgraph "DB Container"
            Postgres["PostgreSQL 17<br/>(Config & Sessions)"]
            PGData["pgdata volume"]
        end
    end

    Host["Host filesystem<br/>./prompts/"] -- "bind mount :ro" --> Prompts
    App -- reads --> Prompts
    App -- "JDBC" --> Postgres
    Postgres -- stores --> PGData
```

- All configuration (AI integrations, Git integrations, bots) is stored in the database
- The `prompts/` directory contains prompt templates loaded at runtime
- PostgreSQL persists configuration and review sessions
- Session data survives container restarts via the `pgdata` volume

