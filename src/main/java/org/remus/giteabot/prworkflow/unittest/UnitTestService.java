package org.remus.giteabot.prworkflow.unittest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.prworkflow.unittest.agents.UnitTestAuthorAgent;
import org.remus.giteabot.prworkflow.unittest.coverage.CoverageResult;
import org.remus.giteabot.prworkflow.unittest.runner.UnitTestOutcome;
import org.remus.giteabot.prworkflow.unittest.runner.UnitTestRunRequest;
import org.remus.giteabot.prworkflow.unittest.runner.UnitTestRunner;
import org.remus.giteabot.prworkflow.unittest.tools.UnitTestToolContext;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Core business logic of the unit-test-author workflow. Not a Spring singleton
 * — instances are created per-bot by {@link UnitTestServiceFactory} with the
 * bot's own {@link AiClient} and {@link RepositoryApiClient}, mirroring
 * {@code AgentReviewService}.
 *
 * <p>The flow is: clone the PR head branch, hand the diff + changed production
 * files to the {@link UnitTestAuthorAgent} (which writes test files into the
 * checkout and persists {@link UnitTestCase} rows), optionally commit the new
 * tests onto the PR branch, run the project's own test runner for a report, and
 * post a single Markdown summary comment.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class UnitTestService {

    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 60_000;
    static final int MAX_FILE_CONTEXT_CHARS = 80_000;
    static final int MAX_CHANGED_FILES_IN_CONTEXT = 25;

    private static final String GIT_AUTHOR_NAME = "AI Agent";
    private static final String GIT_AUTHOR_EMAIL = "ai-agent@bot.local";

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final SystemPrompt systemPrompt;

    private final WorkspaceService workspaceService;
    private final FrameworkDetector frameworkDetector;
    private final UnitTestAuthorAgent authorAgent;
    private final UnitTestRunner runner;
    private final UnitTestSuiteRepository suiteRepository;

    /** Inputs resolved from the workflow params by {@link UnitTestWorkflow}. */
    public record Request(PrWorkflowContext context,
                          UnitTestFramework configuredFramework,
                          int maxRetries,
                          int maxTestCases,
                          SuiteLifecycleMode lifecycleMode) {
    }

    /** Terminal result the workflow maps onto a {@code WorkflowResult}. */
    public record Result(Status status, String summary) {
        public enum Status { SUCCESS, FAILED, SKIPPED }

        public static Result success(String s) { return new Result(Status.SUCCESS, s); }
        public static Result failed(String s)  { return new Result(Status.FAILED, s); }
        public static Result skipped(String s) { return new Result(Status.SKIPPED, s); }
    }

    public Result generate(Request request) {
        PrWorkflowContext context = request.context();
        WebhookPayload payload = context.payload();
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        long prNumber = resolvePrNumber(payload);
        String prTitle = payload.getPullRequest() == null ? null : payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest() == null ? null : payload.getPullRequest().getBody();

        String diff = repositoryClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            postComment(owner, repo, prNumber,
                    UnitTestSummaryRenderer.renderSkipped(prNumber, "the pull request has no diff."));
            return Result.skipped("No diff");
        }

        String headBranch = resolveHeadBranch(payload, owner, repo);
        String headRef = headSha(payload) != null ? headSha(payload) : headBranch;

        Path workspace = null;
        try {
            context.requireActive("before preparing unit-test workspace");
            WorkspaceResult ws = workspaceService.prepareWorkspace(
                    owner, repo, headBranch,
                    repositoryClient.getCloneUrl(), repositoryClient.getToken());
            if (!ws.success()) {
                postComment(owner, repo, prNumber,
                        UnitTestSummaryRenderer.renderFailed(prNumber,
                                "failed to prepare workspace: " + ws.error()));
                return Result.failed("Workspace preparation failed");
            }
            workspace = ws.workspacePath();

            UnitTestFramework framework = resolveFramework(request, workspace);
            if (framework == null) {
                postComment(owner, repo, prNumber,
                        UnitTestSummaryRenderer.renderSkipped(prNumber,
                                "could not detect a supported build/test toolchain "
                                        + "(maven, gradle, npm, pytest, go, cargo, dotnet, bundle, make, gcc, g++). "
                                        + "Set the framework explicitly in the workflow configuration."));
                return Result.skipped("No framework detected");
            }

            postComment(owner, repo, prNumber,
                    UnitTestSummaryRenderer.renderStarting(prNumber, framework));
            context.appendStep("unit-test-start",
                    "Generating unit tests (framework=" + framework.key() + ")");

            // Persist the suite first so the writer tool can attach cases to it.
            context.requireActive("before persisting unit-test suite");
            UnitTestSuite suite = new UnitTestSuite();
            suite.setRunId(context.runId());
            suite.setPrNumber(prNumber);
            suite.setFramework(framework);
            suite.setSourceTreeRef(headSha(payload));
            suite.setLifecycleMode(request.lifecycleMode());
            suite.setCreatedAt(Instant.now());
            suite = suiteRepository.save(suite);

            String kickoff = buildKickoffMessage(owner, repo, headRef, framework, prTitle, prBody, diff);
            UnitTestToolContext toolContext = new UnitTestToolContext(suite, workspace, framework);

            context.requireActive("before running unit-test author agent");
            UnitTestAuthorAgent.Result authored = authorAgent.write(
                    aiClient, toolContext, kickoff, systemPrompt, request.maxTestCases());

            if (!authored.wroteAnything()) {
                postComment(owner, repo, prNumber,
                        UnitTestSummaryRenderer.renderFailed(prNumber,
                                "the author agent did not produce any runnable test files."));
                context.appendStep("unit-test-author", "No files written");
                return Result.failed("No tests generated");
            }
            context.appendStep("unit-test-author", "Wrote " + authored.filesWritten() + " test file(s)");

            // Commit the freshly written tests onto the PR branch *before* running
            // them: at this point the working tree only contains the new test
            // files, so the commit stays clean (no build artefacts).
            boolean committed = false;
            if (request.lifecycleMode() == SuiteLifecycleMode.COMMIT_TO_PR
                    && workspaceService.hasUncommittedChanges(workspace)) {
                context.requireActive("before committing generated unit tests");
                // Pre-commit guard (defence-in-depth behind the write-time guard):
                // re-verify every changed file is an allowed test location for the
                // framework. If anything outside a test location was touched we
                // must not push it — the workflow's "production code is never
                // touched" guarantee takes precedence over committing the tests.
                java.util.List<String> offending = workspaceService.listChangedFiles(workspace).stream()
                        .filter(p -> !UnitTestPathGuard.isAllowedTestPath(framework, p))
                        .toList();
                if (!offending.isEmpty()) {
                    log.warn("Aborting unit-test commit for PR #{}: changed files outside allowed "
                            + "test locations for {}: {}", prNumber, framework.key(), offending);
                    context.appendStep("unit-test-commit",
                            "Commit aborted — non-test files changed: " + offending);
                } else {
                    committed = workspaceService.commitAndPush(workspace, headBranch,
                            "test: add AI-generated unit tests for PR #" + prNumber,
                            GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, false);
                    context.appendStep("unit-test-commit",
                            committed ? "Committed generated tests to " + headBranch
                                    : "Commit skipped / failed");
                }
            }

            // Run the project's own test runner for the report (after committing,
            // so build artefacts never pollute the commit).
            UnitTestOutcome outcome = runTests(suite, workspace, framework, request.maxRetries());
            context.appendStep("unit-test-run", outcome.status() + " — " + outcome.summary());

            UnitTestSuite withCases = suiteRepository.findByIdWithCases(suite.getId()).orElse(suite);
            String comment = UnitTestSummaryRenderer.render(withCases, outcome, CoverageResult.unknown());
            postReviewComment(owner, repo, prNumber, comment);

            return switch (outcome.status()) {
                case PASSED -> Result.success(
                        authored.filesWritten() + " test(s) generated and passing"
                                + (committed ? ", committed to PR" : ""));
                case FAILED -> Result.success(
                        authored.filesWritten() + " test(s) generated; " + outcome.failed()
                                + " failing — review required");
                case SKIPPED -> Result.skipped(outcome.summary());
                case ERROR -> Result.failed(outcome.summary());
            };
        } catch (org.remus.giteabot.prworkflow.WorkflowCancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Unit-test workflow failed for PR #{} in {}/{}: {}",
                    prNumber, owner, repo, e.getMessage(), e);
            postComment(owner, repo, prNumber,
                    UnitTestSummaryRenderer.renderFailed(prNumber,
                            "unexpected error: " + e.getMessage()));
            return Result.failed(e.getMessage());
        } finally {
            if (workspace != null) {
                workspaceService.cleanupWorkspace(workspace);
            }
        }
    }

    private UnitTestOutcome runTests(UnitTestSuite suite, Path workspace,
                                     UnitTestFramework framework, int maxRetries) {
        try {
            return runner.run(new UnitTestRunRequest(suite, workspace, framework, maxRetries, null));
        } catch (RuntimeException e) {
            log.warn("Unit-test runner threw for framework {}: {}", framework.key(), e.getMessage(), e);
            return UnitTestOutcome.error("Runner threw " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private UnitTestFramework resolveFramework(Request request, Path workspace) {
        if (request.configuredFramework() != null) {
            return request.configuredFramework();
        }
        return frameworkDetector.detect(workspace).orElse(null);
    }

    String buildKickoffMessage(String owner, String repo, String ref, UnitTestFramework framework,
                               String prTitle, String prBody, String diff) {
        String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(diff truncated)"
                : diff;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("Write focused unit tests for the following pull request.\n\n");
        sb.append("Toolchain: ").append(framework.key()).append('\n');
        sb.append("Title: ").append(prTitle == null ? "(none)" : prTitle).append('\n');
        if (prBody != null && !prBody.isBlank()) {
            sb.append("Description:\n").append(prBody).append('\n');
        }
        sb.append("\nUnified diff:\n```diff\n").append(truncatedDiff).append("\n```\n\n");

        appendChangedFiles(sb, owner, repo, ref, diff);

        sb.append("\nWrite the tests now by calling `unit-test-write` once per test file. "
                + "When finished, reply with `DONE`.");
        return sb.toString();
    }

    private void appendChangedFiles(StringBuilder sb, String owner, String repo, String ref, String diff) {
        Set<String> changed = parseChangedFiles(diff);
        if (changed.isEmpty()) {
            return;
        }
        sb.append("Full content of the changed production files (at the PR head):\n\n");
        int totalChars = 0;
        int included = 0;
        for (String path : changed) {
            if (included >= MAX_CHANGED_FILES_IN_CONTEXT || totalChars > MAX_FILE_CONTEXT_CHARS) {
                sb.append("\n(Remaining changed files omitted due to size limits.)\n");
                break;
            }
            try {
                String content = repositoryClient.getFileContent(owner, repo, path, ref);
                if (content != null && !content.isEmpty()) {
                    sb.append("--- File: ").append(path).append(" ---\n");
                    sb.append(content).append("\n\n");
                    totalChars += content.length();
                    included++;
                }
            } catch (RuntimeException e) {
                log.debug("Could not fetch changed file {}: {}", path, e.getMessage());
            }
        }
    }

    /**
     * Extracts the post-image file paths from a unified diff ({@code +++ b/<path>}
     * lines), skipping deletions ({@code /dev/null}). Preserves diff order.
     */
    static Set<String> parseChangedFiles(String diff) {
        Set<String> files = new LinkedHashSet<>();
        if (diff == null) {
            return files;
        }
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).trim();
                if (path.equals("/dev/null")) {
                    continue;
                }
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                if (!path.isBlank()) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    private String resolveHeadBranch(WebhookPayload payload, String owner, String repo) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getHead() != null
                && payload.getPullRequest().getHead().getRef() != null) {
            return org.remus.giteabot.agent.shared.BranchRefs.normalize(
                    payload.getPullRequest().getHead().getRef());
        }
        try {
            return repositoryClient.getDefaultBranch(owner, repo);
        } catch (RuntimeException e) {
            return "main";
        }
    }

    private String headSha(WebhookPayload payload) {
        if (payload.getPullRequest() == null || payload.getPullRequest().getHead() == null) {
            return null;
        }
        return payload.getPullRequest().getHead().getSha();
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
            log.warn("Failed to post unit-test comment on PR #{}: {}", prNumber, e.getMessage());
        }
    }

    private void postReviewComment(String owner, String repo, long prNumber, String body) {
        try {
            repositoryClient.postReviewComment(owner, repo, prNumber, body);
        } catch (RuntimeException e) {
            log.warn("Failed to post unit-test review comment on PR #{}: {}", prNumber, e.getMessage());
        }
    }
}






