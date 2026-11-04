# Full configuration example

`docker-compose.yml` in this directory starts the bot from a configuration document in which
**every editable property of every configuration entity is set** — 16 rows, 78 fields. Use it
as the reference for the document format, and as the template for a real deployment.

```bash
docker compose up -d
# http://localhost:8080 — admin / change-this-password
```

The database is in-memory, so every start re-applies the document and discards UI changes.
The persistent (Postgres) variant is commented out at the bottom of the file.

The document itself is the entity model: one array per configuration table, field names as
the entities name them, `@ManyToOne` references as `{"name": …}`. A row the document declares
overrides the seeded row with that name field by field; anything left out keeps its stored
value.

## What each section covers

| Section | Rows | Fields |
| --- | --- | --- |
| `adminUsers` | `admin` | `username`, `password` (plaintext, hashed on import) |
| `systemPrompts` | `House style` | `name` + all ten role prompts (`review`, `reviewAgent`, `issueAgent`, `writerAgent`, `e2ePlanner`, `e2eAuthor`, `e2eRunner`, `unitTestAuthor`, `readmeSync`, `i18nCoverage`) |
| `botToolConfigurations` | `Read-only set` | `name`, `selectedTools[].toolName`, `.toolKind` |
| `mcpConfigurations` | `Docs index` | `name`, `jsonContent`, `selectedTools[].qualifiedName/.serverName/.toolName/.title/.description` |
| `deploymentTargets` | `Preview (static)`, `Staging (webhook)` | `name`, `strategyType`, `configJson`, `previewUrlTemplate`, `timeoutSeconds` |
| `aiIntegrations` | `DeepSeek`, `OpenRouter (EU, no data collection)`, `Anthropic (direct)`, `Ollama (local)` | `name`, `providerType`, `apiUrl`, `apiKey`, `apiVersion`, `model`, `maxTokens`, `useLegacyToolCalling`, `promptCachingEnabled`, `contextWindowTokens`, `parallelWorkerLimit`, `modelFlavor`, `openRouterRegion`, `openRouterDataCollection`, `openRouterZdr` |
| `gitIntegrations` | `Gitea (HTTP)`, `Gitea (SSH)` | `name`, `providerType`, `url`, `username`, `token`, `transport`, `postReviewAction`, `sshCleanupVerified`, `sshPrivateKey`, `sshKnownHosts` |
| `workflowConfigurations` | `PR workflows`, `Issue workflows` | `name`, `kind`, `selectedWorkflows[].workflowKey`, `.params[].name/.value` |
| `bots` | `demo-bot` | `name`, `webhookSecret`, `webhookSigningSecret`, `userWhitelist`, `enabled`, `branchFilter`, `runOnPrCreation`, `runOnPrUpdate`, `runOnIssueCreation`, plus the seven references (`aiIntegration`, `gitIntegration`, `systemPrompt`, `toolConfiguration`, `mcpConfiguration`, `workflowConfiguration`, `issueWorkflowConfiguration`, `deploymentTarget`) |
| `eventHookEndpoints` | `Ops webhook` | `name`, `url`, `secret`, `authorizationHeader`, `customHeaders`, `eventTypes`, `bot` (reference, optional — omit for every bot), `repoOwner`, `repoName`, `skipTlsVerify`, `enabled` |

Values that need explaining:

- **System prompts are all-or-nothing per row.** All ten role prompts are `NOT NULL`, so a
  custom prompt must set all ten; a document that sets only one fails with
  `Review-Agent System-Prompt is required`.
- **`adminUsers` needs one usable administrator.** At least one entry must carry both
  `username` and `password`; a document without one is refused and imports nothing, because the
  import (which only runs while no admin user exists) would otherwise re-apply on every start
  and leave `/setup` claimable. Exported documents never carry a password — add one before
  re-importing.
- **`selectedTools` is a full replacement.** The default configuration contains every built-in
  tool except `execute-code`, which is opt-in: a tool configuration exists to take tools away,
  or to give one bot code execution.
- **`gitIntegrations`.** HTTP needs a `token`; SSH is Gitea-only and needs a `token` for the API
  as well as `sshPrivateKey` + `sshKnownHosts`. Both secrets are stored encrypted. When you
  switch an integration away from SSH, its key and known-hosts are dropped.
- **`aiIntegrations`.** `providerType` is the enum constant, upper case: `OPENAI`, `ANTHROPIC`,
  `GOOGLE`, `OLLAMA`, `LLAMACPP`, `OPENROUTER` — the same value the `provider_type` column holds
  and the admin UI's provider select posts, so a lower-case `openai` fails the import. The
  `openRouter*` fields (`region`: `GLOBAL` | `EU` | `US`, `dataCollection`: `DENY` | `ALLOW`,
  `zdr`) are read by the `OPENROUTER` provider only; the same applies to `apiVersion`, which
  is the Anthropic `anthropic-version` header (`2023-06-01` when unset). `modelFlavor` is
  `standard` or `no_reasoning` for the OpenAI-compatible providers; `apiKey` is omitted for
  `OLLAMA` / `LLAMACPP`.
- **`workflowConfigurations[].params`** — keys per workflow: `review` takes
  `maxDiffCharsPerChunk`, `maxDiffChunks`, `retryTruncatedChunkChars`, `excludedFilePatterns`;
  `e2e-test` takes `framework`, `maxRetries`, `maxTestCases`, `suiteLifecycle`,
  `promotionThresholdPercent`; `readme-sync` takes `includedFilePatterns`, `maxToolRounds`,
  `suiteLifecycle`; `issue-triage` takes `systemPrompt`, `assignees`. Unknown keys are rejected.
- **`eventHookEndpoints.eventTypes`** is a comma-separated list of enum *names*
  (`PR_WORKFLOW_STARTED`, …), not the dotted wire values. `customHeaders` is a JSON object in a
  string. `repoOwner`/`repoName` narrow the endpoint to one repository.
- **`bots.webhookSecret`** is the public path segment of the webhook URL; `webhookSigningSecret`
  is the HMAC key Gitea signs with. Leave both stable across restarts. `userWhitelist` accepts
  commas or newlines; empty means "everyone the Git integration can see". `branchFilter` is a
  glob (`main|release/*`, empty = all branches).

## What a document cannot set

- `defaultEntry` — the seed rows keep it; a document can never move the default flag.
- `id`, `lockVersion`, timestamps and the statistics / last-error fields — stripped on import.
- **MCP deployment targets.** Their `configJson` names an MCP configuration *by id* and must
  reference whitelisted tool names, neither of which is portable between instances. Configure
  those in the admin UI.
- **An admin password in an export.** `GET /config/export` never contains a password hash, so a
  fresh instance built from an export asks for the account at `/setup` first.

## Verified

- Import of this file: `Config import applied: 16 rows created`.
- Field-by-field comparison against `GET /config/export`: every field of every row matches; the
  only keys the export adds are `defaultEntry` and the `openRouter*` defaults.
- Export → import into a fresh instance with a **different** `APP_ENCRYPTION_KEY` reproduces
  every row and field (secrets included), so an exported document is portable.
