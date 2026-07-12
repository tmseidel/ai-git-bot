package org.remus.giteabot.prworkflow.readmesync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.BranchRefs;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowCancelledException;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Core business logic of the readme-sync workflow. Not a Spring singleton —
 * instances are created per-bot by {@link ReadmeSyncServiceFactory} with the
 * bot's own {@link AiClient} and {@link RepositoryApiClient}, mirroring
 * {@code UnitTestService}.
 *
 * <p>The flow is: clone the PR head branch, hand the diff + the current content
 * of the in-scope Markdown documentation to the {@link ReadmeSyncAgent} (which
 * applies documentation changes directly into the checkout via the guarded
 * {@code doc-write} / {@code doc-delete} tools), enforce the documentation
 * scope again as a pre-commit guard, apply the configured
 * {@link SuiteLifecycleMode lifecycle} and post a single Markdown summary
 * comment.</p>
 *
 * <p>Unlike the E2E / unit-test workflows there is no persisted suite entity,
 * so no database migration is required — the set of changed documentation
 * files is tracked in the {@link ReadmeSyncToolContext} and the lifecycle
 * promotion is driven off the live workspace diff.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ReadmeSyncService {

    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 60_000;
    static final int MAX_DOC_CONTEXT_CHARS = 120_000;
    static final int MAX_DOC_FILES_IN_CONTEXT = 40;
    static final int MAX_SINGLE_DOC_CHARS = 20_000;

    private static final String GIT_AUTHOR_NAME = "AI Agent";
    private static final String GIT_AUTHOR_EMAIL = "ai-agent@bot.local";

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final SystemPrompt systemPrompt;
    private final WorkspaceService workspaceService;
    private final ReadmeSyncAgent agent;

    /** Inputs resolved from the workflow params by {@link ReadmeSyncWorkflow}. */
    public record Request(PrWorkflowContext context,
                          List<String> includePatterns,
                          int maxToolRounds,
                          SuiteLifecycleMode lifecycleMode,
                          String userGuidance) {
    }

    /** Terminal result the workflow maps onto a {@code WorkflowResult}. */
    public record Result(Status status, String summary) {
        public enum Status { SUCCESS, FAILED, SKIPPED }

        public static Result success(String s) { return new Result(Status.SUCCESS, s); }
        public static Result failed(String s)  { return new Result(Status.FAILED, s); }
        public static Result skipped(String s) { return new Result(Status.SKIPPED, s); }
    }

    public Result run(Request request) {
        PrWorkflowContext context = request.context();
        WebhookPayload payload = context.payload();
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        long prNumber = resolvePrNumber(payload);
        String prTitle = payload.getPullRequest() == null ? null : payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest() == null ? null : payload.getPullRequest().getBody();

        if (request.includePatterns().isEmpty()) {
            postComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderSkipped(prNumber,
                    "no documentation include patterns are configured — nothing is in scope."));
            return Result.skipped("No include patterns configured");
        }

        String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            postComment(owner, repo, prNumber,
                    ReadmeSyncSummaryRenderer.renderSkipped(prNumber, "the pull request has no diff."));
            return Result.skipped("No diff");
        }

        String headBranch = resolveHeadBranch(payload, owner, repo, prNumber);
        if (headBranch == null) {
            // Never fall back to the default branch: this workflow clones the head
            // branch and (in commit/offer modes) pushes to it. Cloning/committing the
            // wrong branch would write docs to the default branch. Skip instead.
            log.warn("readme-sync: could not resolve PR head branch for PR #{} in {}/{} — skipping",
                    prNumber, owner, repo);
            postComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderSkipped(prNumber,
                    "could not determine the pull request's head branch, so the workflow was skipped "
                            + "to avoid writing documentation to the wrong branch."));
            return Result.skipped("Unresolved PR head branch");
        }

        Path workspace = null;
        try {
            context.requireActive("before preparing readme-sync workspace");
            WorkspaceResult ws = workspaceService.prepareWorkspace(
                    owner, repo, headBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken(), prNumber);
            if (!ws.success()) {
                postComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderFailed(prNumber,
                        "failed to prepare workspace: " + ws.error()));
                return Result.failed("Workspace preparation failed");
            }
            workspace = ws.workspacePath();

            postComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderStarting(
                    prNumber, request.includePatterns(), request.lifecycleMode()));
            context.appendStep("readme-sync-start",
                    "Checking documentation drift (patterns=" + request.includePatterns()
                            + ", lifecycle=" + request.lifecycleMode().key() + ")");

            ReadmeSyncToolContext toolContext = new ReadmeSyncToolContext(workspace, request.includePatterns());
            String kickoff = buildKickoffMessage(workspace, request, prTitle, prBody, diff);

            context.requireActive("before running readme-sync agent");
            ReadmeSyncAgent.Result authored = agent.write(
                    aiClient, toolContext, kickoff, systemPrompt, request.maxToolRounds());

            if (!toolContext.touchedAnything()) {
                postReviewComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderCompletion(
                        prNumber, toolContext, false, null, authored.finalAssistantText()));
                context.appendStep("readme-sync-agent", "No documentation changes needed");
                return Result.success("No documentation changes needed");
            }
            context.appendStep("readme-sync-agent",
                    "Applied " + toolContext.changeCount() + " documentation change(s)");

            // Pre-commit guard (defence-in-depth behind the write-time guard):
            // re-verify every changed file is inside the configured documentation
            // scope. If anything else was touched we must not push it.
            List<String> offending = workspaceService.listChangedFiles(workspace).stream()
                    .filter(p -> !DocPathGuard.isAllowedDocPath(request.includePatterns(), p))
                    .toList();
            if (!offending.isEmpty()) {
                log.warn("Aborting readme-sync commit for PR #{}: changed files outside documentation "
                        + "scope {}: {}", prNumber, request.includePatterns(), offending);
                postReviewComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderFailed(prNumber,
                        "the agent touched files outside the configured documentation scope "
                                + "(" + offending + ") — no changes were committed."));
                context.appendStep("readme-sync-commit", "Aborted — out-of-scope files: " + offending);
                return Result.failed("Out-of-scope files changed");
            }

            return applyLifecycle(context, owner, repo, prNumber, headBranch, workspace, request, toolContext);
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("readme-sync workflow failed for PR #{} in {}/{}: {}",
                    prNumber, owner, repo, e.getMessage(), e);
            postComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderFailed(prNumber,
                    "unexpected error: " + e.getMessage()));
            return Result.failed(e.getMessage());
        } finally {
            if (workspace != null) {
                workspaceService.cleanupWorkspace(workspace);
            }
        }
    }

    private Result applyLifecycle(PrWorkflowContext context, String owner, String repo, long prNumber,
                                  String headBranch, Path workspace, Request request,
                                  ReadmeSyncToolContext toolContext) {
        SuiteLifecycleMode mode = request.lifecycleMode();

        if (mode == SuiteLifecycleMode.EPHEMERAL) {
            // Report-only: do not commit. The changes stay in the (soon-discarded)
            // workspace; the comment lists what would change.
            postReviewComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderCompletion(
                    prNumber, toolContext, false,
                    "(report-only — not committed)", null));
            context.appendStep("readme-sync-commit", "Report-only (ephemeral) — nothing committed");
            return Result.success(toolContext.changeCount() + " documentation change(s) proposed (report-only)");
        }

        context.requireActive("before committing documentation changes");

        if (mode == SuiteLifecycleMode.OFFER_AS_PR) {
            String workBranch = "ai-docs/pr-" + prNumber + "-" + System.currentTimeMillis();
            boolean pushed = workspaceService.commitAndPush(workspace, workBranch,
                    "docs: sync documentation for PR #" + prNumber,
                    GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, true);
            if (!pushed) {
                postReviewComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderCompletion(
                        prNumber, toolContext, false, null, null));
                context.appendStep("readme-sync-commit", "commit/push failed for branch " + workBranch);
                return Result.failed("git commit/push failed");
            }
            String target = "(follow-up PR against `" + headBranch + "`)";
            Long followUp = null;
            try {
                followUp = repositoryClient.createPullRequest(owner, repo,
                        "Sync documentation for PR #" + prNumber,
                        "Automated documentation sync for PR #" + prNumber + ".", workBranch, headBranch);
            } catch (RuntimeException e) {
                log.warn("readme-sync: createPullRequest failed for PR #{}: {}", prNumber, e.getMessage());
            }
            if (followUp != null) {
                target = "(follow-up PR #" + followUp + " against `" + headBranch + "`)";
            }
            postReviewComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderCompletion(
                    prNumber, toolContext, true, target, null));
            context.appendStep("readme-sync-commit",
                    "offer-as-pr — pushed " + workBranch + (followUp == null ? "" : (", opened PR #" + followUp)));
            return Result.success(toolContext.changeCount() + " documentation change(s) offered as a follow-up PR");
        }

        // COMMIT_TO_PR (default): commit straight onto the PR head branch.
        boolean committed = workspaceService.commitAndPush(workspace, headBranch,
                "docs: sync documentation for PR #" + prNumber,
                GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, false);
        postReviewComment(owner, repo, prNumber, ReadmeSyncSummaryRenderer.renderCompletion(
                prNumber, toolContext, committed,
                committed ? "and committed to `" + headBranch + "`" : null, null));
        context.appendStep("readme-sync-commit",
                committed ? "Committed documentation changes to " + headBranch : "Commit skipped / failed");
        return committed
                ? Result.success(toolContext.changeCount() + " documentation change(s) committed to PR")
                : Result.failed("git commit/push failed");
    }

    String buildKickoffMessage(Path workspace, Request request, String prTitle, String prBody, String diff) {
        String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(diff truncated)"
                : diff;
        StringBuilder sb = new StringBuilder(8192);
        sb.append("""
                Review the following pull request for documentation drift and update the \
                in-scope Markdown documentation if the code changes made it inaccurate.
                
                """);
        sb.append("Title: ").append(prTitle == null ? "(none)" : prTitle).append('\n');
        if (prBody != null && !prBody.isBlank()) {
            sb.append("Description:\n").append(prBody).append('\n');
        }
        if (request.userGuidance() != null && !request.userGuidance().isBlank()) {
            sb.append("\nAdditional operator guidance for this run:\n")
                    .append(request.userGuidance().trim()).append('\n');
        }
        sb.append("\nUnified diff:\n```diff\n").append(truncatedDiff).append("\n```\n\n");

        appendInScopeDocs(sb, workspace, request.includePatterns());

        sb.append("\nApply the necessary documentation changes now via `doc-write` / `doc-delete`. "
                + "When finished (or if no change is needed), reply with a final `DONE` line.");
        return sb.toString();
    }

    private void appendInScopeDocs(StringBuilder sb, Path workspace, List<String> includePatterns) {
        List<String> docs = findInScopeDocs(workspace, includePatterns);
        if (docs.isEmpty()) {
            sb.append("There are currently no documentation files in scope. You may create new ones "
                    + "matching the configured patterns if the change warrants documentation.\n");
            return;
        }
        sb.append("Current content of the in-scope documentation files:\n\n");
        int totalChars = 0;
        int included = 0;
        for (String rel : docs) {
            if (included >= MAX_DOC_FILES_IN_CONTEXT || totalChars > MAX_DOC_CONTEXT_CHARS) {
                sb.append("\n(Remaining in-scope documentation omitted due to size limits.)\n");
                break;
            }
            try {
                String content = Files.readString(workspace.resolve(rel), StandardCharsets.UTF_8);
                if (content.length() > MAX_SINGLE_DOC_CHARS) {
                    content = content.substring(0, MAX_SINGLE_DOC_CHARS) + "\n...(file truncated)";
                }
                sb.append("--- File: ").append(rel).append(" ---\n");
                sb.append(content).append("\n\n");
                totalChars += content.length();
                included++;
            } catch (IOException e) {
                log.debug("Could not read in-scope doc {}: {}", rel, e.getMessage());
            }
        }
    }

    /**
     * Walks the checkout and returns the workspace-relative paths of every
     * existing file that is in the configured documentation scope (Markdown +
     * matches an include pattern), skipping the {@code .git} directory.
     */
    List<String> findInScopeDocs(Path workspace, List<String> includePatterns) {
        List<String> result = new ArrayList<>();
        if (workspace == null) {
            return result;
        }
        Path root = workspace.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String rel = root.relativize(p.toAbsolutePath().normalize())
                                .toString().replace('\\', '/');
                        if (rel.startsWith(".git/") || rel.equals(".git")) {
                            return;
                        }
                        if (DocPathGuard.isAllowedDocPath(includePatterns, rel)) {
                            result.add(rel);
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not scan workspace for in-scope docs: {}", e.getMessage());
        }
        result.sort(String::compareTo);
        return result;
    }

    /**
     * Resolves the PR head branch to clone/commit against. Prefers the webhook
     * payload; when the head ref is missing (notably {@code issue_comment}-style
     * events that carry no {@code pull_request.head} block) it authoritatively
     * re-fetches it from the provider API. Returns {@code null} when the head
     * branch cannot be determined — callers MUST skip rather than substitute the
     * repository default branch, because this workflow writes and pushes to the
     * resolved branch.
     */
    private String resolveHeadBranch(WebhookPayload payload, String owner, String repo, long prNumber) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null
                && !payload.getPullRequest().getHead().getRef().isBlank()) {
            return BranchRefs.normalize(payload.getPullRequest().getHead().getRef());
        }
        return fetchHeadBranchFromApi(owner, repo, prNumber);
    }

    @SuppressWarnings("unchecked")
    private String fetchHeadBranchFromApi(String owner, String repo, long prNumber) {
        if (prNumber <= 0) {
            return null;
        }
        try {
            java.util.Map<String, Object> pr = repositoryClient.getPullRequestDetails(owner, repo, prNumber);
            if (pr != null && pr.get("head") instanceof java.util.Map<?, ?> head
                    && ((java.util.Map<String, Object>) head).get("ref") instanceof String ref
                    && !ref.isBlank()) {
                return BranchRefs.normalize(ref);
            }
        } catch (RuntimeException e) {
            log.debug("readme-sync: getPullRequestDetails failed for {}/{}#{}: {}",
                    owner, repo, prNumber, e.getMessage());
        }
        return null;
    }

    private long resolvePrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        return payload.getNumber() == null ? 0L : payload.getNumber();
    }

    private void postComment(String owner, String repo, long prNumber, String body) {
        try {
            repositoryClient.postPullRequestComment(owner, repo, prNumber, body);
        } catch (RuntimeException e) {
            log.warn("Failed to post readme-sync comment on PR #{}: {}", prNumber, e.getMessage());
        }
    }

    private void postReviewComment(String owner, String repo, long prNumber, String body) {
        try {
            repositoryClient.postReviewComment(owner, repo, prNumber, body);
        } catch (RuntimeException e) {
            log.warn("Failed to post readme-sync review comment on PR #{}: {}", prNumber, e.getMessage());
        }
    }
}
