package org.remus.giteabot.prworkflow.i18n;

import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;

import java.util.Collection;
import java.util.List;

/**
 * Renders the Markdown PR comments posted by the {@link I18nCoverageWorkflow} /
 * {@code I18nCoverageService}: the "starting" acknowledgement, the skipped
 * notice, the failure notice and the final completion summary.
 */
public final class I18nCoverageSummaryRenderer {

    private static final String HEADER = "## 🌍 i18n Coverage";

    private I18nCoverageSummaryRenderer() {
    }

    public static String renderStarting(long prNumber, List<String> patterns,
                                        String baselineLocale, SuiteLifecycleMode mode) {
        return HEADER + "\n\n"
                + "Checking translation coverage across locale files for **PR #" + prNumber + "**.\n\n"
                + "- i18n scope: " + renderPatterns(patterns) + "\n"
                + "- Baseline locale: " + renderBaseline(baselineLocale) + "\n"
                + "- Lifecycle: `" + mode.key() + "`\n";
    }

    public static String renderSkipped(long prNumber, String reason) {
        return HEADER + "\n\n"
                + "Skipped for **PR #" + prNumber + "** — " + reason + "\n";
    }

    public static String renderFailed(long prNumber, String reason) {
        return HEADER + "\n\n"
                + "❌ i18n coverage could not be completed for **PR #" + prNumber + "**: " + reason + "\n";
    }

    /**
     * Final completion comment. States whether translation changes were made,
     * lists the added / updated / deleted locale files, and — when nothing
     * changed — carries the agent's short explanation.
     */
    public static String renderCompletion(long prNumber,
                                          I18nCoverageToolContext ctx,
                                          I18nCoverageDetector.Report report,
                                          boolean committed,
                                          String targetDescription,
                                          String noChangeExplanation) {
        StringBuilder sb = new StringBuilder(HEADER).append("\n\n");
        if (ctx == null || !ctx.touchedAnything()) {
            sb.append("No translation changes were needed for **PR #").append(prNumber).append("**.");
            if (report != null && !report.hasGaps()) {
                sb.append(" All in-scope locale files are in sync with the baseline.");
            }
            if (noChangeExplanation != null && !noChangeExplanation.isBlank()) {
                sb.append("\n\n> ").append(oneLine(noChangeExplanation));
            }
            sb.append('\n');
            return sb.toString();
        }

        sb.append("Translations were updated for **PR #").append(prNumber).append("**");
        if (targetDescription != null && !targetDescription.isBlank()) {
            sb.append(' ').append(targetDescription);
        }
        sb.append(".\n\n");

        if (report != null && report.hasGaps()) {
            sb.append("Detected coverage gaps: **").append(report.totalMissingKeys())
                    .append("** missing key(s), **").append(report.totalStaleKeys())
                    .append("** stale key(s) across **").append(report.affectedLocaleFileCount())
                    .append("** locale file(s).\n\n");
        }

        appendSection(sb, "Added", ctx.created());
        appendSection(sb, "Updated", ctx.updated());
        appendSection(sb, "Deleted", ctx.deleted());

        if (!committed) {
            sb.append("""
                    
                    > ⚠️ The changes were prepared but could not be committed/pushed — \
                    see the run log for details.
                    """);
        }
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String label, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        sb.append("**").append(label).append("** (").append(paths.size()).append("):\n");
        for (String p : paths) {
            sb.append("- `").append(p).append("`\n");
        }
        sb.append('\n');
    }

    private static String renderPatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return "_(none configured)_";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('`').append(patterns.get(i)).append('`');
        }
        return sb.toString();
    }

    private static String renderBaseline(String baselineLocale) {
        return (baselineLocale == null || baselineLocale.isBlank())
                ? "_(implicit default per family)_"
                : "`" + baselineLocale + "`";
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
