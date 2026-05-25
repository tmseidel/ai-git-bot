package org.remus.giteabot.prworkflow.e2e.promotion;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.e2e.PrTestCase;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M7 — turns a generated {@link PrTestSuite} into a real change on the
 * source repository. Three modes are supported (see
 * {@link SuiteLifecycleMode}):
 *
 * <ul>
 *     <li>{@link SuiteLifecycleMode#OFFER_AS_PR OFFER_AS_PR} — clones the
 *         feature branch into a sandbox, writes every generated
 *         {@link PrTestCase} under {@code tests/e2e/pr-{n}/}, commits onto
 *         a fresh branch {@code ai-tests/pr-{n}-r{runId}}, pushes it and
 *         opens a follow-up PR <em>against the feature branch</em>. The
 *         original PR author reviews the proposed tests in isolation.</li>
 *     <li>{@link SuiteLifecycleMode#PROMOTE_ON_MERGE PROMOTE_ON_MERGE} —
 *         only triggered after the parent PR merges. Clones the
 *         repository's default branch, writes the tests under
 *         {@code tests/e2e/}, commits onto
 *         {@code ai-tests/promoted-pr-{n}-r{runId}}, pushes and opens a
 *         follow-up PR against the default branch. The tests become part
 *         of the standard CI matrix.</li>
 *     <li>{@link SuiteLifecycleMode#COMMIT_TO_PR COMMIT_TO_PR} — clones
 *         the feature branch, writes the tests under
 *         {@code tests/e2e/pr-{n}/} and commits directly back to the
 *         feature branch without opening a separate PR. Useful for solo
 *         repositories where every extra PR is friction.</li>
 * </ul>
 *
 * <p>Idempotency: when the {@link PrWorkflowRun#getFollowUpPrNumber()} on
 * the supplied run is already populated, every mode returns
 * {@link Outcome#alreadyPromoted}. {@link SuiteLifecycleMode#EPHEMERAL} is
 * a no-op.</p>
 *
 * <p>Conflict policy: if a destination file path already exists in the
 * target branch (e.g. {@code tests/e2e/login.spec.ts}), the bot appends a
 * numeric suffix ({@code login_2.spec.ts}, {@code login_3.spec.ts}, …) so
 * the promotion never overwrites existing tests. The chosen final paths
 * are listed in the follow-up PR description.</p>
 *
 * <p>Failures are surfaced as {@link Outcome#failure} — the caller is
 * expected to log and continue. The original workflow run is never
 * rolled back.</p>
 */
@Slf4j
@Service
public class SuitePromotionService {

    private static final String GIT_AUTHOR_NAME = "AI-Git-Bot";
    private static final String GIT_AUTHOR_EMAIL = "ai-git-bot@local";

    private final WorkspaceService workspaceService;
    private final GiteaClientFactory giteaClientFactory;
    private final PrWorkflowRunRepository runRepository;

    public SuitePromotionService(WorkspaceService workspaceService,
                                 GiteaClientFactory giteaClientFactory,
                                 PrWorkflowRunRepository runRepository) {
        this.workspaceService = workspaceService;
        this.giteaClientFactory = giteaClientFactory;
        this.runRepository = runRepository;
    }

    public Outcome promote(Bot bot, PrWorkflowRun run, PrTestSuite suite,
                           String repoOwner, String repoName, String featureBranch) {
        if (suite == null || suite.getCases() == null || suite.getCases().isEmpty()) {
            return Outcome.skipped("No generated test cases to promote.");
        }
        SuiteLifecycleMode mode = suite.getLifecycleMode();
        if (mode == null || mode == SuiteLifecycleMode.EPHEMERAL) {
            return Outcome.skipped("Suite is " + mode + " — no promotion action.");
        }
        if (run.getFollowUpPrNumber() != null) {
            return Outcome.alreadyPromoted(run.getFollowUpPrNumber());
        }

        RepositoryApiClient client;
        try {
            client = giteaClientFactory.getApiClient(bot.getGitIntegration());
        } catch (RuntimeException e) {
            return Outcome.failure("Failed to obtain repository client: " + e.getMessage());
        }

        long prNumber = suite.getPrNumber();
        String baseBranch = switch (mode) {
            case PROMOTE_ON_MERGE -> client.getDefaultBranch(repoOwner, repoName);
            case OFFER_AS_PR, COMMIT_TO_PR -> featureBranch;
            case EPHEMERAL -> throw new IllegalStateException("EPHEMERAL rejected above");
        };
        if (baseBranch == null || baseBranch.isBlank()) {
            return Outcome.failure("Cannot determine base branch for mode " + mode);
        }

        String targetDir = (mode == SuiteLifecycleMode.PROMOTE_ON_MERGE)
                ? "tests/e2e"
                : "tests/e2e/pr-" + prNumber;


        String uniqueSuffix = (run.getId() != null)
                ? ("r" + run.getId())
                : ("t" + System.currentTimeMillis());

        String workBranch = switch (mode) {
            case PROMOTE_ON_MERGE -> "ai-tests/promoted-pr-" + prNumber + "-" + uniqueSuffix;
            case OFFER_AS_PR      -> "ai-tests/pr-" + prNumber + "-" + uniqueSuffix;
            case COMMIT_TO_PR     -> baseBranch;
            case EPHEMERAL        -> throw new IllegalStateException("EPHEMERAL rejected above");
        };

        WorkspaceResult ws = workspaceService.prepareWorkspace(repoOwner, repoName, baseBranch,
                client.getCloneUrl(), client.getToken());
        if (!ws.success()) {
            return Outcome.failure("Workspace preparation failed: " + ws.error());
        }
        Path workspace = ws.workspacePath();
        try {
            List<String> writtenPaths = writeCases(workspace, targetDir, suite.getCases());
            if (writtenPaths.isEmpty()) {
                return Outcome.skipped("No files written.");
            }

            boolean pushed = workspaceService.commitAndPush(workspace, workBranch,
                    commitMessage(mode, prNumber),
                    GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL,
                    mode != SuiteLifecycleMode.COMMIT_TO_PR);
            if (!pushed) {
                return Outcome.failure("git commit/push failed for branch '" + workBranch + "'.");
            }

            if (mode == SuiteLifecycleMode.COMMIT_TO_PR) {
                run.setFollowUpPrNumber(prNumber); // mirror parent PR number for idempotency
                runRepository.save(run);
                return Outcome.committed(workBranch, writtenPaths);
            }

            String title = (mode == SuiteLifecycleMode.PROMOTE_ON_MERGE)
                    ? "Promote E2E tests from merged PR #" + prNumber
                    : "Add E2E tests for PR #" + prNumber;
            String body = renderPrBody(mode, prNumber, targetDir, writtenPaths);
            Long followUpPr;
            try {
                followUpPr = client.createPullRequest(repoOwner, repoName, title, body,
                        workBranch, baseBranch);
            } catch (RuntimeException e) {
                return Outcome.failure("createPullRequest failed: " + e.getMessage());
            }
            if (followUpPr == null) {
                return Outcome.failure("createPullRequest returned null.");
            }

            run.setFollowUpPrNumber(followUpPr);
            runRepository.save(run);
            log.info("M7 promotion: opened follow-up PR #{} on {}/{} (mode={}, branch={})",
                    followUpPr, repoOwner, repoName, mode, workBranch);
            return Outcome.promoted(followUpPr, workBranch, writtenPaths);
        } finally {
            workspaceService.cleanupWorkspace(workspace);
        }
    }

    private List<String> writeCases(Path workspace, String targetDir, List<PrTestCase> cases) {
        Path baseDir = workspace.resolve(targetDir);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warn("Could not create target dir {}: {}", baseDir, e.getMessage());
            return List.of();
        }

        Set<String> used = new HashSet<>();
        List<String> written = new ArrayList<>();
        for (PrTestCase tc : cases) {
            String original = sanitisePath(tc.getPath());
            String chosen = resolveConflict(baseDir, original, used);
            used.add(chosen);
            Path destination = baseDir.resolve(chosen);
            try {
                Files.createDirectories(destination.getParent());
                Files.writeString(destination,
                        tc.getContent() == null ? "" : tc.getContent());
                written.add(targetDir + "/" + chosen);
            } catch (IOException e) {
                log.warn("Failed to write test case {}: {}", destination, e.getMessage());
            }
        }
        return written;
    }

    /**
     * If {@code targetDir/name} already exists on disk (or in {@code already}
     * chosen by an earlier case in this run), append a numeric suffix
     * before the first dot of the file name. Examples:
     * {@code login.spec.ts} → {@code login_2.spec.ts} → {@code login_3.spec.ts}.
     */
    static String resolveConflict(Path baseDir, String relativePath, Set<String> already) {
        Path candidate = baseDir.resolve(relativePath);
        if (!Files.exists(candidate) && !already.contains(relativePath)) {
            return relativePath;
        }
        String dirPart;
        String fileName;
        int slash = relativePath.lastIndexOf('/');
        if (slash >= 0) {
            dirPart = relativePath.substring(0, slash + 1);
            fileName = relativePath.substring(slash + 1);
        } else {
            dirPart = "";
            fileName = relativePath;
        }
        int dot = fileName.indexOf('.');
        String stem = dot >= 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            String attempt = dirPart + stem + "_" + i + ext;
            Path attemptPath = baseDir.resolve(attempt);
            if (!Files.exists(attemptPath) && !already.contains(attempt)) {
                return attempt;
            }
        }
        // Pathological fallback — should never happen in practice.
        return dirPart + stem + "_" + System.currentTimeMillis() + ext;
    }

    private static String sanitisePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "test.spec.ts";
        }
        String trimmed = raw.replace('\\', '/').trim();
        while (trimmed.startsWith("/") || trimmed.startsWith("./")) {
            trimmed = trimmed.startsWith("./") ? trimmed.substring(2) : trimmed.substring(1);
        }
        // Strip leading workspace dirs the agent might have already prefixed.
        String[] leadingPrefixes = {"tests/e2e/", "tests/", "e2e/"};
        for (String p : leadingPrefixes) {
            if (trimmed.startsWith(p)) {
                trimmed = trimmed.substring(p.length());
            }
        }
        return trimmed.isBlank() ? "test.spec.ts" : trimmed;
    }

    private String commitMessage(SuiteLifecycleMode mode, long prNumber) {
        return switch (mode) {
            case PROMOTE_ON_MERGE -> "test(e2e): promote generated tests from merged PR #" + prNumber;
            case OFFER_AS_PR      -> "test(e2e): generated tests for PR #" + prNumber;
            case COMMIT_TO_PR     -> "test(e2e): add generated tests for PR #" + prNumber;
            case EPHEMERAL        -> throw new IllegalStateException("EPHEMERAL never reaches commitMessage");
        };
    }

    private String renderPrBody(SuiteLifecycleMode mode, long prNumber,
                                String targetDir, List<String> paths) {
        StringBuilder sb = new StringBuilder();
        sb.append(" **AI-Git-Bot** — automated test promotion (`")
                .append(mode.key()).append("`).\n\n");
        if (mode == SuiteLifecycleMode.PROMOTE_ON_MERGE) {
            sb.append("Parent PR **#").append(prNumber)
                    .append("** has been merged. The bot generated the following ")
                    .append("end-to-end tests during the parent PR's E2E workflow ")
                    .append("and is now promoting them into the repository.\n\n");
        } else {
            sb.append("Parent PR: **#").append(prNumber)
                    .append("**. The bot generated the following end-to-end tests ")
                    .append("during the parent PR's E2E workflow and is offering ")
                    .append("them as a separate review.\n\n");
        }
        sb.append("**Files under `").append(targetDir).append("/`:**\n\n");
        for (String p : paths) {
            sb.append("- `").append(p).append("`\n");
        }
        sb.append("\n---\n");
        sb.append("> Tests are added as plain files — the standard CI is the ")
                .append("source of truth. Please review for hidden assumptions, ")
                .append("flaky selectors, and any secret/credential leakage before ")
                .append("merging.\n");
        return sb.toString();
    }

    /**
     * Outcome of a single {@link #promote} invocation. The caller logs the
     * outcome and posts an appropriate PR comment on the parent PR;
     * promotion never throws.
     */
    public record Outcome(Kind kind, Long followUpPrNumber, String branch,
                          List<String> writtenPaths, String message) {

        public enum Kind { PROMOTED, COMMITTED, ALREADY_PROMOTED, SKIPPED, FAILED }

        public static Outcome promoted(long prNumber, String branch, List<String> paths) {
            return new Outcome(Kind.PROMOTED, prNumber, branch, List.copyOf(paths), null);
        }

        public static Outcome committed(String branch, List<String> paths) {
            return new Outcome(Kind.COMMITTED, null, branch, List.copyOf(paths), null);
        }

        public static Outcome alreadyPromoted(long prNumber) {
            return new Outcome(Kind.ALREADY_PROMOTED, prNumber, null, List.of(),
                    "Follow-up PR #" + prNumber + " already exists for this run.");
        }

        public static Outcome skipped(String reason) {
            return new Outcome(Kind.SKIPPED, null, null, List.of(), reason);
        }

        public static Outcome failure(String reason) {
            return new Outcome(Kind.FAILED, null, null, List.of(), reason);
        }

    }
}

