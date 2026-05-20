# Future PR workflow ideas

A brainstorm of additional `PrWorkflow` implementations that would
plug into AI-Git-Bot's existing orchestrator
(`PrWorkflowOrchestrator` + `PrWorkflowRegistry`) without architectural
changes. Every idea here:

- Implements `PrWorkflow` and registers itself via Spring DI.
- Reuses the existing `AgentLoop` + tool-whitelist infrastructure
  (`BotToolConfiguration`, `McpConfiguration`, `AgentToolRouter`).
- Optionally consumes a `DeploymentTarget` if it needs a per-PR
  preview.
- Persists its run via `PrWorkflowRun` / `PrWorkflowStep` and
  contributes Micrometer meters out of the box.

Use this list as a backlog — pick the one(s) that hurt most for the
roles your team actually has, and convert them into user stories
following the template in
[`../doc/agentic-workflows/`](../doc/agentic-workflows/README.md).

---

## Conventions for the ideas below

| Field | Meaning |
|---|---|
| **Workflow key** | The lower-case-kebab `key()` returned by the workflow. |
| **Category** | Reuses `PrWorkflowCategory` (`REVIEW` / `TESTING` / `SECURITY` / `DOCS` / `CUSTOM`). |
| **Persona** | The role that should ask their team to enable the workflow. |
| **Triggers** | The webhook events that should cause it to run. |
| **Outputs** | What lands on the PR (comment, status check, artifact, follow-up PR, …). |
| **Needs deployment target?** | `yes` if a per-PR preview is required. |
| **New tools?** | Built-in tools that would have to ship in `ToolKind.PR_WORKFLOW` — or MCP tools the operator already has. |

---

## Group A — Quality &amp; testing (extends `TESTING`)

### A1. `unit-test-author`
**Category:** `TESTING` · **Persona:** developer / tech lead · **Deployment target?** no

Generates / extends **unit tests** for files touched by the diff.
Different from the shipped `e2e-test` workflow: no preview env, no
browser — just `pr-unit-test-write` + the project's own
`mvn test` / `npm test` / `pytest` / `go test` runner.

- **Triggers:** PR opened/synchronized, `@bot write-unit-tests <path>`.
- **Outputs:** New test files in the PR's branch (via the existing
  coding-agent infra), inline coverage delta in the PR comment.
- **Why it matters:** "We'll add a test later" reborn as "the bot
  already added the test in commit 3 of this PR".

### A2. `mutation-test`
**Category:** `TESTING` · **Persona:** QA lead / staff engineer · **Deployment target?** no

Runs mutation testing (PIT for Java, Stryker for JS/TS, mutmut for
Python) **only on the files touched by the diff** and reports the
mutation score delta.

- **Triggers:** PR opened/synchronized.
- **Outputs:** Mutation-score table per touched file; flags drops
  &gt; X %.
- **Why it matters:** You already have the unit tests. The question
  is whether they actually catch regressions. Most teams never wire
  mutation testing because it is too slow on the whole repo — scoping
  to the diff makes it cheap per PR.

### A3. `flake-detective`
**Category:** `TESTING` · **Persona:** SRE / QA · **Deployment target?** no

Re-runs the project's existing test suite **N times** on the diff
branch and tags any test that fails non-deterministically.

- **Triggers:** PR opened, nightly cron on `main`.
- **Outputs:** A "flaky tests detected on this PR" comment with
  reproduction stats; optionally opens a follow-up issue assigned
  to the test owner.
- **Why it matters:** Flakes destroy trust in CI faster than any
  other class of bug.

### A4. `accessibility-audit`
**Category:** `TESTING` · **Persona:** product designer / frontend lead · **Deployment target?** **yes (`STATIC` / `WEBHOOK` / …)**

Runs `axe-core` / Lighthouse a11y audit against the per-PR preview
(reusing the same deployment targets as `e2e-test`). Reports new
violations.

- **Triggers:** PR opened on a UI-touching diff.
- **Outputs:** WCAG-grouped violation table + screenshots attached
  via `attachPullRequestArtifact`.
- **Why it matters:** Accessibility regressions are silent until a
  customer files a complaint. The bot makes them noisy.

### A5. `perf-smoke`
**Category:** `TESTING` · **Persona:** SRE / staff engineer · **Deployment target?** **yes**

Runs a small `k6` / `Lighthouse perf` script against the preview and
flags p95 / TTFB / LCP / Core Web Vitals regressions vs. the parent
branch.

- **Triggers:** PR opened/synchronized.
- **Outputs:** Before/after table; status check `ai-bot/perf` red on
  &gt; X % regression.
- **Why it matters:** Catches the "we accidentally O(n²) the
  dashboard query" change *before* prod.

### A6. `visual-regression`
**Category:** `TESTING` · **Persona:** frontend lead · **Deployment target?** **yes**

Drives Playwright through a list of canonical screens, takes
screenshots against the preview, diffs them against an artifact
stored in the previous green run on `main`. Posts the diff as an
inline image attachment.

- **Triggers:** PR opened/synchronized.
- **Outputs:** Side-by-side image grid in the PR comment; configurable
  pixel-diff threshold.
- **Why it matters:** "It looks the same to me" is the worst kind of
  review.

### A7. `seed-data-author`
**Category:** `TESTING` · **Persona:** QA / backend dev · **Deployment target?** **yes (`MCP` recommended)**

Calls a platform MCP tool `seed-test-data` with a planner-generated
description ("create 1 admin user with 3 sample orders") so the
preview env is deterministically populated before E2E tests run.

- **Triggers:** chained before `e2e-test` in the workflow
  configuration.
- **Outputs:** none on the PR; a step entry in
  `pr_workflow_steps`.
- **Why it matters:** Removes the #1 reason E2E tests "work on my
  machine but not in the preview".

---

## Group B — Security &amp; compliance (`SECURITY`)

### B1. `dependency-cve-scan`
**Category:** `SECURITY` · **Persona:** security lead / CISO · **Deployment target?** no

Runs `osv-scanner` / `npm audit` / `pip-audit` / `cargo audit` over
the lock-file delta in the PR. Maps every CVE to a recommended
upgrade path.

- **Triggers:** PR that touches `package-lock.json`,
  `pnpm-lock.yaml`, `requirements.txt`, `pom.xml`, `Cargo.lock`,
  `go.sum`, …
- **Outputs:** Severity-grouped CVE table + suggested fix; optional
  follow-up PR via the coding agent.
- **Why it matters:** The reason Renovate exists, but explained in
  English instead of a diff. Pairs naturally with B6
  (`fix-and-pr`).

### B2. `secret-leak-guard`
**Category:** `SECURITY` · **Persona:** security lead · **Deployment target?** no

Runs `trufflehog` / `gitleaks` on the diff (not the whole history —
fast). Refuses the PR with a status check on a hit and posts an
inline comment on the offending line.

- **Triggers:** every PR.
- **Outputs:** Inline review comment; `ai-bot/secrets` status check.
- **Why it matters:** Secrets leak in seconds, get cached forever.

### B3. `sast-triage`
**Category:** `SECURITY` · **Persona:** AppSec engineer · **Deployment target?** no

Wraps the team's existing SAST output (Snyk Code, SonarQube,
Semgrep) and runs it through an LLM that **triages** findings:
which are false-positive vs. real, with one-paragraph justification
each.

- **Triggers:** PR opened/synchronized.
- **Outputs:** Triaged findings sorted by severity + likelihood;
  inline comments only on high-confidence true-positives.
- **Why it matters:** SAST tools find 100 things per PR. The bot
  gives the AppSec engineer the 3 that matter.

### B4. `iac-policy-check`
**Category:** `SECURITY` · **Persona:** SRE / cloud-ops · **Deployment target?** no

Runs `tfsec` / `checkov` / `kube-score` on Terraform / k8s manifests
in the diff and *explains the violation* in plain English plus the
fix. Optional `@bot fix-iac` slash command opens a follow-up PR.

- **Triggers:** PR touching `**/*.tf`, `**/*.yaml` under `k8s/` etc.
- **Outputs:** Inline comments + optional follow-up PR.
- **Why it matters:** The "S3 bucket public read" class of incident.

### B5. `licence-policy`
**Category:** `SECURITY` · **Persona:** legal / OSS PMO · **Deployment target?** no

Diffs the SBOM against the project's allow-list (`MIT`, `Apache-2.0`,
…) and refuses the PR if a copyleft licence sneaks in via a
transitive dependency.

- **Triggers:** PR touching dependency lock-files.
- **Outputs:** Status check + inline comment naming the offending
  package and its parent.
- **Why it matters:** "Why is GPL-3.0 in our SaaS frontend?"

### B6. `auto-fix-pr`
**Category:** `SECURITY` (or `CUSTOM`) · **Persona:** maintainer · **Deployment target?** no

When `dependency-cve-scan`, `iac-policy-check`, etc. flag a fix
that has a single mechanical solution, this workflow opens a
**separate follow-up PR** with the upgrade and references the
original PR for context. Reuses `SuitePromotionService`'s
follow-up-PR plumbing.

- **Triggers:** finding from another security workflow.
- **Outputs:** Follow-up PR; idempotent via
  `PrWorkflowRun.followUpPrNumber`.

---

## Group C — Documentation &amp; knowledge (`DOCS`)

### C1. `api-doc-diff`
**Category:** `DOCS` · **Persona:** API owner / DX lead · **Deployment target?** no

Detects breaking changes in OpenAPI / GraphQL / gRPC schemas via
`oasdiff` / `graphql-inspector` / `buf breaking` and explains the
impact ("removing field `x` will break version-pinned clients
v1.2.x").

- **Triggers:** PR touching `openapi.yaml`, `*.graphql`,
  `*.proto`.
- **Outputs:** Severity-graded breaking-change list; status check
  blocks merge on `breaking` without a `breaking-change-approved`
  label.
- **Why it matters:** The most expensive bugs are wire-protocol
  bugs.

### C2. `changelog-author`
**Category:** `DOCS` · **Persona:** release manager · **Deployment target?** no

Reads the diff + PR title/body + linked issues and writes a
**user-facing changelog entry**, classified per Keep-A-Changelog
sections (`Added` / `Changed` / `Fixed` / …).

- **Triggers:** PR opened/synchronized, `@bot draft-changelog`.
- **Outputs:** A draft `CHANGELOG.md` patch in the PR comment +
  optional commit on the branch.

### C3. `migration-guide`
**Category:** `DOCS` · **Persona:** maintainer of a published library · **Deployment target?** no

When a PR introduces a breaking change (detected via C1 or via a
`!:` Conventional-Commits prefix) the bot drafts a
`MIGRATION_X_TO_Y.md` section explaining: "users of v3 should
replace `foo()` with `bar()`; arguments map as follows".

- **Triggers:** breaking-change PRs.
- **Outputs:** Patch to `doc/migration/MIGRATION_*.md`.

### C4. `readme-sync`
**Category:** `DOCS` · **Persona:** OSS maintainer · **Deployment target?** no

Detects when a code change drifts from the README's quick-start /
configuration matrix and proposes the fix.

- **Triggers:** PR touching public configuration surface or
  `README.md`.
- **Outputs:** Inline comment: "this PR adds the `FOO_BAR` env var
  but README still says only `BAZ` is supported — patch attached".

### C5. `arch-decision-record`
**Category:** `DOCS` · **Persona:** staff engineer / architect · **Deployment target?** no

When a PR carries a label `arch` (or `@bot draft-adr`), the bot
drafts an ADR Markdown file under `doc/adr/`, with the
*Context / Decision / Consequences* sections inferred from the diff
and linked issue.

- **Triggers:** label / slash command.
- **Outputs:** Commit on the branch creating
  `doc/adr/NNNN-<title>.md`.

### C6. `screenshot-author`
**Category:** `DOCS` · **Persona:** technical writer / DX lead · **Deployment target?** **yes**

Drives Playwright through a list of canonical UI screens against the
preview, captures fresh PNGs, and **opens a follow-up PR** that
overwrites the screenshots referenced from the user-guide. Reuses
the same suite-promotion plumbing (`offer-as-pr` mode).

- **Triggers:** PR touching the front-end + a label `screenshot`.
- **Outputs:** Follow-up PR replacing `doc/screenshots/**/*.png`.
- **Why it matters:** The single biggest reason docs go stale.

---

## Group D — Process &amp; collaboration (`CUSTOM`)

### D1. `issue-linking`
**Category:** `CUSTOM` · **Persona:** PM / engineering manager · **Deployment target?** no

Refuses to merge a PR that has no linked issue (Jira / Linear /
GitHub issue), with an inline comment explaining the policy and a
`@bot link-issue` slash command that asks the LLM to search the
issue tracker (via MCP) for likely matches.

- **Triggers:** PR opened.
- **Outputs:** Status check `ai-bot/issue-linked`, optional follow-up
  comment with candidate issue links.

### D2. `pr-summarizer`
**Category:** `CUSTOM` · **Persona:** reviewer / EM · **Deployment target?** no

Posts a one-paragraph TL;DR + a bullet list of the *intent* of the
diff (not just what changed — *why*), targeted at non-technical
stakeholders.

- **Triggers:** PR opened, `@bot summarize`.
- **Outputs:** Sticky PR comment that updates on every push.

### D3. `release-note-builder`
**Category:** `CUSTOM` · **Persona:** release manager · **Deployment target?** no

When a PR is merged into `main` (or `release/*`), the bot maintains a
running `RELEASE_NOTES.md` draft and posts it as a comment on a
"Release v1.8.0" tracking issue.

- **Triggers:** `pull_request closed && merged == true`.
- **Outputs:** Updated `RELEASE_NOTES.md` patch on the next release
  branch.

### D4. `chore-router`
**Category:** `CUSTOM` · **Persona:** EM · **Deployment target?** no

Inspects new issues and *routes* them: small mechanical change →
assign to coding bot; vague bug report → assign to writer bot;
neither → assign to the on-call human.

- **Triggers:** issue opened.
- **Outputs:** Reassignment + a one-line comment explaining the
  routing decision.
- **Why it matters:** Saves the EM ~30 min/day of inbox triage.

### D5. `i18n-coverage`
**Category:** `CUSTOM` · **Persona:** localization lead · **Deployment target?** no

Detects when a PR adds a new user-facing string to one
`messages_*.properties` (or `i18n/*.json`) but not the others.
Drafts the missing translations via the LLM, scoped per locale.

- **Triggers:** PR touching i18n files.
- **Outputs:** Inline comment listing missing locales + optional
  follow-up commit with proposed translations.

### D6. `breaking-change-bouncer`
**Category:** `CUSTOM` · **Persona:** library maintainer · **Deployment target?** no

If C1 (`api-doc-diff`) or `oasdiff` flags `breaking` and the PR
title is not Conventional-Commits-prefixed `feat!:` /
`fix!:` / `BREAKING CHANGE:`, the bot **rewrites the title** with
`@bot retitle` (suggestion → operator approves with a 👍 reaction)
and demands a labelled justification.

- **Triggers:** chained after C1.
- **Outputs:** Inline comment + status check.

---

## Group E — Operations &amp; observability (`CUSTOM`)

### E1. `slo-impact-analysis`
**Category:** `CUSTOM` · **Persona:** SRE · **Deployment target?** no

Reads the PR diff and asks the LLM (with the team's SLO doc as
context, fetched via the `cat`/`rg` tools) whether any service
covered by an SLO is touched. Posts a "this PR may affect SLO X
because …" comment with risk score.

- **Triggers:** PR opened.
- **Outputs:** SRE-targeted summary comment.

### E2. `runbook-touch`
**Category:** `CUSTOM` · **Persona:** SRE · **Deployment target?** no

If the PR touches alerting rules (`alerts.yaml`) or autoscaling
(`hpa.yaml`), the bot ensures the corresponding runbook in
`runbooks/` is updated. If not, it drafts the diff.

- **Triggers:** PR touching `alerts.*` / `hpa.*` / `pagerduty.*`.
- **Outputs:** Inline comment + optional follow-up commit.

### E3. `cost-impact`
**Category:** `CUSTOM` · **Persona:** FinOps / SRE · **Deployment target?** no

Runs `infracost` on Terraform diffs and posts the monthly delta as a
PR comment with a category breakdown.

- **Triggers:** PR touching `**/*.tf`.
- **Outputs:** Cost-delta table; optional status check on threshold.

### E4. `feature-flag-audit`
**Category:** `CUSTOM` · **Persona:** product / platform lead · **Deployment target?** no

Detects new or removed feature-flag references in the diff
(LaunchDarkly / Unleash / OpenFeature) and lists them with their
current rollout status fetched via the relevant MCP server. Flags
"flag added but never rolled out" debt.

- **Triggers:** PR touching feature-flag SDK calls.
- **Outputs:** Inline comment with flag rollout state.

### E5. `dashboard-stale`
**Category:** `CUSTOM` · **Persona:** SRE · **Deployment target?** no

When a PR renames or removes a Prometheus metric, the bot scans the
team's Grafana dashboards (via the Grafana MCP server) and lists
which panels would break.

- **Triggers:** PR touching `metrics/*.go` / `metrics.py` / similar.
- **Outputs:** "These dashboards will go blank" comment.

---

## Group F — Data &amp; database (`CUSTOM`)

### F1. `db-migration-review`
**Category:** `CUSTOM` · **Persona:** DBA / backend lead · **Deployment target?** no

Special-cases PRs touching `db/migration/*.sql` (Flyway / Liquibase /
Alembic). The LLM checks for: missing index on a new FK, locking
operations on large tables, irreversible drops, NOT-NULL on a
populated column without a default, etc. References each finding to
a known PostgreSQL/MySQL gotcha doc.

- **Triggers:** PR touching migration folders.
- **Outputs:** Inline comments with severity + recommended idiomatic
  fix.

### F2. `pii-classifier`
**Category:** `SECURITY` · **Persona:** privacy / DPO · **Deployment target?** no

Detects when a PR adds a new column / log line / event payload that
likely contains PII (regex + LLM judgement) and asks the author to
confirm classification (`public` / `internal` / `confidential` /
`PII`) by reacting with an emoji to the bot comment. Refuses to
merge until classified.

- **Triggers:** PR touching schemas / logging / event payloads.
- **Outputs:** Status check + inline comments.

### F3. `query-explain`
**Category:** `TESTING` · **Persona:** backend dev / DBA · **Deployment target?** **yes (DB-aware preview)**

Runs `EXPLAIN ANALYZE` for new/changed SQL queries on a
representative copy of the production DB schema (seeded preview)
and flags Sequential Scans on tables &gt; X rows.

- **Triggers:** PR touching SQL files / ORM query builders.
- **Outputs:** Per-query plan summary + recommendation.

---

## Group G — Release engineering (`CUSTOM`)

### G1. `version-bump-decider`
**Category:** `CUSTOM` · **Persona:** release manager · **Deployment target?** no

Looks at all merged PRs since the last tag, classifies them as
SemVer `major` / `minor` / `patch`, and proposes the next version +
the corresponding CHANGELOG bump.

- **Triggers:** scheduled cron + label `release-train`.
- **Outputs:** Follow-up PR bumping `package.json` / `pom.xml` /
  `Cargo.toml`.

### G2. `release-rehearsal`
**Category:** `TESTING` · **Persona:** release manager · **Deployment target?** **yes**

Runs the full E2E suite — including paid-tier features — against a
preview env that is deliberately seeded as a "release-candidate"
environment (versioned, frozen). Outputs a release-readiness report.

- **Triggers:** PR with label `release-candidate`.
- **Outputs:** Release-readiness checklist comment + status check.

### G3. `compat-matrix`
**Category:** `TESTING` · **Persona:** library maintainer · **Deployment target?** no

For libraries published to multiple runtimes, runs the test matrix
(`Node 18/20/22`, `Python 3.10/3.11/3.12`, `Java 17/21`, …) on the
diff branch and posts a compact PASS/FAIL grid.

- **Triggers:** PR opened/synchronized.
- **Outputs:** Markdown matrix table.

---

## Group H — Cross-PR / cross-repo workflows (advanced)

These need extra orchestration plumbing but are natural extensions
of the existing SPI.

### H1. `dependency-update-coordination`
**Category:** `CUSTOM` · **Persona:** platform team · **Deployment target?** no

When a PR in repo A bumps a shared library, the bot opens a
**fan-out PR** in every consumer repo (using the existing
`RepositoryApiClient.createPullRequest` surface), each running its
own `e2e-test` workflow.

### H2. `feature-flag-graduation`
**Category:** `CUSTOM` · **Persona:** product engineer · **Deployment target?** no

When a feature flag has been at 100 % for &gt; X days (consulted via
the LaunchDarkly / Unleash MCP), the bot opens a clean-up PR removing
the flag from the codebase. Pairs with `auto-fix-pr` (B6) machinery.

### H3. `dead-code-sweeper`
**Category:** `CUSTOM` · **Persona:** maintainer · **Deployment target?** no

Periodically runs `knip` / `unimported` / `vulture` against `main`
and opens a PR removing files / exports that no other PR has touched
in &gt; 6 months and whose call graph is empty.

### H4. `ai-pr-review-of-the-bot's-own-pr`
**Category:** `REVIEW` · **Persona:** maintainer · **Deployment target?** no

When a coding bot opens a PR, a *different* AI provider (e.g. Claude
reviewing OpenAI's output) runs the review workflow with a stricter
system prompt. Implements the "two-model adversary" pattern at zero
extra plumbing — both bots already exist.

### H5. `monorepo-affected-only`
**Category:** `CUSTOM` · **Persona:** monorepo platform team · **Deployment target?** no

Runs `nx affected` / `bazel query` / `turbo run --filter` to compute
the affected-package graph and **rewrites the orchestrator's workflow
selection** so only the relevant `e2e-test` / `unit-test-author` /
`perf-smoke` runs fire — saving CI minutes on big monorepos.

---

## How to prioritise

A quick scoring matrix when picking which two or three to build next:

| Question | Weight |
|---|---|
| Does it solve a chore that *everybody* on the team postpones? | × 3 |
| Can the **first version** ship without a deployment target? | × 2 |
| Does it reuse a tool the bot already has (no new built-ins)? | × 2 |
| Can it produce a **follow-up PR**, not just a comment? | × 1 |
| Is the persona currently *unrepresented* in your customer base? | × 1 |

Plug each idea into the matrix and the top three will be your next
sprint.

---

## Appendix — what the SPI gives every new workflow for free

Every idea above gets — **without writing it again** — the following
infrastructure:

- Spring DI registration via `PrWorkflowRegistry`.
- Persisted lifecycle (`PrWorkflowRun` / `PrWorkflowStep`) with the
  `RUNNING → SUCCESS / FAILED / WAITING_DEPLOY / CANCELLED` state
  machine and the cancel-on-resync semantics.
- Provider-native function calling and the `agent.use_legacy_tool_calling`
  fallback.
- Per-bot tool whitelist (`BotToolConfiguration`).
- Per-MCP-config tool whitelist (`McpToolSelectionService`).
- Encryption at rest (`EncryptionService`) for any secret column.
- HMAC-signed callback channel (`/api/workflow-callback/{runId}/{secret}`).
- Per-provider artifact upload (`attachPullRequestArtifact`) for
  comments containing screenshots / videos / reports.
- Slash-command dispatch (`@bot <command>`) wired into all four
  webhook handlers (Gitea / GitHub / GitLab / Bitbucket).
- Follow-up-PR plumbing via `WorkspaceService` +
  `RepositoryApiClient.createPullRequest` (the same code path
  `SuitePromotionService` already uses).
- Telemetry: `prworkflow.run_total{workflow,status}` and
  `prworkflow.run_duration_seconds{workflow}`.

Building a new `PrWorkflow` is, in most cases, **a single Java class
plus a JSON-Schema for its params**. That is the design intent of the
SPI — and the reason every bullet on this page is small enough to
actually ship.

