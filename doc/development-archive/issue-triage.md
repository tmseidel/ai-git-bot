# Issue Triage Workflow — Implementation Plan

> **Goal:** Add a new built-in issue-assigned workflow (`issue-triage`) that routes newly opened or bot-assigned issues to the correct assignee via an admin-editable system prompt, posts the routing reason as a comment, and performs the assignment through the provider API.

**Origin:** GitHub issue draft "Issue triage / routing workflow" (originates from #317).
**Supersedes:** none — no prior archive doc covers issue triage (checked `doc/development-archive/`, including the untracked `sandbox-approach.md` / `sandbox-review.md`; both unrelated).
**Tech Stack:** Spring Boot 4.x, Java 21, existing `IssueWorkflow` SPI, `AiClient` native/legacy tool-calling infrastructure, `RepositoryApiClient` provider hierarchy, Flyway (H2 + PostgreSQL), no new dependencies.

---

## Current state (verified against the codebase)

- The `IssueWorkflow` SPI (`issueworkflow/IssueWorkflow.java`) already covers both required triggers: `BotWebhookService.handleIssueCreated` (gated by `Bot.isRunOnIssueCreation()`, V41) synthesizes a virtual assignment to the bot and calls `IssueWorkflowOrchestrator.runAssigned` — the exact same path as a real issue-assigned event. **No new trigger plumbing is needed.**
- Workflow parameters are declarative: `WorkflowDescriptor.paramsSchema()` → `WorkflowParamsSchema`/`WorkflowParamField` (types STRING, TEXT, BOOLEAN, INTEGER, SECRET, ENUM). The shared workflow-configuration UI renders them automatically; `WorkflowSelectionService.resolveParams` applies defaults and type coercion. Param keys are compile-time-safe via a per-workflow enum implementing `WorkflowParamName` (e.g. `AgentReviewParam`).
- Native tool calling is already a first-class `AiClient` capability: `supportsNativeTools()` (per-integration, honoring the `use_legacy_tool_calling` switch via `AiClientFactory`) and `chatWithTools(...)` returning a `ChatTurn` with parsed `ToolCall`s. The default `chatWithTools` falls back to textual `chat`, so a single call site covers both modes.
- `RepositoryApiClient` has `postIssueComment` (all providers) but **no issue-assignment method** — this must be added. Implementations exist for Gitea, GitHub, GitLab, Bitbucket.
- Built-in issue workflows are thin `@Component`s delegating to services (`CodingIssueWorkflow`, `WriterIssueWorkflow`); per-bot AI/Git clients come from `admin/AiClientFactory` / `admin/GiteaClientFactory` (already used from the `issueworkflow` package — ArchUnit-clean).
- Ready-made workflow configurations are seeded by Flyway (V39 precedent, NOT EXISTS-guarded inserts, `default_entry` semantics). Next free migration version: **V42**. `src/test/resources/data.sql` must be kept in sync with new seeds (tests run with Flyway disabled).

## Terminology ruling (from the issue's quality assessment)

- The canonical clarification-bot name is **`issue_hemingway`** everywhere (default prompt, docs, tests). Never `writer-bot`.
- The workflow is a **triage/routing** workflow — it never implements the issue. Key: `issue-triage`, display name: `Issue Triage`.

---

## ADR-1: Workflow shape and SPI integration

**Status:** Proposed

**Context**
The feature must live inside the existing issue-workflow framework (no separate execution path) and run on both issue-created (bot setting permitting) and issue-assigned-to-bot events.

**Options Considered**

1. **New `IssueWorkflow` bean, logic in a dedicated triage service**
   - Pros: matches the `issue-coding`/`issue-writer` pattern; orchestrator lifecycle (`issueassignment.started/completed/failed`, bot error records) comes free; selectable in the existing UI via `IssueWorkflowRegistry` with zero controller/template work.
   - Cons: none identified.
2. **Extend an existing workflow (e.g. writer) with routing**
   - Cons: mixes triage with refinement semantics; violates "one issue, one behavior" configuration model; rejected.

**Decision**
Option 1. New package `org.remus.giteabot.issueworkflow.triage` (name shares no prefix with the protected `webhook` package — ArchUnit-safe) containing:

- `TriageIssueWorkflow` (`@Component implements IssueWorkflow`): key `issue-triage`, display name `Issue Triage`, description stating it routes the issue to an assignee and never implements it.
  - `onIssueAssigned` → delegate to `IssueTriageService.triage(bot, payload, params)`.
  - `onIssueComment` → **conscious no-op** (triage is a one-shot routing decision; follow-up conversation belongs to the coding/writer workflows). Documented in the javadoc, per the SPI contract's "decide consciously" rule.
- `IssueTriageService` (`@Service`, stateless; collaborators injected, `Bot` passed per call): prompt assembly, model call, output validation, comment + assignment execution. Stateless-single-shot differs from the session-ful coding/writer services, so the `AgentServiceFactory` per-bot creation pattern does not apply; the service injects `AiClientFactory` + `RepositoryApiClientFactory` directly (both already depended upon from feature packages).

**Self-assignment loop guard:** if the model selects the bot's own username, the assignment webhook would re-trigger the workflow indefinitely. The service treats `assignee == bot.getUsername()` (case-insensitive) as invalid output → error comment, no assignment.

## ADR-2: Workflow parameters and the allowed-assignee set

**Status:** Proposed — **contains the one scope decision the issue left open; flag for review.**

**Context**
The issue mandates exactly one parameter (`systemPrompt`, TEXT) but also requires validating that "the assignment is one of the configured/allowed names". Free-text prompts are not machine-parseable, so the allowed set must come from somewhere else.

**Options Considered**

1. **Hard-code the seven default names** (`Alice`, `Bob`, `David`, `Josh`, `claude-bot`, `issue_hemingway`, `none`) as a constant.
   - Pros: matches the acceptance criteria literally.
   - Cons: breaks the moment an admin edits account names in the prompt (the issue explicitly tells admins to review/replace them) — validation would reject every routed name.
2. **Add a second parameter `assignees` (STRING, comma-separated)** defaulting to `Alice,Bob,David,Josh,claude-bot,issue_hemingway`, with `none` as a reserved built-in value that is always allowed and never assigned.
   - Pros: validation stays machine-readable; prompt and allowed set can be edited together; both modes share one source of truth (acceptance criterion).
   - Cons: one parameter more than the issue's "required" list.

**Decision**
Option 2, with `none` handled by the workflow (never persisted, never passed to the provider). Param keys via a `TriageParam implements WorkflowParamName` enum: `systemPrompt` (TEXT, required, default = the full default prompt below) and `assignees` (STRING, required, default as above). The `systemPrompt` field description carries the mandated review warning ("review role descriptions, account names, bot names; listed accounts must exist and be assignable in the connected Git provider").

**Default prompt** — stored as `TriageIssueWorkflow.DEFAULT_SYSTEM_PROMPT`, verbatim from the issue (triage-lead role; Alice/Bob/David/Josh; bots `claude-bot` and `issue_hemingway`; `none`; the rules block). See the source issue text; do not paraphrase when implementing.

## ADR-3: Mode resolution and model interaction

**Status:** Proposed

**Context**
Two output protocols are required: native `assign_issue` tool call when tooling is available, JSON-only text otherwise. Both must enforce the same allowed values.

**Decision**

- Mode resolution: `aiClient.supportsNativeTools()` — this already folds in the per-integration `use_legacy_tool_calling` switch and provider capability, so no separate detection logic is built (satisfies "reuse existing tooling-mode infrastructure"). `ToolingMode.resolve` is agent-loop-specific and is not reused directly.
- **Native mode:** append the tool-call suffix from the issue ("IMPORTANT: ... you MUST call the assign_issue tool ...") to the configured prompt, then one `chatWithTools` round advertising exactly one `ToolDescriptor`:
  - name `assign_issue`, parameters `name` (string, enum = configured assignees + `none`) and `reason` (string), both required.
  - Valid result: exactly one tool call named `assign_issue`, `name` in the allowed set, non-blank single-line `reason`. Anything else (zero calls, multiple calls, unknown name, prose-only `ChatTurn`) is malformed output.
- **Legacy mode:** append the JSON-only suffix from the issue, plain `chat`, parse the response with Jackson (tolerate surrounding prose by extracting the first `{...}` block, consistent with `AiResponseParser`/`WriterResponseParser` practice), validate `assignment` against the same allowed set and require a non-blank single-line `reason`.
- User message: issue number, title, and body from the webhook payload (no repository checkout, no session persistence — unlike coding/writer there is no `AgentSession`).
- Token budget: small `maxTokensOverride` (routing answers are one tool call / one JSON object).

## ADR-4: Issue assignment across providers

**Status:** Proposed

**Context**
`RepositoryApiClient` has no assignment method. Comment posting (`postIssueComment`) already exists on all providers.

**Decision**
Add to `RepositoryApiClient`:

```java
default void assignIssue(String owner, String repo, Long issueNumber, String assignee) {
    throw new UnsupportedOperationException("Issue assignment not supported by this provider");
}
```

Provider implementations:

- **Gitea** (`GiteaApiClient`): `PATCH /repos/{owner}/{repo}/issues/{index}` with body `{"assignees": ["<name>"]}`.
- **GitHub** (`GitHubApiClient`): `POST /repos/{owner}/{repo}/issues/{number}/assignees` with body `{"assignees": ["<name>"]}`.
- **GitLab** (`GitLabApiClient`): two calls — `GET /users?username=<name>` to resolve the user id, then `PUT /projects/:id/issues/:iid` with `assignee_ids`. Unknown user → treat as non-assignable.
- **Bitbucket** (`BitbucketApiClient`): leave the default (unsupported) — the triage service maps `UnsupportedOperationException` to the standard error-comment path.

**Assignability failure handling:** provider 4xx (unknown user, not a collaborator, insufficient permission) or `UnsupportedOperationException` → do not assign, post the error comment ("selected assignee is not assignable"), and throw a dedicated `TriageRoutingException` so the orchestrator records a bot error and publishes `issueassignment.failed` (this is the "existing issue-workflow error handling" the issue asks failures to flow through). Malformed-model-output failures follow the same path (error comment + `TriageRoutingException`).

**Risk (Gitea silent success):** Gitea's `EditIssue` has historically ignored unknown assignee names without an error status. Implementation must verify actual behavior (client test + manual check); if silent, pre-validate against `GET /repos/{owner}/{repo}/collaborators` (or document the limitation). This verification step is part of Task 1.

**Execution order on success:** post the reason comment first, then assign (a failed assignment then still leaves the rationale visible, matching the issue's "post the assignment reason as an issue comment" being unconditional for valid output). For `none`: post the reason comment only.

## ADR-5: Seeding and discoverability

**Status:** Proposed

**Context**
The registry makes `issue-triage` selectable in the workflow-selection UI automatically. The issue additionally wants the feature "discoverable alongside existing major workflow features" with a prefilled default prompt.

**Decision**
Follow the V39 precedent: add **V42** (`h2/` + `postgresql/`, byte-identical dialect-permitting, NOT EXISTS-guarded) seeding one non-default configuration `Issue: Triage` (kind `ISSUE`, `default_entry = FALSE`) with a `workflow_selections` row for `issue-triage` and **no params rows** — `resolveParams` applies the schema defaults, so the default prompt and assignee list are prefilled without duplicating the text in SQL. Sync `src/test/resources/data.sql` accordingly. Do not enable triage on any existing configuration or bot.

---

## Task breakdown

### Task 1: `assignIssue` on `RepositoryApiClient` + providers
- Modify: `repository/RepositoryApiClient.java` (default method), `gitea/GiteaApiClient.java`, `github/GitHubApiClient.java`, `gitlab/GitLabApiClient.java`.
- Test: per-provider client tests with `MockRestServiceServer` (pattern per the repository-api-clients skill: fixed base URL, exact endpoint path, JSON body assertions). GitLab test covers the user-lookup + update sequence and the unknown-user path.
- Verify Gitea's unknown-assignee behavior; implement collaborator pre-validation if silent (see ADR-4 risk).

### Task 2: Workflow skeleton
- Create: `issueworkflow/triage/TriageParam.java` (enum implementing `WorkflowParamName`), `issueworkflow/triage/TriageIssueWorkflow.java` (key/displayName/description/`paramsSchema()` with `DEFAULT_SYSTEM_PROMPT`, no-op `onIssueComment`).
- Test: registry/wiring test following the `BotWebhookServiceTest` convention (real `IssueWorkflowRegistry` + orchestrator over mocked collaborators) asserting the workflow is discoverable and its schema defaults resolve via `WorkflowSelectionService.resolveParams`.

### Task 3: Triage service — prompt assembly, modes, validation
- Create: `issueworkflow/triage/IssueTriageService.java`, `issueworkflow/triage/TriageRoutingException.java`.
- Unit tests (`IssueTriageServiceTest`, plain JUnit 5 + Mockito):
  - native mode: tool suffix appended, single `assign_issue` descriptor advertised, `ChatTurn` with one valid call parses; zero/multiple/unknown-tool/prose-only turns rejected;
  - legacy mode: JSON suffix appended, valid JSON parses, prose-wrapped JSON extracted, malformed JSON / unknown assignee / blank reason / multi-line reason rejected;
  - both modes enforce the same configured allowed set incl. `none`;
  - self-assignment guard (assignee == bot username) rejected.

### Task 4: Execution paths
- In `IssueTriageService`: reason comment → assign (real assignee) / comment-only (`none`) / error comment + `TriageRoutingException` (invalid output, non-assignable account, provider unsupported).
- Tests: success-assign, success-`none`, provider-4xx, `UnsupportedOperationException`, malformed output — asserting comment/assign call order and that the exception propagates to the orchestrator's error handling.

### Task 5: V42 seed migration
- Create: `db/migration/h2/V42__issue_triage_configuration_seed.sql` + `db/migration/postgresql/V42__issue_triage_configuration_seed.sql` (V39-style guards).
- Sync: `src/test/resources/data.sql`.
- Test: `IssueTriageMigrationTest` following `issueworkflow/IssueWorkflowMigrationTest` (programmatic Flyway, pre-migration fixture, assert configuration + selection rows; plain JUnit, not `@SpringBootTest`). `AiGitBotApplicationTests` context boot is the free smoke test.

### Task 6: Documentation
- Create: `doc/ISSUE_TRIAGE.md` — top-level feature doc: when it runs (issue-created bot setting + assignment), prompt configuration and the mandatory review warning, tool-calling vs JSON mode, `none` semantics, failure behavior (invalid output / non-assignable account → error comment + bot error record + `issueassignment.failed`), the `issue_hemingway` canonical name.
- Modify: `doc/ISSUE_WORKFLOWS.md` (built-in workflow table row + V42 seed note + link), `doc/README.md` index entry.

### Task 7: Verification
- `mvn test -Dtest=ArchitectureTest` (new package), focused tests above, then full `mvn verify` (ArchUnit controller/layering rules only fail there).
- No new Maven dependencies; enforcer whitelist untouched. No new controllers/templates (params UI is declarative) — confirm no ArchUnit impact.

## Files touched (summary)

- New: `issueworkflow/triage/{TriageIssueWorkflow,IssueTriageService,TriageParam,TriageRoutingException}.java`, `V42` (h2 + postgresql), `doc/ISSUE_TRIAGE.md`, tests (`IssueTriageServiceTest`, `IssueTriageMigrationTest`, provider client tests, wiring test).
- Modified: `repository/RepositoryApiClient.java`, the three provider clients, `doc/ISSUE_WORKFLOWS.md`, `doc/README.md`, `src/test/resources/data.sql`.

## Risks and open questions

1. **Allowed-assignees parameter (ADR-2)** is the one addition beyond the issue's letter — review explicitly.
2. **Gitea silent-assignee behavior** (ADR-4) may force a collaborators pre-check; verified in Task 1.
3. **GitLab two-call assignment** adds a failure mode (user exists but not a project member) — covered by the generic error-comment path.
4. **Prompt/assignees drift:** nothing forces the prompt's listed names and the `assignees` param to agree; the field description must tell admins to edit both together. (Rejected alternative: parsing names out of the prompt.)
5. Model output is untrusted: all validation happens before any provider mutation; assignment is the only write besides comments.

## Acceptance-criteria mapping

- Workflow available + editable `systemPrompt` + prefilled default + review warning → ADR-1/2, Tasks 2, 5.
- Tool-call vs JSON suffixes, same allowed values → ADR-3, Task 3.
- Runs on issue-open (bot setting) and on assignment → existing triggers, Task 2 wiring test.
- Success/`none`/invalid-account/malformed-output behaviors → ADR-4, Task 4.
- Failures via existing error handling → `TriageRoutingException` through the orchestrator (ADR-4).
- Documentation → Task 6.
