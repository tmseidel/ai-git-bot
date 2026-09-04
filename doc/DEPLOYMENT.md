# Deployment

This guide covers deploying the AI-Git-Bot Gateway using Docker Compose.

## Prerequisites

- **Docker** and **Docker Compose** installed on an `amd64` (x86-64) or
  `arm64` (aarch64) host — see [Architectures](#architectures)
- A **Git hosting platform** configured:
  - Gitea: See [Gitea Setup](GITEA_SETUP.md)
  - GitHub / GitHub Enterprise: See [GitHub Setup](GITHUB_SETUP.md)
  - GitLab / GitLab CE/EE: See [GitLab Setup](GITLAB_SETUP.md)
  - Bitbucket Cloud: See [Bitbucket Setup](BITBUCKET_SETUP.md)
- API credentials for your chosen AI provider (Anthropic, OpenAI) or a local Ollama/llama.cpp instance

## Quick Start

```bash
docker compose up --build -d
```

This starts:
- The bot application on port **8080**
- A **PostgreSQL 17** database for configuration and session persistence

Then:
1. Navigate to `http://localhost:8080` to complete initial setup
2. Create your admin account
3. Configure AI and Git integrations via the web UI
4. Create a bot and configure webhooks in your Git provider (Gitea, GitHub, GitLab, or Bitbucket)

See the [User Guide](USER_GUIDE.md) for detailed instructions.

## Docker Compose Template

Save the following as `docker-compose.yml`:

```yaml
services:
  app:
    image: tmseidel/ai-git-bot:latest
    # Or build locally:
    # build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: giteabot
      DATABASE_PASSWORD: change-me
      APP_ENCRYPTION_KEY: your-secure-encryption-key-here
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

> **Note:** Replace placeholders with your actual values. For sensitive values, consider using a `.env` file or Docker secrets.

## Environment Variables

### Required

| Variable | Description |
|----------|-------------|
| `APP_ENCRYPTION_KEY` | Encryption key for sensitive data (API keys, tokens). Set to a fixed value for persistence across restarts. If not set, a random key is generated (data won't survive restarts). |
| `DATABASE_URL` | JDBC connection URL (default: `jdbc:postgresql://db:5432/giteabot`) |
| `DATABASE_USERNAME` | Database username (default: `giteabot`) |
| `DATABASE_PASSWORD` | Database password |

### Secret References (Optional)

Configuration fields that support [secret references](SECRET_REFERENCES.md) can pull values from the environment with `${env:NAME}` instead of holding the credential itself. Only explicitly whitelisted variables are readable:

| Variable | Default | Description |
|----------|---------|-------------|
| `GITEABOT_SECRET_ENV_WHITELIST` | _(empty)_ | Comma-separated names of environment variables that `${env:NAME}` references may read, e.g. `MY_API_TOKEN,CI_DEPLOY_KEY`. Names are matched verbatim (case-sensitive). Empty means no environment variable is readable. |

Whitelisting a name only permits it to be read — the variable itself still has to reach the process. In Docker, add it to the service's `environment:` block next to `GITEABOT_SECRET_ENV_WHITELIST`; Compose does not forward arbitrary host variables into the container.

> **The application refuses to start** if the whitelist names a variable it reads for its own configuration (`GITEABOT_*`, `SPRING_*`, `DATABASE_*`, `APP_ENCRYPTION_KEY`). See [Reserved names](SECRET_REFERENCES.md#reserved-names).

See [Secret References](SECRET_REFERENCES.md) for the syntax, resolution behavior and the available secret sources.

### Agent Configuration (Optional)

The **coding agent** is enabled per coding bot via the web UI. Writer workflows are selected separately by choosing **Bot Type = Writer bot**. These environment variables configure global coding-agent behavior:

| Variable | Default | Description |
|----------|---------|-------------|
| `AGENT_ENABLED` | `true` | Enable/disable the coding agent globally. |
| `AGENT_MAX_FILES` | `20` | Maximum files the agent can modify per issue. |
| `AGENT_BRANCH_PREFIX` | `ai-agent/` | Prefix for branches created by the agent. |
| `AGENT_MAX_FILE_CONTENT_CHARS` | `100000` | Maximum characters of file content included in prompts. Reduce for local models with smaller context windows. |
| `AGENT_VALIDATION_ENABLED` | `true` | Enable syntax validation before commit. |
| `AGENT_BUDGET_MAX_CONTENT_ROUNDS` | `10` | Maximum context rounds before the agent stops. |
| `AGENT_BUDGET_MAX_ROUNDS` | `20` | Maximum total rounds before the agent stops. |
| `AGENT_BUDGET_MAX_CONTEXT_TOOL_REQUESTS_PER_ROUND` | `10` | Maximum context tool requests per round. |
| `AGENT_BUDGET_MAX_TOKENS_PER_CALL` | `16384` | Maximum tokens per AI call. |
| `AGENT_BUDGET_MAX_VALIDATION_RETRIES` | `10` | Maximum iterations for error correction. |
| `AGENT_BUDGET_MAX_HISTORY_CHARS` | `180000` | Maximum characters of conversation history kept in context. |
| `AGENT_BUDGET_MAX_TOOL_RESULT_CHARS` | `8000` | Maximum characters of tool results kept in context. |

### Agent Context (Optional)

These variables control how much repository and issue context is sent to the agent:

| Variable | Default | Description |
|----------|---------|-------------|
| `AGENT_CONTEXT_MAX_TREE_FILES` | `500` | Maximum repository tree files included in agent context. |
| `AGENT_CONTEXT_MAX_ISSUE_COMMENTS` | `50` | Maximum issue comments included in agent context. |
| `AGENT_CONTEXT_MAX_ISSUE_COMMENTS_CHARS` | `20000` | Maximum total characters of issue comments. |
| `AGENT_CONTEXT_MAX_SINGLE_ISSUE_COMMENT_CHARS` | `4000` | Maximum characters per individual issue comment. |
| `AGENT_TRIAGE_MAX_TOOL_ROUNDS` | `5` | Maximum tool rounds for issue triage. |
| `AGENT_TRIAGE_MAX_INITIAL_TREE_FILES` | `100` | Maximum initial tree files fetched during triage. |

See [Agent Documentation](AGENT.md) for full details.

### Workspaces (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `GITEABOT_WORKSPACES_DIR` |  | Optional host-visible root for private agent workspace parents. |

### Review Context (Optional)

These variables control how much context is sent to the AI alongside the diff:

| Variable | Default | Description |
|----------|---------|-------------|
| `REVIEW_MAX_FILE_CONTENT_CHARS` | `30000` | Maximum file content chars sent alongside the diff. |
| `REVIEW_MAX_SINGLE_FILE_CHARS` | `10000` | Maximum chars per single file in review context. |
| `REVIEW_MAX_TREE_FILES` | `500` | Maximum repository tree files included in review context. |
| `REVIEW_MAX_COMMIT_MESSAGES` | `50` | Maximum commit messages included in review context. |

### Review Diff Chunking (Optional)

These variables control how the PR diff is split for review:

| Variable | Default | Description |
|----------|---------|-------------|
| `REVIEW_CHUNKING_MAX_DIFF_CHARS_PER_CHUNK` | `120000` | Maximum diff chars per review chunk. |
| `REVIEW_CHUNKING_MAX_DIFF_CHUNKS` | `8` | Maximum number of diff chunks. |
| `REVIEW_CHUNKING_RETRY_TRUNCATED_CHUNK_CHARS` | `60000` | Diff chunk size when retrying a truncated chunk. |

### Prompts (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `PROMPTS_DIR` | `prompts` | Directory containing prompt files. The Docker profile overrides this to `/app/prompts`. |
| `PROMPTS_DEFAULT_FILE` | `default.md` | Default system prompt file. |
| `PROMPTS_AGENT_FILE` | `agent.md` | Coding agent prompt file. |
| `PROMPTS_LOCAL_LLM_FILE` | `local-llm.md` | Local LLM prompt file. |

### AI Usage Audit (Optional)

These variables control the optional raw request/response payload capture on the Usage page:

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_USAGE_RAW_PAYLOADS_ENABLED` | `false` | Set to `true` to store raw AI request/response JSON in the audit log and show the **Details** column on the Usage page. |
| `AI_USAGE_MAX_RAW_PAYLOAD_LENGTH` | `65535` | Maximum characters stored per raw payload. Longer payloads are truncated to this length (capped at the database column size). |

### Anthropic Extended Thinking (Optional)

With the Anthropic provider, the model can interleave its own reasoning with the
visible review text. Because that narration is part of the assistant message, it
ends up verbatim in the posted review comment. Enabling adaptive extended
thinking moves the reasoning into dedicated `thinking` content blocks that the
client discards, so only the review itself is published. Opt-in and disabled by
default; requires a Claude 5.x model (`claude-fable-5`, `claude-opus-5`,
`claude-sonnet-5`).

| Variable | Default | Description |
|----------|---------|-------------|
| `ANTHROPIC_EXTENDED_THINKING_ENABLED` | `false` | Set to `true` to enable adaptive extended thinking for the Anthropic provider. |
| `ANTHROPIC_EXTENDED_THINKING_EFFORT` | `high` | How often and how deeply the model thinks: `low`, `medium`, `high`, `xhigh`, or `max`. Higher efforts need larger max tokens. |

### Security / Web UI Login (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `GITEABOT_SECURITY_LOGIN_METHOD` | `native` | Authentication method: `native` (database-backed login form) or `oauth` (OAuth 2.0 / OIDC). |
| `GITEABOT_SECURITY_OAUTH_CLIENT_ID` |  | OAuth client ID. |
| `GITEABOT_SECURITY_OAUTH_CLIENT_SECRET` |  | OAuth client secret. |
| `GITEABOT_SECURITY_OAUTH_ISSUER_URI` |  | OIDC issuer URI. When set, Spring Security discovers endpoints automatically. |
| `GITEABOT_SECURITY_OAUTH_AUTHORIZATION_URI` |  | OAuth authorization endpoint (only needed without issuer discovery). |
| `GITEABOT_SECURITY_OAUTH_TOKEN_URI` |  | OAuth token endpoint (only needed without issuer discovery). |
| `GITEABOT_SECURITY_OAUTH_USER_INFO_URI` |  | OAuth user-info endpoint (only needed without issuer discovery). |
| `GITEABOT_SECURITY_OAUTH_JWK_SET_URI` |  | JSON Web Key set URI (only needed without issuer discovery). |
| `GITEABOT_SECURITY_OAUTH_USER_NAME_ATTRIBUTE` | `sub` | User name attribute in the OAuth user info response. |
| `GITEABOT_SECURITY_OAUTH_REDIRECT_URI` | `{baseUrl}/login/oauth2/code/giteabot` | OAuth redirect URI registered with the provider. |
| `GITEABOT_SECURITY_OAUTH_SCOPE` | `openid,profile` | OAuth scopes to request. |
| `GITEABOT_SECURITY_OAUTH_DEBUG_LOGGING_ENABLED` | `false` | Enable redacted OAuth request/response logging at DEBUG. |
| `GITEABOT_SECURITY_OAUTH_DEBUG_LOGGING_MAX_BODY_LENGTH` | `16384` | Maximum body length for OAuth debug logs. |
| `GITEABOT_SECURITY_AUTO_LOGIN_ENABLED` | `false` | Enable auto-login (e2e/preview environments only — never enable in production). |
| `GITEABOT_SECURITY_AUTO_LOGIN_USERNAME` | `admin` | Auto-login username. |
| `GITEABOT_SECURITY_AUTO_LOGIN_PASSWORD` | `admin` | Auto-login password. |

### Public URL (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_PUBLIC_URL` | `http://localhost:8080` | Public base URL of the bot, used for example as a callback URL for CI deployment workflows. |

### Database and Migrations (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_DRIVER` | `org.h2.Driver` | JDBC driver class. The Docker profile sets PostgreSQL automatically. |
| `JPA_DDL_AUTO` | `validate` | Hibernate DDL auto mode. |
| `FLYWAY_ENABLED` | `true` | Enable Flyway database migrations. |

### HTTP Client Timeouts (Optional)

These timeouts apply to all outbound HTTP clients, including AI providers and Git host APIs:

| Variable | Default | Description |
|----------|---------|-------------|
| `HTTP_CLIENT_CONNECT_TIMEOUT` | `10s` | Outbound HTTP connection timeout. |
| `HTTP_CLIENT_READ_TIMEOUT` | `120s` | Outbound HTTP read timeout. |

### Audit Retention (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `AUDIT_RETENTION` | `P90D` | ISO-8601 duration after which audit events are deleted. |
| `AUDIT_GC_CRON` | `0 23 4 * * *` | Cron expression for the audit retention garbage collector. |

## Configuration via Web UI

All AI provider and Git configuration is managed through the web interface:

1. **AI Integrations**: Create connections to AI providers (Anthropic, OpenAI, Ollama, llama.cpp)
   - Provider-specific default API URLs are pre-filled
   - Suggested models are available via dropdown
   - API keys are encrypted at rest

2. **Git Integrations**: Create connections to Git hosting platforms
   - **Gitea**: Self-hosted Gitea instances — see [Gitea Setup](GITEA_SETUP.md)
   - **GitHub**: github.com or GitHub Enterprise Server — see [GitHub Setup](GITHUB_SETUP.md)
   - **GitLab**: gitlab.com or self-managed GitLab — see [GitLab Setup](GITLAB_SETUP.md)
   - **Bitbucket Cloud**: bitbucket.org — see [Bitbucket Setup](BITBUCKET_SETUP.md)
   - Tokens are encrypted at rest

3. **Bots**: Create bots that combine an AI integration with a Git integration
   - Each bot gets a unique webhook URL
   - Select a system prompt entry per bot
   - Enable/disable coding-agent issue implementation per coding bot
   - Choose **Writer bot** when you want issue drafting instead of code changes

## System Prompts

System prompts are stored in the database and managed in **System settings → System prompts**. On migration, Flyway creates a default prompt entry from the bundled prompt files and assigns it to all existing bots. The migration removes the legacy per-bot prompt column; copy any custom per-bot prompt text before upgrading if you need to recreate it as a reusable system prompt entry.

When upgrading to the version that adds .NET validation support, Flyway overwrites the **Default** coding-agent system prompt so it includes `.sln` / `.csproj` detection and `dotnet build` / `dotnet test` guidance. Back up changes made directly to the **Default** prompt before upgrading, or clone it to a custom prompt entry and assign that entry to your bots.

The `prompts/` directory is still copied into the image as the source for default prompt content:

```yaml
volumes:
  - ./prompts:/app/prompts:ro
```

After upgrade, edit or clone prompt entries in the UI instead of editing bot prompt text directly.

## Dockerfile Details

The Dockerfile uses a **multi-stage build**:

1. **Build stage** (`eclipse-temurin:21-jdk-alpine`): Compiles the application with Maven
2. **Runtime stage** (`eclipse-temurin:21-jre-noble`): Runs the JAR as a non-root user

Key features:
- Maven dependency layer caching for fast rebuilds
- Non-root `appuser` for security
- Health check via `/actuator/health` (interval: 30s, start period: 30s)
- JVM tuning: `UseContainerSupport` and `MaxRAMPercentage=75.0`

### Architectures

The published image is a **multi-arch manifest** covering `linux/amd64` and
`linux/arm64`, so `docker pull` / `docker compose up` selects the right variant
automatically — including on Apple Silicon, AWS Graviton and Raspberry Pi 4/5
(64-bit OS).

Both variants ship the same agent toolchain (JDK/Maven, Node.js 22, Python,
Go, Rust, .NET SDK, C/C++, Ruby, k6, Playwright and Cypress). Two
architecture-specific details are worth knowing:

- **k6** is installed from the Grafana APT repository on `amd64` and from the
  official release tarball on `arm64`, because that repository publishes no
  `arm64` packages. The tarball version is pinned by the `K6_VERSION` build
  argument.
- **Cypress on `arm64`** can only drive its bundled Electron browser; Cypress
  ships no Arm builds of Chrome or Firefox. Playwright runs Chromium on both
  architectures, so E2E workflows that need a real Chromium should use the
  Playwright runner on Arm hosts.

Building the image yourself for a single architecture needs no extra flags.
To build the full manifest locally:

```bash
docker buildx build --platform linux/amd64,linux/arm64 -t ai-git-bot:local .
```

Cross-building `arm64` on an `amd64` host requires QEMU
(`docker run --privileged --rm tonistiigi/binfmt --install arm64`) and is
**slow** — this image compiles and downloads a large toolchain. Prefer building
each architecture on a native host.

## Database

- PostgreSQL 17 (Alpine) is included in the Docker Compose setup
- Data is persisted in the `pgdata` Docker volume
- Schema is automatically managed by Hibernate (`ddl-auto=update`)
- The database stores:
  - Admin users
  - AI integrations (with encrypted API keys)
  - Git integrations (with encrypted tokens)
  - Bots
  - Review sessions and conversation history

## Health Check

The bot exposes a health endpoint:

```
GET http://<bot-host>:8080/actuator/health
```

Use this for load balancer health checks or container orchestration.

## Metrics (Prometheus)

The bot exposes a Prometheus-compatible metrics endpoint on the standard Spring Boot Actuator path:

```
GET http://<bot-host>:8080/actuator/prometheus
```

The endpoint is **disabled by default** for operational security. To enable it, set:

```yaml
environment:
  PROMETHEUS_ENABLED: "true"
```

Included metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `giteabot_reviews` | gauge | Total completed reviews |
| `giteabot_findings` | gauge | Total findings posted (v1: one per completed review) |
| `giteabot_ai_usage_input_tokens{integration}` | gauge | Total input tokens per AI integration |
| `giteabot_ai_usage_output_tokens{integration}` | gauge | Total output tokens per AI integration |
| `giteabot_ai_errors` | gauge | Total AI provider errors |
| `giteabot_audit_tool_calls` | gauge | Total tool calls recorded in the audit trail |
| `prworkflow.run_total` | counter | PR workflow runs by workflow and status |
| `prworkflow.run_duration_seconds` | timer | PR workflow run durations |
| `agent.tool_calls_total{provider}` | counter | Individual tool-call invocations per provider |
| `agent.tool_call.mode_total{mode,provider}` | counter | Tool-call mode distribution |
| `agent.tool_call.latency_seconds` | timer | Tool-call latencies |
| `agent.tool_call.parse_failures_total` | counter | Tool-call parse failures |
| `agent.critic.outcome_total{outcome}` | counter | Critic/reflection outcomes |

Labels are deliberately low-cardinality: `integration`, `provider`, `mode`, and `outcome`. Repository names, PR numbers, session IDs, branch names, and error messages are never used as labels.

### Prometheus scrape configuration example

```yaml
scrape_configs:
  - job_name: 'ai-git-bot'
    static_configs:
      - targets: ['bot-host:8080']
    metrics_path: '/actuator/prometheus'
    scrape_interval: 30s
```

## Stopping

```bash
docker compose down        # Stop containers (data preserved in pgdata volume)
docker compose down -v     # Stop and remove volumes (deletes all data)
```
