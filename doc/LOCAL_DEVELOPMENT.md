# Local Development

This guide covers building, testing, and running AI-Git-Bot locally for development.

## Prerequisites

- **Java 21** or later
- **Maven 3.9+**
- **Docker** and **Docker Compose** (for the local Gitea instance)
- **OpenSSH client tools** (`ssh`, `ssh-keygen`, and `ssh-keyscan`) on `PATH`
  when using SSH Git transport in native or executable-JAR runs. The official
  Docker image includes them through `openssh-client`.

## Build & Test

```bash
mvn clean package       # Compile and package (includes tests)
mvn test                # Run tests only
mvn clean package -DskipTests   # Package without running tests
```

## Running Natively

```bash
mvn spring-boot:run
```

This starts the bot on `http://localhost:8080` using the default profile:
- **H2 in-memory database** (no external database needed)
- All configuration done via web UI

### Initial Setup

1. Open `http://localhost:8080` in your browser
2. Create an admin account on the setup page
3. Log in and configure integrations via the web UI

## Local Gitea Instance

A pre-configured Gitea instance is provided under `systemtest/` for local testing.

### Starting Gitea

```bash
docker compose -f systemtest/docker-compose-local-gitea.yml up -d
```

This starts **Gitea** on `http://localhost:3000` with:
- Pre-configured test data (users, repos, PRs) in `systemtest/gitea/`
- Webhook delivery to the host enabled (`GITEA__webhook__ALLOWED_HOST_LIST=*`)
- `host.docker.internal` mapped to the host network

### Pre-configured Users

The local Gitea instance comes with existing test data in `systemtest/gitea/`. Log in to explore the existing setup or create new users as needed.

### Configuring the Webhook

1. In the bot's web UI, create:
   - An **AI Integration** (e.g., Anthropic with your API key)
   - A **Git Integration** pointing to `http://localhost:3000` with a Gitea token
   - A **Bot** using both integrations
2. Copy the bot's **Webhook URL**
3. In Gitea (`http://localhost:3000`), navigate to a repository's **Settings → Webhooks → Add Webhook → Gitea**
4. Set the **Target URL** to the webhook URL (use `http://host.docker.internal:8080/api/webhook/...` to reach the host from Docker)
5. Select events: **Pull Request**, **Issue Comment**, **Pull Request Comment** (and **Issues** if you want to test coding/writer agents)
6. Save the webhook

The `host.docker.internal` hostname allows the Gitea Docker container to reach the bot running natively on your host machine.

### Stopping Gitea

```bash
docker compose -f systemtest/docker-compose-local-gitea.yml down
```

The test data in `systemtest/gitea/` is persisted on disk and survives restarts.

## Test Profile

Tests use the `test` profile with `src/test/resources/application-test.properties`:
- H2 in-memory database
- Mock URLs for external services

Run tests with:

```bash
mvn test
```

## Project Structure

```
src/main/java/org/remus/giteabot/
├── admin/          # Admin UI, bot/integration entities and factories
├── ai/             # AI provider abstraction + provider implementations
├── agent/          # Coding-agent orchestration, writer agent, workspace/tool execution
├── review/         # Code-review orchestration and PR context enrichment
├── repository/     # Provider-agnostic Git API abstraction
├── webhook/        # Unified webhook controller
├── gitea/          # Gitea webhook translation + API client
├── github/         # GitHub webhook translation + API client
├── gitlab/         # GitLab webhook translation + API client
├── bitbucket/      # Bitbucket webhook translation + API client
├── session/        # Review session persistence
├── systemsettings/ # Reusable system prompt entries and settings UI
└── config/         # Spring configuration and properties

prompts/            # Bundled prompt seed files copied into the container image
├── default.md      # Default review prompt content
├── agent.md        # Default coding-agent prompt content
└── local-llm.md    # Review prompt content optimized for local models
```

## Useful Endpoints

| Endpoint | Description |
|----------|-------------|
| `POST /api/webhook/{secret}` | Unified webhook receiver for all supported Git providers |
| `GET /dashboard` | Admin dashboard |
| `GET /bots` | Bot management |
| `GET /ai-integrations` | AI integration management |
| `GET /git-integrations` | Git integration management |
| `GET /system-settings` | Reusable system prompt entry management |
| `GET /actuator/health` | Health check |
| `GET /actuator/info` | Application info |

## Adding a New AI Provider

To add support for a new AI provider:

1. Create a new package under `org.remus.giteabot.ai.{provider}/`
2. Implement `AiProviderMetadata`:
   ```java
   @Component
   public class NewProviderMetadata implements AiProviderMetadata {
       public static final String PROVIDER_TYPE = "newprovider";
       public static final String DEFAULT_API_URL = "https://api.newprovider.com";
       public static final List<String> SUGGESTED_MODELS = List.of("model-a", "model-b");
       
       // Implement all interface methods...
   }
   ```
3. Extend `AbstractAiClient`:
   ```java
   public class NewProviderClient extends AbstractAiClient {
       // Implement sendReviewRequest() and sendChatRequest()
   }
   ```
4. The provider will automatically be discovered by `AiProviderRegistry` via Spring's component scanning

## Adding a New Git Provider

To add support for a new Git hosting platform:

1. Add the new type to `RepositoryType` enum in `org.remus.giteabot.repository`
2. Create a new package under `org.remus.giteabot.{provider}/`
3. Implement `RepositoryApiClient`:
   ```java
   public class NewProviderApiClient implements RepositoryApiClient {
       // Implement all interface methods...
   }
   ```
4. Implement `RepositoryProviderMetadata`:
   ```java
   @Component
   public class NewProviderMetadata implements RepositoryProviderMetadata {
       @Override
       public RepositoryType getProviderType() {
           return RepositoryType.NEW_PROVIDER;
       }
       
       @Override
       public String getDefaultWebUrl() {
           return "https://newprovider.example.com";
       }
       
       // Implement all interface methods...
   }
   ```
5. Create a webhook controller to handle provider-specific payload format
6. The provider will automatically be discovered by `RepositoryProviderRegistry`

