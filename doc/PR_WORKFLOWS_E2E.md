# PR Workflows: E2E Tests

The `E2ETestWorkflow` is the non-review workflow shipped on top of the
agentic PR-workflow platform. It runs after a pull request is opened
or synchronised and follows the pipeline described in
[`CONCEPT_AND_ARCHITECTURE.md`](./agentic-workflows/CONCEPT_AND_ARCHITECTURE.md):

```
plan → deploy → author → run → comment
```

Three LLM agents (`TestPlannerAgent`, `TestAuthorAgent`,
`TestRunnerAgent`) are wired through `PlaywrightTestSuiteRunner`.
Operators drive the workflow via the seeded `Full-stack QA` workflow
configuration, the two slash commands `@bot rerun-tests` and
`@bot regenerate-tests [feedback]`, and PR-close teardown that honours
`SuiteLifecycleMode`. All four repository providers (GitHub, Gitea,
GitLab, Bitbucket) support PR-comment artifact attachments; operator
feedback from `regenerate-tests` is threaded into the planner's prompt
via `PrWorkflowContext.hints`.

## Enabling the workflow on a bot

1. Make sure the bot has a **deployment target** configured under
   *System settings → Deployment targets* (see [`PR_WORKFLOWS.md`](./PR_WORKFLOWS.md)
   for the available strategies — `STATIC`, `WEBHOOK`, `MCP`, and
   `CI_ACTION`; cross-reference the persona-driven walk-throughs under
   [`doc/agentic-workflows/`](./agentic-workflows/README.md) if you're
   unsure which one to pick). Without a target the workflow aborts
   immediately and posts a clearly labelled skip-comment.

   > 💡 If you run the bot itself via Docker / `docker compose` and use a
   > `CI_ACTION` deployment target, make sure the bot's public callback base URL
   > is reachable **from inside the workflow job container**. In Docker-based
   > setups configure this via `APP_PUBLIC_URL` (maps to Spring property
   > `app.public-url`), for example:
   >
   > ```yaml
   > APP_PUBLIC_URL: http://172.17.0.1:8080         # Linux / WSL2
   > APP_PUBLIC_URL: http://host.docker.internal:8080  # macOS / Windows Docker Desktop
   > ```
   >
   > If this is left at `http://localhost:8080`, E2E preview callbacks fail
   > because `localhost` inside the job container points to the container
   > itself, not to the bot.

2. Open the bot's *Workflow configuration*. Either pick the seeded
   **`Full-stack QA`** configuration (has `review` + `e2e-test`
   pre-enabled with `framework=playwright`, `maxRetries=1`,
   `maxTestCases=10`) or toggle the `E2E Tests` workflow on your own
   configuration (listed under **TESTING**, **disabled by default** on
   the seeded `Default` configuration). Tune the per-workflow
   parameters as needed:

   | Field            | Type    | Default      | Notes                                                                |
   |------------------|---------|--------------|----------------------------------------------------------------------|
   | `framework`      | string  | `playwright` | One of `playwright`, `pytest`, `k6`, `cypress`. Only `playwright` is well-tested. |
   | `maxRetries`     | integer | `1`          | Per-test retry budget. A test that passes after retry is tagged `FLAKY`. Capped at 5. |
   | `maxTestCases`   | integer | `20`         | Hard cost guard. Capped at 100 server-side. |
   | `suiteLifecycle` | string  | `ephemeral`  | See [Suite lifecycle modes](#suite-lifecycle-modes) below. |
   | `promotionThresholdPercent` | integer | `100` | Minimum percentage of executed cases that must pass for the suite to be **promoted** (offer-as-pr / commit-to-pr / promote-on-merge). `100` keeps the original "only fully green suites are promoted" behaviour; lower it (e.g. `80`) when you want partial successes to still trigger the configured promotion action. `ERROR` / `SKIPPED` suites are never promoted regardless of this value. See [Treating generated tests as a regression baseline](#treating-generated-tests-as-a-regression-baseline). |

3. Save. The next PR-open / PR-synchronise webhook triggers the workflow.

## Customising the agent prompts

The three E2E agents (**planner**, **author**, **runner**) ship with
sensible built-in role descriptions, but you can override each one per
**System Prompt entry** under *System settings → System prompts*. The
form exposes three additional editors below the existing
review / issue-agent / writer-agent slots:

- **E2E Planner System-Prompt** — reads the PR diff, produces the test plan.
- **E2E Author System-Prompt** — materialises each planned journey as a runnable test file.
- **E2E Runner System-Prompt** — executes the suite against the preview deployment and reports the outcome.

The bot's *Preview* button renders all three texts alongside the
existing review / coding / writer prompts so you can sanity-check them
before saving.

> 🛡️ **What you can and cannot edit.** These three editors hold the
> agent's **role description only** — persona, intent, tone, policy.
> The *technical protocol* is appended automatically by the software at
> runtime and is **not** editable from the UI: the active test framework
> key, the planner's JSON output schema, the author's required tool call
> (`pr-test-write`) and URL handling rules, and the runner's tool
> sequence (`preview-url` → `preview-status` → `pr-test-run` → optional
> `attach-artifact`). That split lets operators tune *how* the agents
> speak without breaking the JSON contract or the tool dispatch. The
> fallback when a slot is left blank is the corresponding
> `DEFAULT_*_EDITABLE` constant in
> `org.remus.giteabot.prworkflow.e2e.agents.E2ePromptLibrary`.

## What happens per run

1. Resolves workflow parameters from the bot's configuration.
2. Aborts cleanly (PR comment + `SKIPPED`) if no deployment target is configured.
3. Persists a draft `pr_test_suites` row tied to the active `PrWorkflowRun`.
4. Hands off to `DeploymentOrchestrator.requestDeployment(...)`. Failure /
   timeout / rejection surface as a "❌ Failed" PR comment.
5. Allocates a sandboxed workspace under
   `${ai-git-bot.e2e.workspace-root:${java.io.tmpdir}/ai-bot-pr-tests}/run-<id>/`
   with framework-specific scaffolding. Path traversal is denied by
   `PrTestWorkspaceManager.resolveInsideWorkspace(...)`.
6. Dispatches to the registered `TestSuiteRunner` (`PlaywrightTestSuiteRunner`
   for `playwright`), which drives the three agents through the existing
   `AgentLoop` / `chatWithTools` infrastructure. The agents use only
   the `PR_WORKFLOW`-category tools: `pr-test-write`, `pr-test-run`,
   `preview-url`, `preview-status`, `attach-artifact`.
7. Posts a Markdown summary to the PR:

   ```markdown
   ## E2E Test Run for PR #42

   **Framework:** `playwright`
   **Preview environment:** https://preview-42.example.com
   **Source SHA:** `abc12345`
   **Outcome:** ❌ FAILED (1/2 passed)

   | Test | Status | Duration |
   | --- | --- | --- |
   | `tests/login.spec.ts`<br/>Sign-in happy path | ✅ PASSED | 1.23s |
   | `tests/checkout.spec.ts`<br/>Add to cart and pay | ❌ FAILED | 4.57s |
   ```

## Slash commands

`E2eTestSlashCommandHandler` intercepts comments on PRs before the
regular code-review handler runs. Both commands are no-ops if the
bot's `WorkflowConfiguration` does not have `e2e-test` enabled — the
comment then falls through to the standard handler.

| Command | What it does | When to use |
|---|---|---|
| `@bot rerun-tests` | Re-runs the **existing** test files from the last suite. **No LLM call.** Fast, deterministic, free. | Suspected flakiness, preview env was unhealthy, you want to see if a fix in `main` made the suite green again. |
| `@bot regenerate-tests [feedback]` | Full planner → author → runner cycle. Optional free-text after the command is threaded into the planner's prompt as operator feedback. | Tests miss an obvious case: "also cover the empty-state", "use `data-testid` selectors", "the login URL is `/auth/sign-in` not `/login`". |

Both commands ack with a 👀 reaction and post a starting comment on
the PR.

## Suite lifecycle modes

The `suiteLifecycle` param controls what happens to the generated
`PrTestSuite` once the run is done. Four modes are implemented by
`SuitePromotionService`:

| Mode | Trigger | Target branch | Target dir | Follow-up PR |
|---|---|---|---|---|
| `ephemeral` *(default)* | — | — | — | No — suite is deleted on PR close. |
| `commit-to-pr` | Successful run on the feature PR | feature branch (direct commit) | `tests/e2e/pr-{n}/` | No — tests land directly on the feature branch. |
| `offer-as-pr` | Successful run on the feature PR | `ai-tests/pr-{n}` → feature branch | `tests/e2e/pr-{n}/` | "Add E2E tests for PR #N" — author reviews tests in isolation. |
| `promote-on-merge` | PR-merged webhook on the parent PR | `ai-tests/promoted-pr-{n}` → default branch | `tests/e2e/` | "Promote E2E tests from merged PR #N" — tests join standard CI. |

**Idempotency.** `PrWorkflowRun.followUpPrNumber` is set on the first
successful promotion. Re-runs (`@bot rerun-tests`), late merge events
and webhook retries all observe the populated field and no-op.

**Conflict policy.** If the destination file already exists, the bot
appends a numeric suffix before the first dot:
`login.spec.ts → login_2.spec.ts → login_3.spec.ts`. Final paths are
listed in the follow-up PR description.

**Failure handling.** Workspace / git / API failures degrade to a
"❌ Promotion failed — …" comment on the parent PR. The parent run's
terminal status is never rolled back — promotion is best-effort.

**Teardown.** `ephemeral` and `commit-to-pr` suites are deleted on PR
close. `offer-as-pr` / `promote-on-merge` suites are kept so the
dashboard can correlate the parent run with its follow-up PR. A
nightly `PromotedSuiteGarbageCollector` (default cron 03:17 server
time, configurable via `prworkflow.e2e.promotion.gc-cron`) removes
the suite rows once the owning run finished more than
`prworkflow.e2e.promotion.retention` ago (default `P30D`). The
promoted-PR link on `PrWorkflowRun` is preserved.

**Security note.** Promoted tests run in standard CI on the default
branch and may need manual secret review — selectors and fixtures are
LLM-generated and could leak environment-specific URLs, test
usernames or recorded responses. Treat each follow-up PR like any
other contribution: code review, branch-protection rules, secret
scanners all apply unchanged.

**Recipe.** A laptop-runnable walkthrough lives at
[`systemtest/README-suite-promotion.md`](../systemtest/README-suite-promotion.md).

## Treating generated tests as a regression baseline

Even with a well-tuned planner / author / runner pipeline, **LLM-generated
end-to-end tests are rarely 100% runnable on the first try**. Selectors
drift, assumptions about routing or seed data turn out to be slightly
wrong, async timing is mis-calibrated, and edge cases that the planner
considered "covered" still flake. Treating a green suite as ground truth
out of the gate sets the bar too high and ends up suppressing useful
output.

**Use the generated suite as a regression-test baseline instead.** The
recommended workflow is:

1. **Let the bot generate a first version.** Review the suite — both the
   structure (does it cover the journeys you care about?) and the
   individual specs (do the selectors match your app's actual DOM, are
   the URLs and credentials right?).
2. **Iterate with `@bot regenerate-tests <feedback>`.** Each comment is
   passed verbatim to the planner. Typical feedback: "use `data-testid`
   instead of CSS classes", "the login URL is `/auth/sign-in`, not
   `/login`", "add an empty-state test for the dashboard". Cheap, scoped,
   fast.
3. **Promote partial successes when the trend is healthy.** Set
   `promotionThresholdPercent` below `100` (e.g. `80`) so suites with a
   small number of flaky / wrong cases still land on the feature branch
   or in the follow-up PR. You then keep the *value* of the working
   tests and can either fix or delete the bad ones during normal code
   review — the same workflow you'd apply to any human-authored test.
4. **Re-run with `@bot rerun-tests` after fixing.** No LLM call, no
   regeneration; just executes the same files against a fresh preview
   deploy. Use this to confirm a fix or to retry transient infra
   flakiness without paying the planning bill again.

**Why a threshold below 100% is usually right.** A pure 100% gate means
that *one* genuinely buggy generated case prevents *all* of the
other valid cases from being adopted. Lowering the threshold trades a
small amount of cleanup work (delete or fix the bad ones in the
follow-up PR) for a much larger amount of reusable scaffolding (login
flow, navigation helpers, fixtures, asserted user journeys). The
threshold is enforced both by the immediate promotion path
(`offer-as-pr` / `commit-to-pr`) and by the `promote-on-merge` close
handler, so the same value applies regardless of when promotion fires.

**What is never promoted.** Suites that ended with status `ERROR` (e.g.
the framework itself blew up, missing dependency, sandbox failure) or
`SKIPPED` (no runner, deployment was never ready) are skipped
unconditionally — there is nothing meaningful in those rows to graduate.

## Lifecycle on PR close

`E2eTestPrCloseHandler` runs from `BotWebhookService.handlePrClosed(...)`.
For every `e2e-test` run on the closed PR it:

1. Broadcasts `DeploymentStrategy.teardown(...)` across every
   registered strategy so the preview environment is released.
2. Removes the sandboxed workspace on disk.
3. Deletes every `pr_test_suites` row whose `lifecycle_mode` is
   `ephemeral` or `commit-to-pr`. Suites tagged `offer-as-pr` or
   `promote-on-merge` are kept so the dashboard can correlate the
   parent run with the follow-up PR; the nightly GC eventually
   removes them.
4. For `promote-on-merge` suites only — and only when the parent PR
   was actually **merged** — invokes `SuitePromotionService.promote(...)`
   to open the follow-up PR.

Failures are logged but never abort the close handler.

## Persistence

See `V17__pr_test_suites.sql` (H2 + PostgreSQL):

```text
pr_test_suites(id, run_id FK, pr_number, framework, source_tree_ref,
               lifecycle_mode, created_at)
pr_test_cases (id, suite_id FK, path, title, content,
               last_status, last_run_at, last_duration_ms, last_log)
```

`run_id` cascades from `pr_workflow_runs(id)` so deleting a run cleans
up its suites and cases. The `cases.content` column holds the full
generated test source inline — the bot does not re-clone anything to
re-run.

## Where to look in the code

| Concern                             | Class                                                                 |
|-------------------------------------|-----------------------------------------------------------------------|
| Workflow entry point                | `org.remus.giteabot.prworkflow.e2e.E2ETestWorkflow`                   |
| Persistence model                   | `org.remus.giteabot.prworkflow.e2e.{PrTestSuite,PrTestCase}`          |
| Sandbox + path-traversal guards     | `org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager`  |
| Pluggable runner SPI                | `org.remus.giteabot.prworkflow.e2e.runner.TestSuiteRunner`            |
| Playwright runner (drives 3 agents) | `org.remus.giteabot.prworkflow.e2e.runner.PlaywrightTestSuiteRunner`  |
| Planner / Author / Runner agents    | `org.remus.giteabot.prworkflow.e2e.agents.*`                          |
| Sandboxed tool executor             | `org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor`      |
| Slash-command dispatcher            | `org.remus.giteabot.prworkflow.e2e.E2eTestSlashCommandHandler`        |
| PR-close teardown                   | `org.remus.giteabot.prworkflow.e2e.E2eTestPrCloseHandler`             |
| Suite promotion service             | `org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService`   |
| Markdown rendering                  | `org.remus.giteabot.prworkflow.e2e.E2eTestSummaryRenderer`            |

## Try it out — sample app under `systemtest/`

A minimal Node app (no external deps, ~70 lines of `server.js`) lives
under `systemtest/sample-e2e-app/` and is exposed via
[`systemtest/docker-compose-e2e-sample.yml`](../systemtest/docker-compose-e2e-sample.yml).
It boots a single login form with credentials `demo` / `demo` and
exposes `/healthz` for the deployment-target probe.

```bash
docker compose -f systemtest/docker-compose-e2e-sample.yml up --build
# In the bot UI:
#   1. System settings → Deployment targets → New
#        Strategy:               STATIC
#        Preview URL template:   http://sample-e2e-app:3000   (or http://host.docker.internal:3030)
#        Health-check path:      /healthz
#   2. Bot → Workflow configuration: pick "Full-stack QA"
#   3. Bot → Deployment target: pick the one created above
# Open a PR → the bot generates Playwright specs, runs them against the sample
# app and posts the run summary back on the PR.
```

## Tips for reliable, low-cost E2E runs

The E2E workflow is opinionated but ultimately runs LLM-authored
Playwright tests against a freshly deployed preview environment. The
following recommendations come from running it daily against the
`ai-git-bot` codebase itself (see [Reference setup](#reference-setup-ai-git-bot-on-itself)
below) and dramatically improve both the success rate and the AI bill.

### 1. Make your preview deployment idempotent

Flaky tests almost always trace back to a non-deterministic preview
environment, not to a flaky test framework. Before each deploy, the
workflow should:

- **Wipe the slot completely.** Remove the previous build artifact
  (JAR, binary, container image, `dist/` folder, …), working
  directory, uploaded files and any process holding the port. Do not
  rely on "the new artifact will just overwrite". The `preview.yml`
  shipped in this repo does `rm -rf "$SLOT_DIR" && mkdir -p
  "$SLOT_DIR"` before copying the new artifact in, and graceful-stops
  the previous process with a `SIGKILL` fallback.
- **Use a throw-away database.** An in-memory or per-PR database gives
  every boot a fresh, migration-driven schema. Examples: SQLite
  `:memory:`, in-memory H2 (`jdbc:h2:mem:preview-$SLOT`), a per-PR
  Docker Postgres container, or a `DROP SCHEMA; CREATE SCHEMA;` step
  in `before_deploy`. The point is: do not share state with the
  previous run.
- **Pin deterministic secrets for previews.** A random encryption /
  signing key per boot makes any seeded encrypted column unreadable
  and produces decryption noise that hides real failures. Use a
  constant, well-known preview value (and make sure it can never reach
  production).
- **Garbage-collect old slots.** Stale slot directories accumulate
  fast on shared hosts — a 24 h cleanup loop keeps the host healthy
  and prevents disk-full failures masquerading as test failures.
- **Wait for readiness, do not sleep.** Probe a health endpoint
  (`/health`, `/healthz`, `/actuator/health`, `/_status`, …) with a
  bounded `curl --max-time` loop and only POST the `callbackUrl` once
  the app reports healthy. The bot will block on the callback for up
  to 30 minutes by default; sending an early callback against a
  half-started app causes timeouts on the very first browser step.

### 2. Teach the planner how to reach a useful starting point

LLM-authored tests are only as good as the system prompt that frames
them. Customise the bot's `SystemPrompt` (under *System settings →
System prompts*) so the planner / author know how to:

- **Log in.** Either ship a deterministic test account (a known
  username + password injected via env vars / a test-only config
  file) or — better — provide a preview-only bypass like the
  auto-login flag used in this repo's `preview.yml`. The bot will
  then post a single initial test that asserts auto-login succeeded,
  instead of weaving flaky form interactions into every spec.
- **Reach the feature under test.** Give the AI the exact URL path
  ("the system-settings page lives at `/system-settings`"). Without
  this, the planner often invents reasonable-looking but wrong paths
  and burns retries discovering them.
- **Use stable selectors.** Recommend explicit selectors in the
  prompt — `data-testid`, `aria-label`, ARIA roles, accessible names.
  AI naïvely picks `.btn-primary:nth-child(2)`-style fragile
  selectors when not told otherwise.
- **Skip seed data.** If your app boots empty, ask the planner to
  create the fixtures it needs in a setup step. If your app boots
  with seeded demo data, document the names so the AI does not invent
  ones that don't exist.

A short, app-specific addendum (200 – 400 tokens) at the bottom of
the system prompt tends to be worth more than rewording the
high-level persona.

### 3. Consider running the app in "test mode"

Most non-trivial apps grow knobs that make E2E testing dramatically
easier, regardless of language or framework:

- **Auto-login / fake-SSO toggle** — skip the login form entirely.
  This repo ships such a toggle for exactly that purpose.
- **Disable rate limits, captchas, 2FA, webhook signature checks.**
  The AI cannot solve a captcha, and rate limits make per-test
  retries meaningless.
- **Freeze the clock or expose a `/test/advance-clock`-style
  helper.** Tests of scheduled jobs become deterministic.
- **Pre-seed minimal fixtures via a test profile / mode flag.** A
  handful of known users / projects keeps test selectors stable
  across runs.
- **Surface verbose error responses.** Replacing a generic "500" with
  the actual error type + message makes the LLM-driven runner's
  retry analysis vastly more useful — and any captured
  failure-context artifact actually points at the bug.

Wire every such toggle behind an explicit, default-off flag so it
cannot accidentally land in production. The mechanism is
language-agnostic: a Spring `@ConditionalOnProperty(havingValue =
"true")` bean, a Node `if (process.env.AUTO_LOGIN === 'true')`
middleware, a Django `settings.AUTO_LOGIN`-gated middleware, a Rails
initializer guarded by `Rails.env.preview?` — pick whatever your
stack offers, just make sure the code path is **absent** (or
trivially refuses to load) when the flag is off.

### 4. Keep the PR small

The planner reads the full PR diff. With a 5,000-line diff it will:

- generate too many journeys and hit `maxTestCases` before covering
  the actual change,
- author very generic tests that pass even when the change is broken,
- waste tokens on unchanged files that look "interesting" but were
  pulled in by a refactor.

Split refactors from behaviour changes and re-run on the focused PR.
A diff of a few hundred lines produces noticeably tighter test plans
and roughly halves the per-run token spend in our own measurements.

### 5. Watch the bill

- **Cap `maxTestCases`** on the workflow configuration (default 20,
  hard limit 100). Larger numbers blow up author-agent token usage
  roughly linearly.
- **Prefer `rerun-tests` over `regenerate-tests`** for flake
  investigation — it skips the LLM entirely.
- **Pick a cheap-but-capable model for the planner / author roles.**
  The runner role benefits from a strong reasoner because it
  interprets failures and decides on retries; the other two are
  mostly templated output and work fine on smaller models.

### Reference setup: ai-git-bot on itself

This very repository runs the E2E workflow on every PR. The bot
happens to be a Spring Boot / Java app, but the patterns map cleanly
to Node, Python, Go, Ruby, .NET, … — substitute the obvious
equivalents (`requirements.txt` / `package.json` for the build,
`Health` route for the readiness probe, framework-native config
binding for the feature flags). The reference files are:

- [`.github/workflows/preview.yml`](../.github/workflows/preview.yml) —
  the CI-action that deploys a slot-isolated preview to a single
  long-running host. Demonstrates idempotent wipe, throw-away
  in-memory database, deterministic encryption key, auto-login
  flags, health-endpoint readiness probe, 24 h slot GC, graceful
  process stop with `SIGKILL` fallback. The shell scripting is POSIX
  `sh` and the ideas (wipe → deploy → probe → callback) are entirely
  language-independent.
- [`src/main/java/org/remus/giteabot/admin/AutoLoginConfig.java`](../src/main/java/org/remus/giteabot/admin/AutoLoginConfig.java) —
  the auto-login filter that lets preview tests skip the login form.
  Gated by a single boolean flag so the production build does not
  even instantiate it. Equivalent shapes: a Node middleware behind
  `if (process.env.AUTO_LOGIN === 'true')`, a Django middleware
  behind `settings.AUTO_LOGIN`, a Rails initializer behind
  `Rails.env.preview?`.
- [`src/main/resources/application.properties`](../src/main/resources/application.properties) —
  the three `*.auto-login.*` properties (default `false`, opt-in via
  env vars in `preview.yml`). Mirror this layering — config-file
  default off, env-var on in preview — in your framework's native
  config system.
- [`src/main/java/org/remus/giteabot/prworkflow/e2e/workspace/PrTestWorkspaceManager.java`](../src/main/java/org/remus/giteabot/prworkflow/e2e/workspace/PrTestWorkspaceManager.java) —
  the hardened Playwright config the runner writes into every
  workspace: `workers: 1`, `fullyParallel: false`, `retries: 0`,
  `storageState: undefined`, `ignoreHTTPSErrors: true`. Mirror these
  settings if you author a custom runner for Cypress,
  pytest-playwright, k6, or any other framework — the goal is the
  same everywhere: serialise execution, do not retry silently, do
  not carry cookies across tests, do not fail on self-signed preview
  certs.

Reading those four files end-to-end is the fastest way to understand
what a "good" E2E setup looks like in practice. The mechanisms are
Spring-specific, but the **shapes** (idempotent deploy, opt-in test
mode, layered config, deterministic test runner) translate directly
to any web stack.

