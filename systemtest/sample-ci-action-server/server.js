/*
 * Sample CI-Action server for AI-Git-Bot M6 (CiActionTriggerStrategy / CiActionPoller).
 *
 * Implements the *minimal* subset of the GitHub Actions REST API that
 * GitHubApiClient.dispatchWorkflow / getWorkflowRun / getWorkflowRunOutputs
 * exercise — so the bot can be pointed at this server (via its
 * `github.api-base-url` property) and drive an end-to-end CI_ACTION
 * deployment scenario from a laptop without touching real github.com.
 *
 * Endpoints:
 *   POST /repos/:owner/:repo/actions/workflows/:workflow/dispatches
 *        body: { ref, inputs? }    -> 204 No Content, creates an
 *                                     in-memory run that transitions
 *                                     from "in_progress" to "completed"
 *                                     after RUN_DURATION_MS.
 *
 *   GET  /repos/:owner/:repo/actions/workflows/:workflow/runs
 *        ?event=workflow_dispatch&branch=<name>&per_page=10
 *        -> { workflow_runs: [ { id, head_branch, event } ] }
 *        Matches the GitHub Actions REST filter shape so the bot's
 *        race-safe run-id resolver (see GitHubApiClient.resolveNewRunId
 *        in M6 fix #7) can scope to a specific branch/event.
 *
 *   GET  /repos/:owner/:repo/actions/runs/:run_id
 *        -> { status, conclusion }
 *
 *   GET  /healthz                                 -> { ok: true }
 *
 * Operator helpers (not part of the GitHub API — for the walkthrough):
 *   GET  /_admin/runs                             -> list all in-memory runs
 *   POST /_admin/fail-next                        -> next dispatch resolves to "failure"
 *   POST /_admin/reset                            -> clear all runs
 *
 * Env knobs:
 *   PORT             default 8091
 *   RUN_DURATION_MS  default 5000  (how long a run stays "in_progress")
 *   FAIL_ALL         default "false" — when "true", every run completes
 *                    with conclusion "failure" (handy for the failure-path
 *                    smoke test without touching /_admin/fail-next).
 */

const express = require("express");

const PORT = parseInt(process.env.PORT || "8091", 10);
const RUN_DURATION_MS = parseInt(process.env.RUN_DURATION_MS || "5000", 10);
const FAIL_ALL = String(process.env.FAIL_ALL || "false").toLowerCase() === "true";

const app = express();
app.use(express.json({ limit: "256kb" }));

// In-memory state: runId -> { owner, repo, workflow, ref, inputs, startedAt, forceFail }
const runs = new Map();
let nextRunId = 1_000_001;
let failNext = false;

function logLine(...args) {
  console.log(new Date().toISOString(), ...args);
}

// ---- GitHub-Actions-compatible endpoints ----

app.post(
  "/repos/:owner/:repo/actions/workflows/:workflow/dispatches",
  (req, res) => {
    const { owner, repo, workflow } = req.params;
    const ref = req.body && req.body.ref;
    const inputs = (req.body && req.body.inputs) || {};
    if (!ref) {
      return res.status(422).json({ message: "missing 'ref' in body" });
    }
    const forceFail = FAIL_ALL || failNext;
    failNext = false;
    const runId = nextRunId++;
    runs.set(runId, {
      runId,
      owner,
      repo,
      workflow,
      ref,
      inputs,
      startedAt: Date.now(),
      forceFail,
    });
    logLine(
      `dispatch  ${owner}/${repo} workflow=${workflow} ref=${ref} ` +
        `inputs=${JSON.stringify(inputs)} -> run id=${runId} ` +
        `(forceFail=${forceFail})`
    );
    return res.status(204).end();
  }
);

app.get(
  "/repos/:owner/:repo/actions/workflows/:workflow/runs",
  (req, res) => {
    const { owner, repo, workflow } = req.params;
    const wantEvent = req.query.event; // optional, e.g. "workflow_dispatch"
    const wantBranch = req.query.branch; // optional
    const perPage = Math.min(parseInt(req.query.per_page || "10", 10), 50);
    // Return the latest matching runs for that (owner, repo, workflow) tuple.
    // Apply the same event=workflow_dispatch + branch=… filter shape the
    // real GitHub Actions API exposes — exercised by GitHubApiClient's
    // correlation logic added in fix #7 of the M6 follow-up.
    const matches = [];
    for (const run of runs.values()) {
      if (run.owner !== owner || run.repo !== repo || run.workflow !== workflow) continue;
      if (wantEvent && wantEvent !== "workflow_dispatch") continue;
      const headBranch = headBranchFor(run.ref);
      if (wantBranch && wantBranch !== headBranch) continue;
      matches.push({ id: run.runId, head_branch: headBranch, event: "workflow_dispatch" });
    }
    matches.sort((a, b) => b.id - a.id);
    return res.json({
      total_count: matches.length,
      workflow_runs: matches.slice(0, perPage),
    });
  }
);

function headBranchFor(ref) {
  if (!ref) return null;
  if (ref.startsWith("refs/heads/")) return ref.substring("refs/heads/".length);
  if (ref.startsWith("refs/")) return null; // pull/tag/etc.
  return ref;
}

app.get("/repos/:owner/:repo/actions/runs/:runId", (req, res) => {
  const { owner, repo, runId } = req.params;
  const run = runs.get(parseInt(runId, 10));
  if (!run || run.owner !== owner || run.repo !== repo) {
    return res.status(404).json({ message: "Not Found" });
  }
  const elapsed = Date.now() - run.startedAt;
  if (elapsed < RUN_DURATION_MS) {
    return res.json({
      id: run.runId,
      status: "in_progress",
      conclusion: null,
    });
  }
  const conclusion = run.forceFail ? "failure" : "success";
  return res.json({
    id: run.runId,
    status: "completed",
    conclusion,
  });
});

// ---- Operator helpers (admin surface, not in real GitHub API) ----

app.get("/_admin/runs", (_req, res) => {
  const now = Date.now();
  res.json(
    Array.from(runs.values()).map((r) => ({
      ...r,
      ageMs: now - r.startedAt,
      done: now - r.startedAt >= RUN_DURATION_MS,
    }))
  );
});

app.post("/_admin/fail-next", (_req, res) => {
  failNext = true;
  logLine("admin: next dispatch will resolve to FAILURE");
  res.json({ failNext: true });
});

app.post("/_admin/reset", (_req, res) => {
  runs.clear();
  failNext = false;
  logLine("admin: state reset");
  res.json({ ok: true });
});

app.get("/healthz", (_req, res) => {
  res.json({
    ok: true,
    runs: runs.size,
    failNext,
    failAll: FAIL_ALL,
    runDurationMs: RUN_DURATION_MS,
  });
});

app.listen(PORT, () => {
  logLine(
    `sample-ci-action-server listening on :${PORT} ` +
      `(RUN_DURATION_MS=${RUN_DURATION_MS}, FAIL_ALL=${FAIL_ALL})`
  );
});

