package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.config.ReviewCommentProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders a finalized review into the single application-owned comment layout: verdict, assessment,
 * severity totals, findings, coverage. The model supplies field content only — headings, ordering,
 * counts, references, icons and the feedback footer position belong to this class, so every agentic
 * review looks the same regardless of provider or learning settings.
 *
 * <p>Display order is severity, then location, then the finding's ordinal (the input is
 * ordinal-ordered and the sort is stable). The ordinal is never derived from display order, which is
 * why re-sorting cannot renumber a finding.</p>
 *
 * <p>Field content is untrusted: a line that would open a heading, quote, fence, table, thematic
 * break or a second finding header is escaped, so model text can never forge review structure.</p>
 */
@Component
@RequiredArgsConstructor
public class ReviewCommentRenderer {

    private final ReviewCommentProperties properties;

    private static final String FINDING_SEVERITY = "(BLOCKER|MEDIUM|LOW)";

    /** Finding header shape; model text matching it is neutralized so it cannot spoof one. */
    private static final Pattern FINDING_HEADER_LIKE =
            Pattern.compile("^" + FINDING_SEVERITY + "\\s*\\u00B7");

    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})");

    private static final Pattern THEMATIC_BREAK = Pattern.compile("^(-{2,}|={2,}|\\*{3,}|_{3,})$");

    static final String INCONCLUSIVE_NOTICE = "**Inconclusive review** — coverage was incomplete. "
            + "Read the limitations below before relying on this review.";

    private static final String NOT_SPECIFIED = "not specified";

    public String render(FinalizedReview review) {
        return render(review, ReviewLinkStyle.NONE);
    }

    public String render(FinalizedReview review, ReviewLinkStyle linkStyle) {
        ReviewLinkStyle links = linkStyle == null ? ReviewLinkStyle.NONE : linkStyle;
        StringBuilder out = new StringBuilder();

        out.append("# \uD83E\uDD16 AI Review \u00B7 ").append(inline(review.reference()))
                .append("\n\n");
        out.append(verdictLine(review)).append("\n\n");

        out.append("## \uD83D\uDCDD Assessment \u00B7 ").append(inline(review.assessmentReference()))
                .append("\n\n");
        out.append(block(review.summary())).append('\n');

        if (review.conclusion() == ReviewConclusion.INCONCLUSIVE) {
            out.append("\n> ").append(INCONCLUSIVE_NOTICE).append('\n');
        }

        appendFindings(out, review, links);
        appendCoverage(out, review);
        appendFooter(out);
        return out.toString().stripTrailing();
    }

    /** One line a reader can decide on: how alarming the review is, and how many of each severity. */
    private static String verdictLine(FinalizedReview review) {
        String verdict = switch (review.conclusion()) {
            case FINDINGS_PRESENT -> "\u26A0\uFE0F **Findings present**";
            case NO_FINDINGS -> "\u2705 **No findings**";
            case INCONCLUSIVE -> "\u2753 **Inconclusive**";
        };
        FinalizedReview.SeverityCounts counts = review.counts();
        return verdict + " \u2014 " + count("BLOCKER", counts.blocker()) + " \u00B7 "
                + count("MEDIUM", counts.medium()) + " \u00B7 " + count("LOW", counts.low());
    }

    private static String count(String severity, int value) {
        return severityIcon(ReviewSeverity.valueOf(severity)) + " **" + value + "** "
                + severity.toLowerCase(java.util.Locale.ROOT);
    }

    private static void appendFindings(StringBuilder out, FinalizedReview review,
                                       ReviewLinkStyle links) {
        out.append("\n---\n\n");
        if (review.findings().isEmpty()) {
            out.append("## \u2705 Findings\n\n");
            out.append("No findings were reported in this change.\n");
            return;
        }
        out.append("## \uD83D\uDD0E Findings \u00B7 ").append(review.findings().size()).append("\n\n");
        for (FinalizedReview.Finding finding : ordered(review.findings())) {
            appendFinding(out, finding, links);
        }
    }

    private static void appendFinding(StringBuilder out, FinalizedReview.Finding finding,
                                      ReviewLinkStyle links) {
        out.append("### ").append(severityIcon(finding.severity())).append(' ')
                .append(finding.severity()).append(" \u00B7 `").append(inline(finding.reference()))
                .append("` \u00B7 ").append(inline(finding.title())).append("\n\n");

        appendField(out, "Explanation", finding.explanation());

        if (finding.locations().isEmpty()) {
            out.append("**Where**\n\n_No source location was recorded for this finding._\n\n");
        } else {
            out.append("**Where**\n\n");
            for (FinalizedReview.Location location : finding.locations()) {
                out.append("- \uD83D\uDCCD ").append(locationLabel(location, links)).append(" \u00B7 ")
                        .append(location.side()).append("\n");
                out.append("  > ").append(inline(location.description())).append('\n');
            }
            out.append('\n');
        }

        appendField(out, "Impact", finding.impact());
        appendField(out, "Recommendation", finding.recommendation());
    }

    private static void appendField(StringBuilder out, String label, String value) {
        out.append("**").append(label).append("**\n\n").append(block(value)).append("\n\n");
    }

    private static void appendCoverage(StringBuilder out, FinalizedReview review) {
        out.append("---\n\n## \uD83E\uDDED Coverage and limitations\n\n");
        appendField(out, "Reviewed", review.reviewedAreas());
        appendField(out, "Not verified", review.limitations());
        if (review.coverageNote() != null && !review.coverageNote().isBlank()) {
            appendField(out, "Coverage note", review.coverageNote());
        }
        String rendered = out.toString().stripTrailing();
        out.setLength(0);
        out.append(rendered).append('\n');
    }

    /**
     * Instance branding, configured per deployment and rendered as Markdown. An empty or blank value
     * publishes the review without a footer instead of leaving a stray rule behind.
     */
    private void appendFooter(StringBuilder out) {
        String footer = properties == null ? null : properties.getFooter();
        if (footer == null || footer.isBlank()) {
            return;
        }
        out.append("\n---\n\n").append(block(footer)).append('\n');
    }

    private static String severityIcon(ReviewSeverity severity) {
        return switch (severity) {
            case BLOCKER -> "\uD83D\uDD34";
            case MEDIUM -> "\uD83D\uDFE0";
            case LOW -> "\uD83D\uDFE1";
        };
    }

    /** Path, lines and side in code style, linked when the provider can address the line range. */
    private static String locationLabel(FinalizedReview.Location location, ReviewLinkStyle links) {
        String text = location.path() + lineSuffix(location);
        String url = links.fileUrl(new ReviewLinkStyle.FileRef(location.path(), location.side(),
                location.startLine(), location.endLine()));
        return url == null ? "`" + text + "`" : "[`" + text + "`](" + url + ")";
    }

    private static String lineSuffix(FinalizedReview.Location location) {
        if (location.startLine() == null) {
            return "";
        }
        if (location.endLine() == null || location.endLine().equals(location.startLine())) {
            return ":" + location.startLine();
        }
        return ":" + location.startLine() + "\u2013" + location.endLine();
    }

    private static List<FinalizedReview.Finding> ordered(List<FinalizedReview.Finding> findings) {
        return findings.stream()
                .sorted(Comparator
                        .comparingInt((FinalizedReview.Finding finding) -> rank(finding.severity()))
                        .thenComparingInt(finding -> finding.locations().isEmpty() ? 1 : 0)
                        .thenComparing(ReviewCommentRenderer::firstPath, String::compareTo)
                        .thenComparingInt(ReviewCommentRenderer::firstStartLine))
                .toList();
    }

    private static int rank(ReviewSeverity severity) {
        return switch (severity) {
            case BLOCKER -> 0;
            case MEDIUM -> 1;
            case LOW -> 2;
        };
    }

    private static String firstPath(FinalizedReview.Finding finding) {
        return finding.locations().isEmpty() ? "" : finding.locations().getFirst().path();
    }

    private static int firstStartLine(FinalizedReview.Finding finding) {
        if (finding.locations().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Integer start = finding.locations().getFirst().startLine();
        return start == null ? Integer.MAX_VALUE : start;
    }

    /** Single-line text: a value that would break the line layout is collapsed. */
    static String inline(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return stripControl(value).replaceAll("\\s+", " ").strip();
    }

    /**
     * Multi-line field content. Blank-line runs are collapsed, and lines that would open review
     * structure are escaped: a title or explanation must never be able to create a heading, quote,
     * table, fence, rule or a second, fake finding block.
     */
    static String block(String value) {
        if (value == null || value.isBlank()) {
            return NOT_SPECIFIED;
        }
        StringBuilder out = new StringBuilder();
        boolean previousBlank = true;
        for (String raw : stripControl(value).replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank()) {
                if (previousBlank) {
                    continue;
                }
                previousBlank = true;
                out.append('\n');
                continue;
            }
            previousBlank = false;
            out.append(guard(line)).append('\n');
        }
        String rendered = out.toString().stripTrailing();
        return rendered.isEmpty() ? NOT_SPECIFIED : rendered;
    }

    private static String guard(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("#") || stripped.startsWith(">") || stripped.startsWith("|")
                || FENCE.matcher(stripped).lookingAt()
                || THEMATIC_BREAK.matcher(stripped).matches()
                || looksLikeFindingHeader(stripped)) {
            return "\\" + line;
        }
        return line;
    }

    /** Matches a finding header in model text, with or without a leading severity icon. */
    private static boolean looksLikeFindingHeader(String line) {
        String withoutPrefix = line.replaceAll("^[^\\p{L}]+", "");
        return FINDING_HEADER_LIKE.matcher(withoutPrefix).lookingAt();
    }

    private static String stripControl(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t') {
                out.append("    ");
            } else if (c >= 0x20 || c == '\n' || c == '\r') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
