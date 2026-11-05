# Local Development

This guide covers building, testing, and running AI-Git-Bot locally for development.

## Prerequisites

- **Java 21** or later
- **Maven 3.9+**
- **Docker** and **Docker Compose** (for the local Gitea instance)
- **OpenSSH client tools** (`ssh`, `ssh-keygen`, and `ssh-keyscan`) on `PATH`
  when using SSH Git transport in native or executable-JAR runs. The official
  Docker image includes them through `openssh-client`.
- **Git 2.43 or later**

## Developing on Windows

Running the bot natively on Windows is not supported: it shells out to `git`, `ssh` and
other POSIX tooling that assumes a Linux environment. Use WSL instead and install the
[prerequisites](#prerequisites) inside your WSL distribution as well as on your Windows host. See
[IntelliJ IDEA's WSL guide](https://www.jetbrains.com/help/idea/how-to-use-wsl-development-environment-in-product.html)
for IDE setup. To only run the bot, you can use the Docker image.

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

### The `execute-code` Sandbox

The `execute-code` tool runs a model-written Python program. The shipped image
provisions a pool of throwaway accounts for it — one per execution, so the program
reads neither `appuser`'s files nor the JVM's start-time environment. A native
`mvn spring-boot:run` has no such pool: by default the runtime points at the image's
pool file (`agent.code-execution.sandbox-slots` defaults to
`/etc/execute-code/sandbox-slots`), which a dev machine does not have, so a call to
`execute-code` **fails closed** — no program runs — rather than quietly running it as
you. Set the pool up (Option 1), or opt out explicitly (Option 2).

**Option 1 — provision the pool (Linux or WSL).** The image's own script provisions a
host the same way it provisions the image:

```bash
sudo ./docker/install-execute-code-sandbox.sh "$(id -un)"
```

It needs `sudo` and the Linux account tools (`useradd`, `groupadd`, `visudo`). It
creates the accounts `execute-code-10001` … `execute-code-10016`, writes the pool file
`/etc/execute-code/sandbox-slots`, and installs one sudo rule
`<you> ALL=(execute-code-10001,…) NOPASSWD: ALL` — no `(root)`, no `(ALL)` runas. Size
the pool with `EXECUTE_CODE_FIRST_SLOT` (default `10001`) and
`EXECUTE_CODE_SLOT_COUNT` (default `16`), and re-run the script after changing them.

The script adds you to every slot's group, and the JVM hands the throwaway workspace
over with `chgrp` — a membership check — so **start the bot in a fresh session** after
provisioning (a new login, or `sudo -u "$(id -un)"`): a shell that was already open
keeps the groups it started with.

Check the pieces:

```bash
cat /etc/execute-code/sandbox-slots              # name uid gid, one line per slot
sudo -n -u execute-code-10001 -- id              # you may become a slot, no password
sudo -l -U "$(id -un)" | grep execute-code       # the NOPASSWD rule is present
```

The launching session must carry the slot groups, and so must the JVM itself — the
`Groups:` line, not just the login, is what `chgrp` uses:

```bash
id -nG | tr ' ' '\n' | grep -c execute-code      # expect 16, in the session that starts the bot
grep -E '^(Uid|Groups):' /proc/<jvm-pid>/status  # the running JVM's own supplementary gids
```

If `execute-code` still fails with `sandbox failure: /tmp/execute-code-…: Operation
not permitted` (and teardown logs `find: …: Permission denied`), the JVM is not a
member of the slot's group: it was started from a shell older than the `usermod`, so it
cannot `chgrp` the workspace into the slot's group. The install script already added
you (`getent group 10001` will show it) — the process just did not inherit it. Open a
fresh session and start the bot there.

The pool is one-per-service — do not point two JVMs at the same file, they clear each
other's slots on start.

**Option 2 — run the program as yourself (opt out).** Blank the pool path and the
program runs as your own user:

```bash
AGENT_CODE_EXECUTION_SANDBOX_SLOTS= mvn spring-boot:run
```

(or set `agent.code-execution.sandbox-slots=` empty). Convenient on a dev machine you
already trust with the repository, but it is **not** the deployment's isolation: the
program can then read your files, your `$HOME` and the JVM's environment. Never carry
an empty pool into a shared or production deployment. On macOS or native Windows the
pool script cannot run, so this is the option there.

Either way the tool only reaches a run once the bot's tool configuration selects
`execute-code` — it is not part of the Default configuration (see
[BOT_TOOL_CONFIGURATIONS.md](BOT_TOOL_CONFIGURATIONS.md)) — and there is no off switch
beyond leaving it unselected. The production story — the pool of accounts, the
`no-new-privileges` interaction — is in
[DEPLOYMENT.md](DEPLOYMENT.md#dockerfile-details).

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
├── azuredevops/    # Azure DevOps webhook translation + API client
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

1. Add the new type and its display name to the `AiProviderType` enum in `org.remus.giteabot.ai`
2. Create a new package under `org.remus.giteabot.ai.{provider}/`
3. Implement `AiProviderMetadata`:
   ```java
   @Component
   public class NewProviderMetadata implements AiProviderMetadata {
       public static final AiProviderType PROVIDER_TYPE = AiProviderType.NEW_PROVIDER;
       public static final String DEFAULT_API_URL = "https://api.newprovider.com";
       public static final List<String> SUGGESTED_MODELS = List.of("model-a", "model-b");
       
       // Implement all interface methods...
   }
   ```
4. Extend `AbstractAiClient`:
   ```java
   public class NewProviderClient extends AbstractAiClient {
       // Implement sendReviewRequest() and sendChatRequest()
   }
   ```
5. The provider will automatically be discovered by `AiProviderRegistry` via Spring's component scanning

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

