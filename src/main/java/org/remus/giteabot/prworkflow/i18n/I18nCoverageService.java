package org.remus.giteabot.prworkflow.i18n;

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
import java.util.List;
import java.util.Map;

/**
 * Core business logic of the i18n-coverage workflow. Not a Spring singleton —
 * instances are created per-bot by {@link I18nCoverageServiceFactory} with the
 * bot's own {@link AiClient} and {@link RepositoryApiClient}, mirroring
 * {@code ReadmeSyncService}.
 *
 * <p>The flow is: clone the PR head branch, scan the whole checkout for in-scope
 * locale files, compute per-locale coverage gaps against the configured baseline
 * ({@link I18nCoverageDetector}), hand the diff + coverage report to the
 * {@link I18nCoverageAgent} (which drafts the missing translations and removes
 * stale keys directly into the checkout via the guarded {@code i18n-write} /
 * {@code i18n-delete} tools), enforce the i18n scope again as a pre-commit
 * guard, apply the configured {@link SuiteLifecycleMode lifecycle} and post a
 * single Markdown summary comment.</p>
 *
 * <p>Like readme-sync there is no persisted suite entity, so no database
 * migration is required — the set of changed locale files is tracked in the
 * {@link I18nCoverageToolContext} and the lifecycle promotion is driven off the
 * live workspace diff.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class I18nCoverageService {

    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 40_000;
    static final int MAX_LOCALE_FILE_CHARS = 40_000;
    static final int MAX_LOCALE_CONTEXT_CHARS = 160_000;

    private static final String GIT_AUTHOR_NAME = "AI Agent";
    private static final String GIT_AUTHOR_EMAIL = "ai-agent@bot.local";

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final SystemPrompt systemPrompt;
    private final WorkspaceService workspaceService;
    private final I18nCoverageAgent agent;

    /** Inputs resolved from the workflow params by {@link I18nCoverageWorkflow}. */
    public record Request(PrWorkflowContext context,
                          List<String> includePatterns,
                          String baselineLocale,
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
            postComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderSkipped(prNumber,
                    "no i18n include patterns are configured — nothing is in scope."));
            return Result.skipped("No include patterns configured");
        }

        String headBranch = resolveHeadBranch(payload, owner, repo, prNumber);
        if (headBranch == null) {
            // Never fall back to the default branch: this workflow clones the head
            // branch and (in commit/offer modes) pushes to it.
            log.warn("i18n-coverage: could not resolve PR head branch for PR #{} in {}/{} — skipping",
                    prNumber, owner, repo);
            postComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderSkipped(prNumber,
                    "could not determine the pull request's head branch, so the workflow was skipped "
                            + "to avoid writing translations to the wrong branch."));
            return Result.skipped("Unresolved PR head branch");
        }

        Path workspace = null;
        try {
            context.requireActive("before preparing i18n-coverage workspace");
            WorkspaceResult ws = workspaceService.prepareWorkspace(
                    owner, repo, headBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken(), prNumber);
            if (!ws.success()) {
                postComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderFailed(prNumber,
                        "failed to prepare workspace: " + ws.error()));
                return Result.failed("Workspace preparation failed");
            }
            workspace = ws.workspacePath();

            postComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderStarting(
                    prNumber, request.includePatterns(), request.baselineLocale(), request.lifecycleMode()));
            context.appendStep("i18n-coverage-start",
                    "Checking translation coverage (patterns=" + request.includePatterns()
                            + ", baseline=" + request.baselineLocale()
                            + ", lifecycle=" + request.lifecycleMode().key() + ")");

            // ---- Detection: scan the whole checkout, diff each locale against the baseline.
            I18nCoverageDetector.Report report = I18nCoverageDetector.detect(
                    workspace, request.includePatterns(), request.baselineLocale());
            context.appendStep("i18n-coverage-detect",
                    report.hasGaps()
                            ? report.totalMissingKeys() + " missing / " + report.totalStaleKeys()
                                    + " stale key(s) across " + report.affectedLocaleFileCount() + " locale file(s)"
                            : "No coverage gaps detected");

            if (!report.hasGaps()) {
                postReviewComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderCompletion(
                        prNumber, null, report, false, null,
                        "All in-scope locale files are already in sync with the baseline."));
                return Result.success("No translation coverage gaps detected");
            }

            I18nCoverageToolContext toolContext =
                    new I18nCoverageToolContext(workspace, request.includePatterns());
            String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
            String kickoff = buildKickoffMessage(workspace, request, prTitle, prBody, diff, report);

            context.requireActive("before running i18n-coverage agent");
            I18nCoverageAgent.Result authored = agent.generate(
                    aiClient, toolContext, kickoff, systemPrompt,
                    request.includePatterns(), request.baselineLocale(), request.maxToolRounds());

            if (!toolContext.touchedAnything()) {
                postReviewComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderCompletion(
                        prNumber, toolContext, report, false, null, authored.finalAssistantText()));
                context.appendStep("i18n-coverage-agent", "Agent produced no translation changes");
                return Result.success("No translation changes applied");
            }
            context.appendStep("i18n-coverage-agent",
                    "Applied " + toolContext.changeCount() + " translation change(s)");

            // Pre-commit guard (defence-in-depth behind the write-time guard):
            // re-verify every changed file is inside the configured i18n scope.
            List<String> offending = workspaceService.listChangedFiles(workspace).stream()
                    .filter(p -> !I18nPathGuard.isAllowedI18nPath(request.includePatterns(), p))
                    .toList();
            if (!offending.isEmpty()) {
                log.warn("Aborting i18n-coverage commit for PR #{}: changed files outside i18n "
                        + "scope {}: {}", prNumber, request.includePatterns(), offending);
                postReviewComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderFailed(prNumber,
                        "the agent touched files outside the configured i18n scope "
                                + "(" + offending + ") — no changes were committed."));
                context.appendStep("i18n-coverage-commit", "Aborted — out-of-scope files: " + offending);
                return Result.failed("Out-of-scope files changed");
            }

            return applyLifecycle(context, owner, repo, prNumber, headBranch, workspace, request,
                    toolContext, report);
        } catch (WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("i18n-coverage workflow failed for PR #{} in {}/{}: {}",
                    prNumber, owner, repo, e.getMessage(), e);
            postComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderFailed(prNumber,
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
                                  I18nCoverageToolContext toolContext, I18nCoverageDetector.Report report) {
        SuiteLifecycleMode mode = request.lifecycleMode();

        if (mode == SuiteLifecycleMode.EPHEMERAL) {
            postReviewComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderCompletion(
                    prNumber, toolContext, report, false,
                    "(report-only — not committed)", null));
            context.appendStep("i18n-coverage-commit", "Report-only (ephemeral) — nothing committed");
            return Result.success(toolContext.changeCount() + " translation change(s) proposed (report-only)");
        }

        context.requireActive("before committing translation changes");

        if (mode == SuiteLifecycleMode.OFFER_AS_PR) {
            String workBranch = "ai-i18n/pr-" + prNumber + "-" + System.currentTimeMillis();
            boolean pushed = workspaceService.commitAndPush(workspace, workBranch,
                    "i18n: sync translation coverage for PR #" + prNumber,
                    GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, true);
            if (!pushed) {
                postReviewComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderCompletion(
                        prNumber, toolContext, report, false, null, null));
                context.appendStep("i18n-coverage-commit", "commit/push failed for branch " + workBranch);
                return Result.failed("git commit/push failed");
            }
            String target = "(follow-up PR against `" + headBranch + "`)";
            Long followUp = null;
            try {
                followUp = repositoryClient.createPullRequest(owner, repo,
                        "Sync translation coverage for PR #" + prNumber,
                        "Automated i18n coverage sync for PR #" + prNumber + ".", workBranch, headBranch);
            } catch (RuntimeException e) {
                log.warn("i18n-coverage: createPullRequest failed for PR #{}: {}", prNumber, e.getMessage());
            }
            if (followUp != null) {
                target = "(follow-up PR #" + followUp + " against `" + headBranch + "`)";
            }
            postReviewComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderCompletion(
                    prNumber, toolContext, report, true, target, null));
            context.appendStep("i18n-coverage-commit",
                    "offer-as-pr — pushed " + workBranch + (followUp == null ? "" : (", opened PR #" + followUp)));
            return Result.success(toolContext.changeCount() + " translation change(s) offered as a follow-up PR");
        }

        // COMMIT_TO_PR (default): commit straight onto the PR head branch.
        boolean committed = workspaceService.commitAndPush(workspace, headBranch,
                "i18n: sync translation coverage for PR #" + prNumber,
                GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, false);
        postReviewComment(owner, repo, prNumber, I18nCoverageSummaryRenderer.renderCompletion(
                prNumber, toolContext, report, committed,
                committed ? "and committed to `" + headBranch + "`" : null, null));
        context.appendStep("i18n-coverage-commit",
                committed ? "Committed translation changes to " + headBranch : "Commit skipped / failed");
        return committed
                ? Result.success(toolContext.changeCount() + " translation change(s) committed to PR")
                : Result.failed("git commit/push failed");
    }

    String buildKickoffMessage(Path workspace, Request request, String prTitle, String prBody,
                               String diff, I18nCoverageDetector.Report report) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("""
                Bring every non-baseline locale file below back in sync with its baseline's \
                key set. For each locale file you edit you MUST write back the COMPLETE file: \
                keep every key/value it already contains, ADD the listed missing keys with a \
                translation of the baseline value, and REMOVE only the listed stale keys. \
                Never drop keys that are already translated, and never modify the baseline file.

                """);
        sb.append("Title: ").append(prTitle == null ? "(none)" : prTitle).append('\n');
        if (prBody != null && !prBody.isBlank()) {
            sb.append("Description:\n").append(prBody).append('\n');
        }
        sb.append("Baseline locale: ")
                .append(request.baselineLocale() == null || request.baselineLocale().isBlank()
                        ? "(implicit default per family)" : request.baselineLocale())
                .append('\n');
        if (request.userGuidance() != null && !request.userGuidance().isBlank()) {
            sb.append("\nAdditional operator guidance for this run:\n")
                    .append(request.userGuidance().trim()).append('\n');
        }
        if (diff != null && !diff.isBlank()) {
            String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                    ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(diff truncated)"
                    : diff;
            sb.append("\nUnified diff (for context only — drive changes from the coverage report):\n```diff\n")
                    .append(truncatedDiff).append("\n```\n");
        }

        sb.append('\n').append(renderCoverageReport(workspace, report));

        sb.append("\nApply the necessary translation changes now via `i18n-write` (write the "
                + "complete file each time) / `i18n-delete`. When finished (or if no change is "
                + "needed), reply with a final `DONE` line.");
        return sb.toString();
    }

    /**
     * Renders the per-locale coverage report AND embeds the current on-disk
     * content of every relevant file: the baseline file (the source of truth for
     * both keys and values) and each non-baseline member file that has a gap.
     *
     * <p>Embedding the full current content is essential: {@code i18n-write}
     * overwrites the whole file, so the agent must see every already-translated
     * key it has to preserve. Without it the agent would reconstruct the file
     * from the key list alone and drop all existing translations — which looks
     * like it is deleting keys that are present in the baseline.</p>
     */
    static String renderCoverageReport(Path workspace, I18nCoverageDetector.Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Per-locale coverage report:\n\n");
        int budget = MAX_LOCALE_CONTEXT_CHARS;
        for (I18nCoverageDetector.FamilyCoverage family : report.families()) {
            boolean familyHasGap = family.gaps().stream().anyMatch(I18nCoverageDetector.LocaleGap::hasGap);
            if (!familyHasGap) {
                continue;
            }
            sb.append("### Bundle family (baseline `").append(family.baselinePath())
                    .append("`, locale `").append(family.baselineLocale().isEmpty()
                            ? "(default)" : family.baselineLocale()).append("`)\n");

            // The baseline file — the reference for both which keys must exist and
            // what each value means. The agent translates these values per locale.
            budget = appendFileBlock(sb, workspace, family.baselinePath(),
                    "Baseline file (reference — DO NOT modify)", budget);

            for (I18nCoverageDetector.LocaleGap gap : family.gaps()) {
                if (!gap.hasGap()) {
                    continue;
                }
                sb.append("- File `").append(gap.path()).append("` (locale `")
                        .append(gap.locale().isEmpty() ? "(default)" : gap.locale()).append("`):\n");
                if (!gap.missingKeys().isEmpty()) {
                    sb.append("    - MISSING keys (add with a translation of the baseline value): ")
                            .append(String.join(", ", gap.missingKeys())).append('\n');
                }
                if (!gap.staleKeys().isEmpty()) {
                    sb.append("    - STALE keys (remove only these): ")
                            .append(String.join(", ", gap.staleKeys())).append('\n');
                }
                // The member file's current content — every key here must be kept
                // (minus the stale keys) so the overwrite never loses translations.
                budget = appendFileBlock(sb, workspace, gap.path(),
                        "Current content (keep all keys except the stale ones)", budget);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Appends a fenced block with the current UTF-8 content of {@code relPath}.
     * Returns the remaining character budget; when it is exhausted only a
     * placeholder is emitted so a huge repository cannot blow the context.
     */
    private static int appendFileBlock(StringBuilder sb, Path workspace, String relPath,
                                       String caption, int budget) {
        sb.append("    ").append(caption).append(" — `").append(relPath).append("`:\n");
        if (budget <= 0) {
            sb.append("    (omitted — context size limit reached; read the file with the tools if needed)\n");
            return budget;
        }
        try {
            String content = Files.readString(workspace.resolve(relPath), StandardCharsets.UTF_8);
            if (content.length() > MAX_LOCALE_FILE_CHARS) {
                content = content.substring(0, MAX_LOCALE_FILE_CHARS) + "\n...(file truncated)";
            }
            sb.append("```\n").append(content);
            if (!content.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append("```\n");
            return budget - content.length();
        } catch (IOException e) {
            sb.append("    (could not read file: ").append(e.getMessage()).append(")\n");
            return budget;
        }
    }

    /**
     * Resolves the PR head branch to clone/commit against. Prefers the webhook
     * payload; when the head ref is missing it re-fetches it from the provider
     * API. Returns {@code null} when the head branch cannot be determined —
     * callers MUST skip rather than substitute the repository default branch.
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
            Map<String, Object> pr = repositoryClient.getPullRequestDetails(owner, repo, prNumber);
            if (pr != null && pr.get("head") instanceof Map<?, ?> head
                    && ((Map<String, Object>) head).get("ref") instanceof String ref
                    && !ref.isBlank()) {
                return BranchRefs.normalize(ref);
            }
        } catch (RuntimeException e) {
            log.debug("i18n-coverage: getPullRequestDetails failed for {}/{}#{}: {}",
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
            log.warn("Failed to post i18n-coverage comment on PR #{}: {}", prNumber, e.getMessage());
        }
    }

    private void postReviewComment(String owner, String repo, long prNumber, String body) {
        try {
            repositoryClient.postReviewComment(owner, repo, prNumber, body);
        } catch (RuntimeException e) {
            log.warn("Failed to post i18n-coverage review comment on PR #{}: {}", prNumber, e.getMessage());
        }
    }
}
