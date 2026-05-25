# Changelog

All notable changes to AI-Git-Bot are documented in this file.

The format is inspired by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses semantic versioning where practical.

## [Unreleased]

### Added

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



