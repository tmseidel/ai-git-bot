# Migration Guide: 1.0.0 → 1.1.0

This guide helps you upgrade AI-Git-Bot from version 1.0.0 to 1.1.0, which introduces a web-based management UI with multi-bot support and the Gateway architecture.

## ⚠️ Breaking Changes

This is a **breaking change**. The following environment variables are **no longer used** and have been removed:

- `GITEA_URL`, `GITEA_TOKEN`
- `AI_PROVIDER`, `AI_MODEL`, `AI_MAX_TOKENS`, `AI_MAX_DIFF_CHARS_PER_CHUNK`, `AI_MAX_DIFF_CHUNKS`, `AI_RETRY_TRUNCATED_CHUNK_CHARS`
- `AI_ANTHROPIC_API_URL`, `AI_ANTHROPIC_API_KEY`, `AI_ANTHROPIC_API_VERSION`
- `AI_OPENAI_API_URL`, `AI_OPENAI_API_KEY`
- `AI_OLLAMA_API_URL`
- `AI_LLAMACPP_API_URL`
- `BOT_USERNAME`

**All AI and Gitea configuration is now managed through the web UI** and stored in the database.

The legacy `/api/webhook` endpoint (without a path variable) has been **removed**. All webhook traffic must use per-bot webhook URLs: `/api/webhook/{secret}`.

## What's New in 1.1.0

- **Web Dashboard**: Manage bots, AI integrations, and Git integrations through a browser UI
- **Multi-Bot Support**: Create and configure multiple bots, each with their own webhook URL
- **AI Integration Management**: Configure multiple AI providers (Anthropic, OpenAI, Ollama, llama.cpp) via UI
- **Git Integration Management**: Configure Git providers (Gitea, GitHub, GitHub Enterprise) via UI
- **Encrypted Secrets**: API keys and tokens are encrypted at rest when `APP_ENCRYPTION_KEY` is configured
- **Admin Authentication**: Secure the management UI with username/password authentication
- **Per-Bot Statistics**: Track webhook calls, AI token usage, and errors per bot

## Migration Steps

### 1. Set an Encryption Key (Strongly Recommended)

The new version can encrypt sensitive data (API keys, tokens) in the database. To enable encryption, set a persistent encryption key:

```yaml
# docker-compose.yml
services:
  bot:
    environment:
      APP_ENCRYPTION_KEY: "your-secure-random-key-here"
```

> **Important:** If you don't set this key, credentials are stored in plain text. Generate a strong, stable key, e.g.: `openssl rand -base64 32`

### 2. Update Your Docker Compose

Remove all AI and Gitea environment variables. Only database, encryption, and general settings remain:

```yaml
version: '3.8'
services:
  bot:
    image: tmseidel/ai-gitea-bot:1.1.0
    environment:
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: giteabot
      DATABASE_PASSWORD: change-me
      APP_ENCRYPTION_KEY: your-secure-encryption-key
    ports:
      - "8080:8080"
    depends_on:
      db:
        condition: service_healthy

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

volumes:
  pgdata:
```

### 3. Database Migration

The application automatically creates the new database tables on startup (`spring.jpa.hibernate.ddl-auto=update`). No manual database migration is needed.

New tables added:
- `admin_users` - Administrator accounts
- `ai_integrations` - AI provider configurations
- `git_integrations` - Git provider configurations
- `bots` - Bot configurations with webhook URLs and statistics

### 4. Initial Setup

After upgrading, visit `http://your-server:8080/setup` to create your administrator account. This is a one-time setup step.

### 5. Configure Bots via UI (Required)

After logging in, you **must** recreate your configuration through the UI:

1. **Create AI Integrations** matching your previous provider settings (API URL, API key, model, etc.)
2. **Create Git Integrations** matching your previous Gitea configuration (URL, token)
3. **Create Bots** that combine an AI + Git integration with a unique webhook URL

### 6. Update Webhooks in Git Providers

Since the legacy `/api/webhook` endpoint has been removed, update all webhooks in your Git provider to use the new per-bot webhook URL. Current releases use the same webhook URL format for every supported provider:

- **All providers**: `/api/webhook/{secret}`

The webhook secret is shown in the bot management UI after creating a bot.

## New Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_ENCRYPTION_KEY` | (none) | Encryption key for sensitive data. Without it, credentials are stored in plain text. |

## Rollback

If you need to roll back to 1.0.0:
1. Stop the application
2. Use the 1.0.0 Docker image
3. Restore the environment variables that were previously configured
4. The new database tables will be ignored by the older version
