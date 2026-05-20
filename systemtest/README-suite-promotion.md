# M7 — Suite promotion systemtest walkthrough

End-to-end recipe demonstrating the three M7 "non-ephemeral" lifecycle
modes on a real Gitea instance. Companion to
[`doc/refactoring/SUITE_PROMOTION_USER_STORY.md`](../doc/refactoring/SUITE_PROMOTION_USER_STORY.md)
(the *why*) and
[`doc/PR_WORKFLOWS_E2E.md` § Suite lifecycle modes](../doc/PR_WORKFLOWS_E2E.md#suite-lifecycle-modes-m7)
(the per-mode protocol).

> Reuses the existing M4 sample stack (sample login app + Gitea + the bot)
> and the existing `Full-stack QA` workflow configuration that Flyway
> `V18` seeded. No new infrastructure required.

## 1. Boot the stack

```bash
# Terminal A — Gitea on :3000 with a pre-seeded acme/demo repo.
docker compose -f systemtest/docker-compose-local-gitea.yml up

# Terminal B — sample login app on :3030 (target for E2E tests).
docker compose -f systemtest/docker-compose-e2e-sample.yml up --build

# Terminal C — the bot (host build).
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

## 2. Pick a lifecycle mode

In the bot's web UI, open
**Workflow configurations → Full-stack QA → e2e-test params** and set:

| Mode | Value to enter | Outcome on the parent PR |
|------|----------------|--------------------------|
| **ephemeral** (default) | leave blank or `ephemeral` | Suite is deleted on PR close. Use as a sanity baseline. |
| **offer-as-pr** | `offer-as-pr` | After a successful run the bot opens a follow-up PR `ai-tests/pr-{n}` against the feature branch with the generated tests under `tests/e2e/pr-{n}/`. |
| **promote-on-merge** | `promote-on-merge` | The follow-up PR is opened only *after* the parent PR merges, targeting the default branch and writing under `tests/e2e/`. |
| **commit-to-pr** | `commit-to-pr` | No follow-up PR — the tests are committed directly onto the feature branch. |

Save the configuration. Make sure the bot has:
- a deployment target (`STATIC` pointing at `http://sample-e2e-app:3000`
  is the simplest — set `urlTemplate` to that literal URL),
- a recent AI integration with enough budget to generate a couple of
  Playwright specs.

## 3. Trigger the workflow

Open a PR in the `acme/demo` repo touching anything UI-related; the bot
runs the planner → author → runner pipeline and posts the suite report
to the PR.

### `offer-as-pr` (immediate)
After the run finishes (PASSED), look at the parent PR:

```
🤖 Suite promotion (offer-as-pr)

✅ Opened follow-up PR #4242 on branch `ai-tests/pr-7` with 3 generated
test file(s).
```

The follow-up PR `ai-tests/pr-7 → feature/login` contains:
```
tests/
└── e2e/
    └── pr-7/
        ├── login.spec.ts
        ├── invalid-credentials.spec.ts
        └── logout.spec.ts
```

### `commit-to-pr` (immediate, no PR)
Same as above, but no follow-up PR is opened — `git log feature/login`
shows a single extra commit `test(e2e): add generated tests for PR #7`
authored by `AI-Git-Bot`.

### `promote-on-merge` (deferred)
Nothing happens after the run. **Merge** the parent PR to trigger
promotion:

```
🚀 Promotion fired by PR close + merged=true
🤖 Promote E2E tests from merged PR #7

✅ Opened follow-up PR #5050 on branch `ai-tests/promoted-pr-7` with
3 generated test file(s).
```

The new PR targets `main` and writes the tests directly under
`tests/e2e/` so the standard CI matrix picks them up.

## 4. Verify the failure paths

### Idempotency
Re-run the workflow on the same PR while the follow-up PR is still
open. The bot reads `pr_workflow_runs.follow_up_pr_number`, recognises
the run has already been promoted, and posts:

```
🤖 Suite promotion (offer-as-pr)

ℹ️ Follow-up PR #4242 already exists for this run.
```

No new branch, no new PR.

### Conflict policy
On a second PR touching the same feature, the bot would try to write
`tests/e2e/pr-9/login.spec.ts`. If a file with that name already exists
in the branch, the suffix policy kicks in:

```
✅ Opened follow-up PR #5151 on branch `ai-tests/pr-9` with 3 generated
test file(s).
   tests/e2e/pr-9/login_2.spec.ts
   tests/e2e/pr-9/invalid-credentials.spec.ts
   tests/e2e/pr-9/logout.spec.ts
```

### Push failure
Stop Gitea (`docker compose down` in terminal A) and re-trigger the
workflow. The suite-result comment still appears, but promotion
degrades cleanly:

```
🤖 Suite promotion (offer-as-pr)

❌ Promotion failed — Workspace preparation failed: Failed to clone
repository: …
```

The parent run's terminal status is unchanged — promotion failures
never roll back the original workflow.

## 5. Cleanup

```bash
docker compose -f systemtest/docker-compose-e2e-sample.yml down -v
docker compose -f systemtest/docker-compose-local-gitea.yml down -v
```

