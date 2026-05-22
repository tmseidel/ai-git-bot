# Security Policy

## Supported versions

Security fixes are provided for the latest released version of AI-Git-Bot and the current `main` branch when applicable.

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| `main` branch | Yes, before the next release |
| Older releases | Best effort |

## Reporting a vulnerability

Please report suspected security vulnerabilities privately.

Preferred reporting path:

1. Use GitHub private vulnerability reporting for this repository if it is enabled.
2. If private vulnerability reporting is not available, open a minimal public issue asking for a private security contact. Do not include exploit details, secrets, logs with credentials, or proof-of-concept payloads in the public issue.

Please include, when possible:

- affected AI-Git-Bot version or commit hash
- deployment mode, for example Docker Compose or local development
- affected Git provider integration, if relevant
- affected AI provider integration, if relevant
- clear reproduction steps
- expected impact
- relevant logs with secrets redacted

## Scope

Security-sensitive areas include:

- storage and encryption of AI provider keys and Git platform tokens
- webhook authentication and request handling
- Git provider API access and permissions
- autonomous coding-agent workspace handling
- MCP server configuration and tool whitelisting
- prompt handling where it may expose credentials, private code, or internal metadata
- Docker image and deployment configuration

## Handling expectations

Maintainers will triage reports as soon as practical, request clarification when needed, and coordinate a fix and disclosure timeline based on severity. Please avoid public disclosure until a fix or mitigation is available.

## Security best practices for operators

- Use least-privilege Git provider tokens.
- Use separate bot accounts for automation.
- Rotate AI provider keys and Git tokens regularly.
- Configure webhook secrets or equivalent provider protections where supported.
- Keep AI-Git-Bot updated to the latest release.
- Use local LLM providers such as Ollama or llama.cpp when code must remain inside controlled infrastructure.
- Restrict MCP servers and selected tools to the minimum required capability set.
- Back up the database securely and protect the configured encryption key.

