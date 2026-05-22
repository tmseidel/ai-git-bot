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
├── admin/        # Admin controllers, services, entities (Bots, Integrations)
├── ai/           # AI provider abstraction layer and implementations
├── config/       # Configuration classes and prompt service
├── gitea/        # Gitea webhook controller, API client, and models
├── github/       # GitHub webhook controller, API client, and models
├── repository/   # Repository provider abstraction and metadata
├── agent/        # Issue implementation agent
├── review/       # Code review orchestration
└── session/      # Session management and persistence
```

### Testing

- Unit tests use `@ExtendWith(MockitoExtension.class)` with `@Mock` and `@InjectMocks`
- Web layer tests use `@WebMvcTest` with `@MockitoBean`
- Integration tests use `@SpringBootTest` with embedded HTTP servers
- Use `@ActiveProfiles("test")` with `application-test.properties` for test configuration
- Aim for meaningful test coverage on new code

### Prompt Files

When adding or modifying prompt definitions:

- Place markdown files in the `prompts/` directory
- Document the prompt's intended use case as a comment at the top of the file
- Add the corresponding property in `application.properties`

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## Questions?

Open an issue if you have questions about contributing. We're happy to help!
