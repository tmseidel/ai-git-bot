package org.remus.giteabot.prworkflow.e2e;

import org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteOutcome;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteOutcomeStatus;

import java.util.List;

/**
 * Renders the Markdown comment posted to the PR at the end of an
 * {@code E2ETestWorkflow} run. Pure / side-effect-free so it can be unit-
 * tested without spinning up Spring; the workflow handles the actual HTTP
 * POST to the repository.
 */
public final class E2eTestSummaryRenderer {

    private E2eTestSummaryRenderer() {
    }

    public static String render(PrTestSuite suite, TestSuiteOutcome outcome, String previewUrl) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("## E2E Test Run for PR #").append(suite.getPrNumber()).append('\n');
        sb.append('\n');
        sb.append("**Framework:** `").append(suite.getFramework().key()).append("`  \n");
        if (previewUrl != null && !previewUrl.isBlank()) {
            sb.append("**Preview environment:** ").append(previewUrl).append("  \n");
        }
        if (suite.getSourceTreeRef() != null && !suite.getSourceTreeRef().isBlank()) {
            sb.append("**Source SHA:** `").append(shortSha(suite.getSourceTreeRef())).append("`  \n");
        }
        sb.append("**Outcome:** ").append(statusEmoji(outcome.status())).append(' ')
                .append(outcome.status().name());
        if (outcome.attempted() > 0) {
            sb.append(" (").append(outcome.attempted() - outcome.failed()).append('/')
                    .append(outcome.attempted()).append(" passed)");
        }
        sb.append("\n\n");

        List<PrTestCase> cases = suite.getCases();
        if (cases == null || cases.isEmpty()) {
            sb.append("_No test cases were generated. ").append(outcome.summary()).append("_\n");
            return sb.toString();
        }

        sb.append("| Test | Status | Duration |\n");
        sb.append("| --- | --- | --- |\n");
        for (PrTestCase tc : cases) {
            sb.append("| `").append(escapePipe(tc.getPath())).append('`');
            if (tc.getTitle() != null && !tc.getTitle().isBlank()) {
                sb.append("<br/>").append(escapePipe(tc.getTitle()));
            }
            sb.append(" | ").append(caseStatusEmoji(tc.getLastStatus())).append(' ')
                    .append(tc.getLastStatus() == null ? "—" : tc.getLastStatus().name());
            sb.append(" | ").append(formatDuration(tc.getLastDurationMs())).append(" |\n");
        }
        sb.append('\n');
        if (outcome.summary() != null && !outcome.summary().isBlank()) {
            sb.append("> ").append(outcome.summary()).append('\n');
        }
        return sb.toString();
    }

    public static String renderSkipped(long prNumber, String reason) {
        return "## E2E Test Run for PR #" + prNumber + "\n\n"
                + "⏭️ Skipped — " + reason + "\n";
    }

    /**
     * Posted as soon as the bot decides to actually run the E2E workflow
     * (i.e. after the deployment-target precheck passes). The full pipeline
     * — preview deployment, LLM-driven test generation, runner execution —
     * routinely takes several minutes; this opener gives the operator
     * immediate feedback so the PR thread does not feel stalled.
     */
    public static String renderStarting(long prNumber, E2eTestFramework framework,
                                        SuiteLifecycleMode lifecycleMode) {
        return "## E2E Test Run for PR #" + prNumber + "\n\n" +
                "🤖 **Starting end-to-end test run** — this typically takes " +
                "several minutes.\n\n" +
                "- **Framework:** `" + framework.key() + "`\n" +
                "- **Suite lifecycle:** `" + lifecycleMode.key() + "`\n" +
                '\n' +
                "I'll deploy a preview environment, generate the test suite " +
                "and post the results here when the run finishes. " +
                "Use `@bot rerun-tests` to re-execute or " +
                "`@bot regenerate-tests <feedback>` to re-plan the suite.\n";
    }

    /**
     * Short "ack" comment posted at the very beginning of a <em>rerun-only</em>
     * run ({@code @bot rerun-tests}). Unlike {@link #renderStarting} it makes
     * clear that no new tests are generated — only the existing ones are
     * re-executed against a fresh deployment.
     */
    public static String renderRerunStarting(long prNumber, E2eTestFramework framework,
                                             SuiteLifecycleMode lifecycleMode) {
        return "## E2E Test Re-run for PR #" + prNumber + "\n\n" +
                "🔄 **Re-running existing test suite** — skipping test generation, " +
                "re-executing the previously generated tests against a fresh deployment.\n\n" +
                "- **Framework:** `" + framework.key() + "`\n" +
                "- **Suite lifecycle:** `" + lifecycleMode.key() + "`\n" +
                '\n' +
                "Results will be posted here once the run finishes. " +
                "Use `@bot regenerate-tests <feedback>` to re-plan and regenerate the suite.\n";
    }

    public static String renderFailed(long prNumber, String reason) {
        return "## E2E Test Run for PR #" + prNumber + "\n\n"
                + "❌ Failed — " + reason + "\n";
    }

    /**
     * M7 — short comment posted on the parent PR after a promotion attempt
     * (success or failure). The full file list lives on the follow-up PR
     * itself; this comment only carries the link + a one-line status so
     * the parent PR thread does not get spammed.
     */
    public static String renderPromotion(SuiteLifecycleMode mode,
                                         SuitePromotionService.Outcome outcome) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("##  Suite promotion (`").append(mode.key()).append("`)\n\n");
        switch (outcome.kind()) {
            case PROMOTED -> sb.append("✅ Opened follow-up PR **#")
                    .append(outcome.followUpPrNumber())
                    .append("** on branch `").append(outcome.branch())
                    .append("` with ").append(outcome.writtenPaths().size())
                    .append(" generated test file(s).\n");
            case COMMITTED -> sb.append("✅ Committed ")
                    .append(outcome.writtenPaths().size())
                    .append(" generated test file(s) directly onto `")
                    .append(outcome.branch()).append("`.\n");
            case ALREADY_PROMOTED -> sb.append("ℹ️ ")
                    .append(outcome.message() == null ? "Already promoted." : outcome.message())
                    .append('\n');
            case SKIPPED -> sb.append("⏭️ Skipped — ")
                    .append(outcome.message() == null ? "(no reason given)" : outcome.message())
                    .append('\n');
            case FAILED -> sb.append("❌ Promotion failed — ")
                    .append(outcome.message() == null ? "(no reason given)" : outcome.message())
                    .append('\n');
        }
        return sb.toString();
    }

    private static String statusEmoji(TestSuiteOutcomeStatus status) {
        return switch (status) {
            case PASSED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
            case ERROR -> "";
        };
    }

    private static String caseStatusEmoji(PrTestCaseStatus status) {
        if (status == null) {
            return "•";
        }
        return switch (status) {
            case PASSED -> "✅";
            case FAILED -> "❌";
            case FLAKY -> "⚠️";
            case SKIPPED -> "⏭️";
            case ERROR -> "";
            case PENDING -> "•";
        };
    }

    private static String formatDuration(Long ms) {
        if (ms == null || ms < 0) {
            return "—";
        }
        if (ms < 1000) {
            return ms + " ms";
        }
        double seconds = ms / 1000.0;
        return String.format(java.util.Locale.ROOT, "%.2fs", seconds);
    }

    private static String shortSha(String sha) {
        return sha.length() <= 8 ? sha : sha.substring(0, 8);
    }

    private static String escapePipe(String raw) {
        return raw.replace("|", "\\|");
    }
}
