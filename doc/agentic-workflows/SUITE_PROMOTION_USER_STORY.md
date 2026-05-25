# M7 — Suite promotion: concrete user story & benefits

> **Audience:** stakeholders / QA leads asking *"now that the bot
> generates E2E tests per PR, how do those tests stop being throwaway?"*
> **Companion:** [doc/PR_WORKFLOWS_E2E.md § Suite lifecycle modes](../PR_WORKFLOWS_E2E.md#suite-lifecycle-modes-m7)
> for the per-mode protocol, and
> [systemtest/README-suite-promotion.md](../../systemtest/README-suite-promotion.md)
> for a laptop reproduction.

---

## 1. The persona

**Lin** is the **QA Lead** at *Bauer Industrial Components*. Bauer's
web shop has decent unit-test coverage and *zero* automated
end-to-end coverage — every regression is found by a human clicking
through the checkout flow on a staging box. Lin tried twice to seed an
E2E suite from scratch; both times the suite rotted within a quarter
because nobody owned it and Playwright kept moving.

When Bauer adopted AI-Git-Bot's **Full-stack QA** workflow (M4), Lin
finally saw the bot author Playwright specs against the per-PR preview
deployment. The specs were *good* — most of them caught the
regressions a real human would have caught. Then PR closed → the
suite was deleted (ephemeral mode) and the value evaporated.

Lin's question: **how do I keep those generated tests?**

---

## 2. The pain before M7

With only `ephemeral` lifecycle:

1. Every generated suite gets deleted on PR close. **Test history is
   not auditable.**
2. The standard CI never runs the generated tests, so they don't
   catch regressions on `main`.
3. Lin would have to manually `git cherry-pick` the test files out of
   the bot's sandbox before PR close — a chore he won't do for every
   one of ~30 PRs/week.
4. Each new PR re-generates a similar suite from scratch — wasted
   tokens, redundant LLM work, no compounding value.

---

## 3. The user story

> **As** Lin the QA Lead
> **I want** the bot to automatically **promote** generated test suites
> into the repository (either immediately as a follow-up PR, or after
> the parent PR merges, or as a direct commit on the feature branch)
> **so that** the generated coverage stops evaporating and becomes part
> of the standard CI matrix that protects `main`.

### Acceptance criteria (all shipped in M7)

- [x] A new **`suiteLifecycle`** workflow param on `e2e-test` accepts
      `ephemeral` / `offer-as-pr` / `promote-on-merge` / `commit-to-pr`.
- [x] `offer-as-pr`: after a successful run the bot opens a follow-up
      PR `ai-tests/pr-{n}-r{runId} → feature-branch` carrying every generated
      `PrTestCase` under `tests/e2e/pr-{n}/`.
- [x] `promote-on-merge`: triggered by the PR-merged lifecycle hook;
      opens a follow-up PR `ai-tests/promoted-pr-{n}-r{runId} → default-branch`
      carrying the tests under `tests/e2e/`.
- [x] `commit-to-pr`: the tests are committed directly onto the feature
      branch — no follow-up PR.
- [x] **Idempotency**: `PrWorkflowRun.followUpPrNumber` is set on the
      first successful promotion; subsequent triggers (re-run,
      `@bot rerun-tests`, late merge events) recognise it and no-op.
- [x] **Conflict policy**: if the destination file exists, the bot
      appends `_2`, `_3`, … before the first dot (`login.spec.ts` →
      `login_2.spec.ts`); chosen names are listed in the follow-up PR
      description.
- [x] **Best-effort failure**: workspace / git / API failures degrade
      to a `❌ Promotion failed — …` comment on the parent PR. The
      parent run's terminal status is never rolled back.
- [x] **Teardown policy**: `ephemeral` + `commit-to-pr` suites are
      deleted on PR close (no longer needed). `offer-as-pr` /
      `promote-on-merge` suites are kept so the dashboard can correlate
      the parent run with the follow-up PR.
- [x] **Nightly retention GC**: `PromotedSuiteGarbageCollector`
      (`@Scheduled` cron, default 03:17 server-time) deletes the in-DB
      `PrTestSuite` rows once `PrWorkflowRun.finishedAt` is older than
      `prworkflow.e2e.promotion.retention` (default `P30D`) — the
      promoted-PR link on the run is preserved so the dashboard keeps
      surfacing "promoted as PR #N". Cases are removed via the existing
      `orphanRemoval=true` cascade. No webhook traffic to the four
      provider APIs required.

---

## 4. Concrete benefits

| Benefit | Why it matters to Lin |
|---|---|
| **No more throwaway tests** | The good tests survive PR close and start protecting `main`. |
| **Lin reviews tests like normal code** | The follow-up PR is a regular PR — code review, comment threads, branch-protection rules all apply. |
| **Auditable promotion trail** | `PrWorkflowRun.followUpPrNumber` links every promoted suite back to the run that generated it; the dashboard surfaces both PRs. |
| **No double-promotion** | Idempotency guard means re-runs / late merges never open duplicate PRs, and the per-run branch suffix avoids collisions with older promotion branches for the same parent PR. |
| **No silent overwrites** | Conflict suffixes (`login_2.spec.ts`) make sure existing tests are never clobbered. |
| **Mode per team, not per repo** | A team owning a stable area can pick `promote-on-merge`. A scratch-pad service can stay on `commit-to-pr`. A risk-averse team keeps `offer-as-pr` and reviews everything. |
| **No new infrastructure** | Reuses the existing `WorkspaceService` (same `git clone` / `git push` path the coding agent already uses) and the existing `RepositoryApiClient.createPullRequest` — works on Gitea, GitHub, GitLab, Bitbucket out of the box. |

---

## 5. Before / after

```
┌──────────────────── BEFORE M7 ────────────────────┐    ┌──────────────── AFTER M7 ───────────────┐
│                                                   │    │                                          │
│  Bot generates 4 specs on PR #7.                  │    │  Bot generates 4 specs on PR #7.        │
│  Suite report posted to PR comment.               │    │  Suite report posted to PR comment.     │
│  PR closes → suite deleted.                       │    │  Follow-up PR #4242 opens on            │
│  Tests never run on main.                         │    │  `ai-tests/pr-7-r101 → feature/login`   │
│  Next quarter Lin still has no coverage.          │    │  with the 4 specs.                       │
│                                                   │    │  Lin reviews + merges → tests live      │
│                                                   │    │  on main → CI runs them on every PR.    │
└───────────────────────────────────────────────────┘    └──────────────────────────────────────────┘
```

---

## 6. Sequence — `offer-as-pr` happy path

```
PR opened ──▶ E2ETestWorkflow ──▶ Planner / Author / Runner
                                       │
                                       ▼ outcome = PASSED
                       SuitePromotionService.promote(bot, run, suite,
                                                    owner, repo,
                                                    featureBranch)
                                       │
                                       ▼
              WorkspaceService.prepareWorkspace(repo, featureBranch)
              Files.write tests/e2e/pr-{n}/*.spec.ts
              WorkspaceService.commitAndPush(branch=ai-tests/pr-{n}-r{runId})
              RepositoryApiClient.createPullRequest(title, body,
                                                   head=ai-tests/pr-{n}-r{runId},
                                                   base=featureBranch)
                                       │
                                       ▼
                       PrWorkflowRun.followUpPrNumber = 4242
                       Comment on parent PR:
                         "✅ Opened follow-up PR #4242 …"
```

For `promote-on-merge` the trigger is the PR-closed lifecycle hook
filtering on `merged == true`; everything else is identical except the
base branch (repository default branch) and the target directory
(`tests/e2e/` without the per-PR subfolder).

---

## 7. When to pick which mode

| Choose | When… |
|---|---|
| **`ephemeral`** (default) | The bot is still being trialled, or you don't trust the generated tests yet. Keeps the codebase pristine. |
| **`offer-as-pr`** | You want a *separate* code-review loop for every promoted suite — typical for established repos with branch protection and CODEOWNERS. |
| **`promote-on-merge`** | You want the promoted tests to land at the same logical "time" as the parent feature — minimises noise (no follow-up PR open while the feature is still in review). |
| **`commit-to-pr`** | Small / solo repositories where every extra PR is friction. Forces the human reviewer to see the tests as part of the feature PR diff. |

---

## 8. Out of scope (deliberately deferred)

- **Cross-PR de-duplication** of identical specs (the same `login.spec.ts`
  being regenerated for every PR). The conflict-suffix policy handles
  this safely but does not deduplicate semantically.
- **GitHub Actions-aware promotion** — currently the follow-up PR uses
  the standard `RepositoryApiClient.createPullRequest` surface; future
  M6 (`CI_ACTION`) work can layer a "trigger a workflow_dispatch run"
  hook on top.

