# Changelog

All notable changes to AI-Git-Bot are documented in this file.

The format is inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses semantic versioning where practical.

## [Unreleased]

### Added

- Added the **Usage** page (`/usage`) that audits all AI provider interactions: a collapsible *AI usage* section (paginated/sortable table with Timestamp, AI-Integration, Session-ID, Input/Output tokens, clearable via a *Clear all* button) and a collapsible *Errors* section (paginated/sortable table with expandable stack traces and JSON export), both filterable by timespan. Backed by the new `ai_usage_log` / `ai_error_log` tables (Flyway `V26`). All AI clients now report token usage and failed interactions (e.g. HTTP `401` responses) to the audit log, and the dashboard shows an **AI Errors** card (last 7 days, red when errors occurred) plus token totals from the audit log so AI errors are no longer invisible.
- Added the **Unit-Test Author** PR workflow (key `unit-test-author`, category `TESTING`, opt-in per bot). It generates white-box unit tests for the files touched by a PR diff, runs them with the project's *own* test runner via the coding agent's existing execution mechanism (`ToolExecutionService`), commits the generated tests onto the PR branch and posts a result + coverage comment. Supports `maven`, `gradle`, `npm`, `pytest`, `go`, `cargo`, `dotnet`, `bundle`, `make`, `gcc` and `g++` (auto-detected by default), an operator-editable author system prompt (`system_prompts.unit_test_author_system_prompt`, Flyway `V23`), the `unit_test_suites` / `unit_test_cases` tables (Flyway `V24`), and the `@bot generate-tests` / `@bot rerun-unit-tests` slash commands. Unlike the `e2e-test` workflow it needs no deployment target or browser. See [`doc/PR_WORKFLOWS_UNIT_TEST.md`](doc/PR_WORKFLOWS_UNIT_TEST.md).
- Added per-bot **User Whitelist** (optional textarea on the bot edit form, backed by the new `bots.user_whitelist` column) that restricts which Git usernames may trigger AI-spending interactions — PR opens/syncs, `@`-mentions, inline review comments, review submissions, agent issue assignments and follow-up comments. Blank ⇒ everyone allowed (historical default); non-blank ⇒ strict allow-list enforced in `BotWebhookService#isCallerAllowed`. Recommended for bots installed on public repositories so unknown users cannot burn the bot's AI tokens.
- Added Dark Theme support with a toggle switch in the navbar.
- Added repository discovery metadata for search engines, LLMs, autonomous agents, and RAG systems.
- Added `llms.txt` as a concise LLM entry point.
- Added `llms-full.txt` as an expanded single-file project reference for larger context windows.
- Added `SECURITY.md` with vulnerability reporting guidance and operator security recommendations.
- Added `CITATION.cff` for GitHub citation metadata and software catalog indexing.
- Added `codemeta.json` with machine-readable software metadata for catalogs and crawlers.
- Added Maven project metadata for repository URL, SCM, license, issue tracker, and maintainer attribution.
- Added README discovery keywords and references to LLM/security/changelog metadata.

## [1.6.0]

### Notes

- Historical release notes before the introduction of this changelog were tracked through repository commits, pull requests, and releases.
- See the README and documentation in `doc/` for the current feature set, including reviewer-triggered PR reviews, coding-agent workflows, writer-agent workflows, MCP handling, and local LLM support.



