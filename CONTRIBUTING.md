# Contributing to AI-Git-Bot

We welcome contributions to AI-Git-Bot! This document provides guidelines and instructions for contributing.

## Getting Started

### Prerequisites

- Java 21 or later
- Maven 3.9+
- A Git hosting platform for integration testing (Gitea or GitHub)
- An AI provider API key (Anthropic, OpenAI) or local setup (Ollama, llama.cpp)

### Setting Up the Development Environment

1. Fork and clone the repository:

   ```bash
   git clone https://github.com/<your-fork>/ai-git-bot.git
   cd ai-git-bot
   ```

2. Build the project:

   ```bash
   mvn clean package
   ```

3. Run the tests:

   ```bash
   mvn test
   ```

## How to Contribute

### Reporting Bugs

- Open an issue describing the bug, including steps to reproduce
- Include relevant logs or error messages
- Mention the version or commit hash you are using

### Suggesting Features

- Open an issue describing the feature and its use case
- Explain why the feature would be valuable

### Submitting Pull Requests

1. Create a feature branch from `develop`:

   ```bash
   git checkout -b feature/your-feature develop
   ```

2. Make your changes following the coding conventions below

3. Add or update tests for your changes

4. Ensure all tests pass:

   ```bash
   mvn clean test
   ```

5. Commit your changes with clear, descriptive commit messages:

   ```bash
   git commit -m "feat: add support for custom review templates"
   ```

6. Push to your fork and open a pull request against the `develop` branch

### Pull Request Description

Every pull request must include a description with the following four sections:

1. **What has been done?** — A concise summary of the changes (new feature, bug fix, refactoring, etc.).
2. **Why?** — The motivation behind the change. What problem does it solve or what value does it add?
3. **What has been tested?** — Which tests were added or updated, and what manual testing (if any) was performed.
4. **How AI was used?** — If AI tools (e.g., code generation, suggestions, reviews) contributed to this work, describe how they were used.

### Commit Messages

Follow the [Conventional Commits](https://www.conventionalcommits.org/) format:

- `feat:` — new feature
- `fix:` — bug fix
- `docs:` — documentation changes
- `test:` — adding or updating tests
- `refactor:` — code refactoring without behavior changes
- `chore:` — maintenance tasks

## Coding Conventions

### Java Style

- Follow standard Java naming conventions
- Use Lombok annotations (`@Data`, `@Slf4j`, `@Builder`) where already used in the codebase
- Write Javadoc for public APIs

### Project Structure

See [Architecture Documentation](doc/ARCHITECTURE.md) for detailed component descriptions and request flows.

```
src/main/java/org/remus/giteabot/
├── admin/           # Admin controllers, services, entities (Bots, Integrations)
├── agent/           # Issue implementation agent (critic, issueimpl, loop, model, session, shared, tools, validation, writerimpl)
├── ai/              # AI provider abstraction and implementations (anthropic, google, llamacpp, ollama, openai)
├── ausage/          # AI usage tracking
├── audit/           # Audit trail
├── azuredevops/     # Azure DevOps integration
├── bitbucket/       # Bitbucket integration
├── config/          # Configuration classes and prompt service
├── eventhook/       # Event hook handling
├── gitea/           # Gitea webhook controller, API client, and models
├── github/          # GitHub webhook controller, API client, and models
├── gitlab/          # GitLab integration
├── issueworkflow/   # Issue workflows (coding, writer)
├── mcp/             # MCP server handling
├── metrics/         # Metrics and monitoring
├── prworkflow/      # PR workflows (agentreview, config, deployment, e2e, i18n, readmesync, review, unittest)
├── repository/      # Repository provider abstraction and metadata
├── review/          # Code review orchestration and enrichment
├── session/         # Session management and persistence
├── systemsettings/  # System-wide settings
├── util/            # Shared utilities
└── webhook/         # Webhook handling
```

### Testing

- Unit tests use `@ExtendWith(MockitoExtension.class)` with `@Mock` and `@InjectMocks`
- Web layer tests use `@WebMvcTest` with `@MockitoBean`
- Integration tests use `@SpringBootTest` with embedded HTTP servers
- Use `@ActiveProfiles("test")` with `application-test.properties` for test configuration
- Aim for meaningful test coverage on new code

### Database Migrations

Flyway migrations live in `src/main/resources/db/migration/h2` and
`src/main/resources/db/migration/postgresql`. Every change needs a script in
both directories with the same version.

- Name new scripts with a UTC timestamp version:
  `V<yyyy>_<MM>_<dd>_<HHmm>__<lower_snake_description>.sql`,
  e.g. `V2026_10_20_1430__add_bot_labels.sql`. Parallel pull requests then no
  longer conflict over the next sequence number.
- Flyway runs with `out-of-order=true`, so a script may be applied after one
  with a newer timestamp. Do not rely on unmerged migrations from other pull
  requests; if your script depends on another one, give it a later timestamp.
- Never rename or edit a migration that has been merged. The existing
  sequential scripts (`V1` to `V58`) are recorded in the schema history of
  deployed databases and must keep their names and contents.
- `FlywayMigrationNamingTest` enforces the naming scheme and that both vendor
  directories contain the same versions.

### Prompt Files

When adding or modifying prompt definitions:

- Place markdown files in the `prompts/` directory
- Document the prompt's intended use case as a comment at the top of the file
- Add the corresponding property in `application.properties`

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## Questions?

Open an issue if you have questions about contributing. We're happy to help!
