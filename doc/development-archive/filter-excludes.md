# Feature: Exclude non-meaningful files from PR review diff

## Feature Request

### Use Case
Some files in a PR are not meaningful for code review — e.g. `package-lock.json`,
`yarn.lock`, `*.min.js`, `*.lock`, or generated files. Including them wastes
context-window tokens, inflates cost, and can distract the model from real code
changes.

### Proposed Behavior
- Configurable with an exclude list in `application.properties`.
- A new workflow parameter (e.g. `excludedFilePatterns`) on the PR Review
  workflow configuration.
- Accepts a comma-separated list of glob patterns or file extensions
  (e.g. `*.lock`, `*.min.js`, `package-lock.json`).
- Before the diff is passed to the AI, any diff hunks whose file path matches an
  excluded pattern are stripped out.

### Why It Is Valuable
- Reduces token usage and cost.
- Improves review quality by keeping the model focused on meaningful changes.
- No code change required by end users — purely a configuration-time decision.

### Current Behavior
The raw diff from `getPullRequestDiff()` is passed to the AI without any file
filtering. There is no configuration hook to exclude files.

---

## Architecture Context (traced)

- Raw diff enters at `RepositoryApiClient.getPullRequestDiff()` and is consumed
  unfiltered in `CodeReviewService` (`reviewPullRequest` at line 73, the PR
  update path, `handleReviewSubmitted` at line 399) and separately in
  `AgentReviewService` (line 139).
- Workflow params flow: `ReviewWorkflow.paramsSchema()` declares fields keyed by
  the `ReviewParam` enum -> `WorkflowSelectionService.resolveParams()` returns a
  `Map<String,Object>` -> `ReviewWorkflow.run()` reads them (`intParam` helper)
  -> passes through `CodeReviewServiceFactory.create(...)` ->
  `CodeReviewService` constructor. Schema defaults are sourced from an injected
  `@ConfigurationProperties` bean (`ReviewChunkingProperties`, prefix
  `review.chunking`).
- Per-file diff boundaries are already parsed elsewhere via regex
  `^diff --git a/(.+?) b/(.+?)$` in `ChangedFileContentsEnricher` — reusable
  pattern.
- Params are stored as JSON in `workflow_selection_params` — no DB/Flyway change
  needed.

---

## Implementation Plan

### Goal
Strip diff hunks for excluded file paths (globs / extensions) before the diff
reaches the AI, configurable via an application-properties default plus a
per-workflow `excludedFilePatterns` parameter on the PR Review workflow.

### Components to create / modify
- [ ] NEW util: `DiffFileFilter` (`org.remus.giteabot.review`) — pure, stateless
      component. `String filter(String rawDiff, List<String> patterns)`: splits
      the unified diff on `diff --git` boundaries, drops any per-file segment
      whose new-path (`b/...`) matches an excluded pattern, re-joins the rest.
      Also exposes pattern parsing (comma-separated string -> List) and a
      `PathMatcher`-based glob matcher supporting exact names
      (`package-lock.json`), suffix globs (`*.lock`, `*.min.js`), and nested
      globs (`**/generated/**`). Returns the input unchanged when patterns are
      empty.
- [ ] MODIFY `ReviewConfigProperties` (prefix `review.context`) — add
      `private String excludedFilePatterns = "";` as the app-level default
      (single source of truth for the default). Env-overridable.
- [ ] MODIFY `ReviewParam` — add
      `EXCLUDED_FILE_PATTERNS("excludedFilePatterns")`.
- [ ] MODIFY `ReviewWorkflow`:
      - inject `ReviewConfigProperties` (for the schema default),
      - add a `TEXT`/`STRING` `WorkflowParamField` in `paramsSchema()`
        (default = `reviewConfig.getExcludedFilePatterns()`, help text with
        examples),
      - resolve it in `run()` (new `strParam` helper mirroring
        `AgentReviewWorkflow.strParam`) and pass through to the factory.
- [ ] MODIFY `CodeReviewServiceFactory.create(...)` — add the
      `excludedFilePatterns` parameter (NO overload; update all call sites),
      pass to the `CodeReviewService` constructor.
- [ ] MODIFY `CodeReviewService` — store patterns + `DiffFileFilter`; apply the
      filter to every `getPullRequestDiff(...)` result (`reviewPullRequest`, the
      PR-update chat path, `handleReviewSubmitted` context). Filter once, right
      after fetch, before chunking/enrichment.
- [ ] Config: document `review.context.excluded-file-patterns=${...:}` in
      `application.properties` (base file only — not the docker profile).
- [ ] Tests: `DiffFileFilterTest` (glob/extension/exact/multi-file/empty),
      extend `CodeReviewServiceTest` + `ReviewWorkflowTest` for the new param
      wiring and filtered-diff behaviour, update factory call sites in tests.

### Sequence
1. Write `DiffFileFilter` + unit tests (RED->GREEN in isolation).
2. Add default property to `ReviewConfigProperties`.
3. Add `ReviewParam` enum constant.
4. Thread the param through `ReviewWorkflow` -> factory -> `CodeReviewService`,
   updating ALL call sites in one pass (main + test).
5. Apply the filter inside `CodeReviewService` diff paths.
6. Document the property; run `mvn -o test -Denforcer.skip=true`.

### Assumptions / decisions to confirm
- Matching is against the diff's `b/` (new) path; deletions (`b/` = `/dev/null`)
  keyed on the `a/` path.
- SCOPE: this covers the `review` workflow only. `AgentReviewService` /
  `AgentReviewWorkflow` also consume the raw diff
  (`agentreview/AgentReviewService.java:139`) — per the skill's "check siblings"
  rule this is flagged, but left out of this slice unless wanted. The
  `DiffFileFilter` util is written reusable so adding it there later is a 3-line
  change.
- Empty/blank pattern list => zero behaviour change (diff passed through).

---

## ADR-1: Where to apply diff file-exclusion filtering

**Status:** Proposed

**Context**
The raw diff is fetched via `RepositoryApiClient.getPullRequestDiff()` and
consumed by two workflows. We need to strip excluded files before the AI sees
them, driven by a per-workflow config parameter. The filter could live at three
layers.

**Options Considered**
1. **Inside each `RepositoryApiClient` implementation**
   (Gitea/GitHub/GitLab/Bitbucket)
   - Pros: single fetch point; every consumer benefits automatically.
   - Cons: the API clients are provider-plumbing and have no access to
     per-workflow config; would need config pushed down into 4 classes; breaks
     separation (clients shouldn't know about review policy).
2. **A reusable `DiffFileFilter` util invoked by the consuming service**
   (CodeReviewService now, AgentReviewService later)
   - Pros: config stays at the workflow layer where params already resolve;
     pure/testable util; opt-in per workflow; matches existing param-threading
     pattern (chunking ints flow the same way).
   - Cons: each consumer must call it explicitly (one line per diff fetch).
3. **New `ContextEnricher`-style step**
   - Pros: fits the enrichment pipeline.
   - Cons: enrichers ADD context; this REMOVES from the primary diff, which is
     not enrichment — wrong abstraction. Enrichers also run after the diff is
     already chunked/used.

**Decision**
Option 2 — a stateless `DiffFileFilter` util applied inside the consuming
service right after `getPullRequestDiff()`. It keeps provider clients dumb,
places config resolution at the workflow layer (consistent with how
`maxDiffChunks` etc. are already threaded), and is trivially unit-testable.

**Consequences**
- Each new diff consumer must remember to call the filter (mitigated: only two
  today; util is shared).
- No DB migration — the param persists as JSON in `workflow_selection_params`.
- Default lives once in `ReviewConfigProperties`; the workflow schema reads from
  it, so ops can set a global default and override per-bot in the UI.
