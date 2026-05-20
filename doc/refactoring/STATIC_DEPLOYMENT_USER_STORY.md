# M3 — `STATIC` Deployment: concrete user story & benefits

> **Audience:** stakeholders / frontend leads asking *"why would I pick
> `StaticPreviewUrlStrategy` over `WEBHOOK`, `MCP` (M5), or `CI_ACTION`
> (M6)?"*
> **Companion:** [doc/PR_WORKFLOWS.md → Deployment targets](../PR_WORKFLOWS.md#deployment-targets-m3)
> for the protocol details.

---

## 1. The persona

**Marco** is the **Frontend Lead** at *Initech Web*. Initech's marketing
site is a Next.js app deployed on **Vercel**. Vercel already creates a
preview deployment for *every* pull request, automatically, and exposes
each one at a predictable URL:

```
https://initech-web-git-<branch-slug>-initech.vercel.app
```

The platform team also runs a Storybook on **Netlify** with the same
"preview-per-PR" model and the URL template
`https://deploy-preview-<prNumber>--initech-storybook.netlify.app`.

Marco doesn't *want* a deploy step in the bot at all — Vercel and
Netlify already did it. He just needs the bot to **know the URL** so the
E2E workflow can drive Playwright against it.

---

## 2. The pain before M3

Without a "URL-only" deployment strategy Marco had two options, neither
good:

1. **Force-fit `WEBHOOK`.** Stand up a tiny shim that resolves the URL
   template and POSTs it back to the bot's callback endpoint. ~50 lines
   of code that exist purely to copy a string Marco already knows.
2. **Hard-code the URL into the bot prompt.** Works for one project,
   collapses the moment a second app needs a different template.

Neither preserves the *zero-extra-infra* promise that made Vercel review
deploys attractive in the first place.

---

## 3. The user story

> **As** Marco the Frontend Lead
> **I want** AI-Git-Bot to compute the preview URL from a **per-bot
> template** that interpolates PR metadata (`{prNumber}`, `{branch}`,
> `{sha}`)
> **so that** existing Vercel / Netlify / GitLab review-app deployments
> are reused as-is, without a custom callback service, without a CI
> change, and without leaking platform secrets into the bot.

### Acceptance criteria (all shipped in M3)

- [x] An operator can pick **`STATIC`** in *Deployment Targets → New*
      and set a `urlTemplate` containing `{prNumber}`, `{branch}`,
      `{sha}`, `{repoOwner}`, `{repoName}` placeholders.
- [x] Optional **`healthzPath`** (e.g. `/api/health`) — the bot probes
      `urlTemplate + healthzPath` until HTTP 2xx before marking the
      deployment `READY`.
- [x] `StaticPreviewUrlStrategy.awaitsCallback() == false` — the bot
      polls the healthz URL on its existing schedule (no inbound
      callback channel needed).
- [x] Branch-name slugification matches Vercel / Netlify conventions
      (lowercase, non-alphanumeric → `-`, max length).
- [x] No teardown action by default — the upstream platform owns the
      lifecycle. Operators can flip `teardownOnPrClose=true` if they
      want the bot to fire a webhook on PR close.

---

## 4. Concrete benefits

| Benefit                                | Why it matters to Marco                                                                                                                       |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| **Zero extra infrastructure**          | No bridge service, no Jenkins job, no MCP server. The URL is computed in-process.                                                              |
| **Reuses existing platform**           | Vercel / Netlify / GitLab review apps already do the hard work — Marco keeps his existing CDN, caching, env-var injection, and rollback story. |
| **No secrets in the bot**              | The bot never talks to Vercel's API. Tokens, billing keys, project IDs all stay in Vercel.                                                     |
| **Cheapest possible coupling**         | Switching providers later means swapping one URL template, not rewriting a deploy script.                                                      |
| **Healthz-gated readiness**            | The bot waits for the preview to be reachable, so flaky "deployed-but-not-yet-warm" timing windows don't show up as Playwright timeouts.       |
| **Multi-app per bot**                  | Marco can run two `STATIC` targets — one for the Vercel marketing site, one for the Netlify Storybook — and pick per workflow configuration.   |

---

## 5. Before / after

```
┌──────────────────── BEFORE M3 ────────────────────┐    ┌──────────────── AFTER M3 ───────────────┐
│                                                   │    │                                          │
│  Manual: developer copy/pastes the Vercel         │    │  AI-Git-Bot computes URL from template, │
│  preview URL into a PR comment so the bot can     │    │  probes /api/health, runs E2E tests.    │
│  pick it up.                                      │    │                                          │
│                                                   │    │  Manual hand-offs per PR: 0              │
│  Net-new infra:        none (already on Vercel)   │    │  Net-new infra:        none              │
│  Hand-offs per PR:     1 (copy/paste)             │    │  Bridge service:        none             │
└───────────────────────────────────────────────────┘    └──────────────────────────────────────────┘
```

---

## 6. Sequence (happy path)

```
PR opened ──▶ E2ETestWorkflow ──▶ StaticPreviewUrlStrategy.trigger()
                                       │
                                       ▼
                       Render urlTemplate against PR metadata
                       e.g. "https://initech-web-git-{branch}-initech.vercel.app"
                            → "https://initech-web-git-feat-login-initech.vercel.app"
                                       │
                                       ▼
                       Poll {url}{healthzPath} until 2xx (or timeout)
                                       │
                                       ▼
                       Mark run READY with previewUrl
                                       │
                                       ▼
                       Planner → Author → Runner against the preview URL
                                       │
                                       ▼
                       PR closed ──▶ no-op (Vercel handles teardown)
```

---

## 7. When to choose `STATIC`

| Choose `STATIC` when…                                                       | Look elsewhere when…                                                            |
|-----------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| Your platform already creates a preview per PR (Vercel, Netlify, GitLab review apps, Render PR previews, Argo Rollouts preview). | Deploys are on-demand / expensive — pre-deploying every PR is wasteful. Use `WEBHOOK` / `CI_ACTION` / `MCP`. |
| The preview URL is computable from PR metadata.                              | The URL is unpredictable (random GUIDs, dynamic ports). Use a callback strategy. |
| You don't need lifecycle hooks (upstream owns deploy + teardown).            | You need explicit teardown control. Use `WEBHOOK` or `MCP`.                      |
| You want the absolute simplest configuration.                                | You need to pass deploy *parameters* (branch options, feature flags). Use a callback strategy. |

