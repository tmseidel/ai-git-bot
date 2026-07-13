# Full-Stack QA (E2E Tests)

> Workflow key: **`e2e-test`** · Opt-in per bot.

## The problem it solves

Unit tests prove functions work in isolation; they don't prove a user can
actually log in and check out. **Full-Stack QA** generates end-to-end browser
tests (Playwright) for the change in a pull request, runs them against a live
preview of that PR, and posts the results back — so real user journeys are
exercised on every change, not just at release time.

> **Needs a preview environment.** This workflow runs its tests against a live,
> per-PR preview of your app. Setting up how that preview is produced is done
> separately from this workflow guide and is currently being reworked. Without a
> preview environment configured for the bot, the workflow skips with a clear
> comment on the PR. Everything below describes the workflow itself.

## What it does

1. Obtains the per-PR preview environment.
2. Plans which user journeys to test from the PR change.
3. Writes runnable Playwright tests for those journeys.
4. Runs them against the preview and posts a Markdown summary on the PR:

   ```markdown
   ## E2E Test Run for PR #42

   **Framework:** playwright
   **Preview environment:** https://preview-42.example.com
   **Outcome:** ❌ FAILED (1/2 passed)

   | Test | Status | Duration |
   | --- | --- | --- |
   | Sign-in happy path | ✅ PASSED | 1.23s |
   | Add to cart and pay | ❌ FAILED | 4.57s |
   ```

5. On PR close, cleans up the preview and any temporary test suites.

## Settings

Set these on **System settings → Workflow configurations → Workflows → E2E
Tests**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `framework` | enum | `playwright` | Test framework. Only `playwright` is well-tested; `pytest`, `k6`, `cypress` are experimental. |
| `maxRetries` | integer | `1` | Per-test retry budget. A test that passes only after a retry is tagged FLAKY. Capped at 5. |
| `maxTestCases` | integer | `20` | Cost guard on the number of generated tests. Capped at 100. |
| `suiteLifecycle` | enum | `ephemeral` | What happens to the generated tests after the run — see below. |
| `promotionThresholdPercent` | integer | `100` | Minimum percent of tests that must pass before a suite is kept/promoted. Lower it (e.g. `80`) to keep partially-green suites. |

## Running it

- **Automatically** on PR open / update, when enabled on the bot.
- **On command** in a PR comment:
  - `@bot rerun-tests` — re-run the **existing** tests from the last suite. No
    AI call — fast, deterministic, and free. Use it for suspected flakiness.
  - `@bot regenerate-tests [feedback]` — regenerate the whole suite. Any text
    after the command is passed to the planner as guidance, e.g.
    `@bot regenerate-tests use data-testid selectors and the login URL is /auth/sign-in`.

<a id="suite-lifecycle-modes"></a>
## Keeping the generated tests

By default the generated suite is thrown away when the PR closes. The
`suiteLifecycle` setting lets you keep it instead:

| Mode | What happens |
|---|---|
| `ephemeral` *(default)* | Suite is deleted on PR close. |
| `commit-to-pr` | Tests are committed directly onto the PR branch. |
| `offer-as-pr` | A follow-up PR is opened with the tests, for separate review. |
| `promote-on-merge` | When the PR merges, a follow-up PR adds the tests to the default branch so they join standard CI. |

`promotionThresholdPercent` decides how green a suite must be to be kept:
`100` keeps only fully-passing suites; lowering it lets you keep the valuable
working tests from a suite that has a few bad cases you'll fix or delete during
review. Suites that errored or were skipped are never kept.

## The agent prompts

The planning / authoring / running steps use operator-editable prompts under
**System settings → System prompts** (E2E Planner, E2E Author, E2E Runner). Edit
them to steer *how* the tests are written and run; the technical execution
details are handled by the software and are not part of these prompts.

## Enabling it

1. Make sure the bot has a preview environment configured (see the note at the
   top).
2. Open **System settings → Workflow configurations → Workflows**, or use the
   seeded **`Full-stack QA`** configuration which pre-enables `review` +
   `e2e-test`.
3. Assign the configuration to the bot.

It only runs for bots that have it explicitly enabled.

---

## Tips for reliable, low-cost runs

LLM-authored E2E tests are rarely 100% correct on the first try. Treat the
generated suite as a **regression-test baseline** you refine, not as ground
truth. These tips come from running this workflow daily on the AI-Git-Bot
codebase itself and dramatically improve both success rate and cost.

### Make your preview deterministic

Flaky E2E tests almost always trace back to a non-deterministic preview
environment, not the test framework. For each preview:

- **Start clean.** Wipe the previous build, working directory and any process
  holding the port. Don't rely on "the new build will just overwrite".
- **Use a throw-away database.** In-memory or per-PR databases give every boot a
  fresh, migration-driven schema — no shared state with the previous run.
- **Pin deterministic secrets for previews.** A random key per boot makes seeded
  encrypted data unreadable and hides real failures behind decryption noise.
- **Wait for readiness, don't sleep.** Probe a health endpoint until the app is
  actually up before running tests.

### Teach the planner how to reach the feature

Customise the bot's E2E system prompts so the planner/author know how to:

- **Log in** — ship a known test account, or a preview-only auto-login bypass.
- **Reach the feature** — give exact URL paths so the bot doesn't invent wrong ones.
- **Use stable selectors** — recommend `data-testid` / `aria-label` / ARIA roles
  instead of fragile CSS selectors.
- **Handle seed data** — document seeded fixtures, or ask the planner to create
  what it needs.

A short, app-specific addendum (a few hundred tokens) is worth more than
rewording the high-level persona.

### Consider a "test mode" for your app

Most apps benefit from opt-in, default-off test toggles: auto-login, disabled
rate-limits/captcha/2FA, a frozen clock, minimal seeded fixtures, and verbose
error responses. Gate each behind an explicit flag that is absent in production.

### Keep the PR small

The planner reads the whole diff. A 5,000-line diff produces too many, too
generic tests and wastes tokens on unchanged files. Split refactors from
behaviour changes; a few-hundred-line diff yields much tighter test plans.

### Watch the bill

- Cap `maxTestCases` (default 20).
- Prefer `@bot rerun-tests` over `@bot regenerate-tests` for flake investigation
  — it skips the AI entirely.
- A cheap model is fine for planning/authoring; the run/analysis step benefits
  most from a stronger reasoner.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [Unit Tests](PR_WORKFLOWS_UNIT_TEST.md) — the unit-level testing sibling (no
  preview needed).
