package org.remus.giteabot.review.structured;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders a finalized review into the single application-owned comment layout: assessment,
 * severity totals, findings, coverage. The model supplies field content only — headings,
 * ordering, counts, references and the feedback footer position belong to this class, so
 * every agentic review looks the same regardless of provider or learning settings.
 *
 * <p>Display order is severity, then location, then the finding's ordinal (the input is
 * ordinal-ordered and the sort is stable). The ordinal is never derived from display
 * order, which is why re-sorting cannot renumber a finding.</p>
 */
@Component
public class ReviewCommentRenderer {

    /** Header shape of a finding; model text matching it is neutralized so it cannot spoof one. */
    private static final Pattern FINDING_HEADER_LIKE =
            Pattern.compile("^(BLOCKER|MEDIUM|LOW)\\s*\u00B7");

    static final String INCONCLUSIVE_NOTICE = "**Inconclusive review** — coverage was incomplete. "
            + "Read the limitations below before relying on this review.";

    private static final String NOT_SPECIFIED = "not specified";

    public String render(FinalizedReview review) {
        return render(review, ReviewLinkStyle.NONE);
    }

    public String render(FinalizedReview review, ReviewLinkStyle linkStyle) {
        ReviewLinkStyle links = linkStyle == null ? ReviewLinkStyle.NONE : linkStyle;
        StringBuilder out = new StringBuilder();
        out.append("AI Review \u00B7 ").append(inline(review.reference())).append("\n\n");
        out.append("Assessment \u00B7 ").append(inline(review.assessmentReference())).append('\n');
        out.append(block(review.summary())).append("\n\n");
        out.append("Findings: ").append(review.counts().blocker()).append(" blocker \u00B7 ")
                .append(review.counts().medium()).append(" medium \u00B7 ")
                .append(review.counts().low()).append(" low\n");
        if (review.conclusion() == ReviewConclusion.INCONCLUSIVE) {
            out.append('\n').append(INCONCLUSIVE_NOTICE).append('\n');
        }
        if (review.findings().isEmpty()) {
            out.append("\nNo findings were reported.\n");
        } else {
            for (FinalizedReview.Finding finding : ordered(review.findings())) {
                out.append('\n');
                appendFinding(out, finding, links);
            }
        }
        out.append("\nCoverage and limitations\n");
        out.append("Reviewed: ").append(block(review.reviewedAreas())).append('\n');
        out.append("Not verified: ").append(block(review.limitations())).append('\n');
        if (review.coverageNote() != null && !review.coverageNote().isBlank()) {
            out.append("Coverage note: ").append(block(review.coverageNote())).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static void appendFinding(StringBuilder out, FinalizedReview.Finding finding,
                                      ReviewLinkStyle links) {
        out.append(finding.severity()).append(" \u00B7 ").append(finding.reference())
                .append(" \u00B7 ").append(inline(finding.title())).append('\n');
        out.append("Explanation: ").append(block(finding.explanation())).append('\n');
        for (FinalizedReview.Location location : finding.locations()) {
            out.append("Location: ").append(locationLabel(location, links)).append('\n');
            out.append("Evidence: ").append(block(location.description())).append('\n');
        }
        out.append("Impact: ").append(block(finding.impact())).append('\n');
        out.append("Recommendation: ").append(block(finding.recommendation())).append('\n');
    }

    private static String locationLabel(FinalizedReview.Location location, ReviewLinkStyle links) {
        String text = location.path() + lineSuffix(location) + " (" + location.side() + ")";
        String url = links.fileUrl(new ReviewLinkStyle.FileRef(location.path(), location.side(),
                location.startLine(), location.endLine()));
        return url == null ? text : "[" + text + "](" + url + ")";
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
                        .thenComparing(finding -> firstPath(finding), String::compareTo)
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
        return finding.locations().isEmpty() ? "" : finding.locations().get(0).path();
    }

    private static int firstStartLine(FinalizedReview.Finding finding) {
        if (finding.locations().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Integer start = finding.locations().get(0).startLine();
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
     * Multi-line field content. Blank-line runs are collapsed, and lines that would open a
     * Markdown heading/quote or imitate a finding header are escaped: a title or explanation
     * must never be able to create a second, fake finding block.
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
        if (line.startsWith("#") || line.startsWith(">")
                || FINDING_HEADER_LIKE.matcher(line).lookingAt()) {
            return "\\" + line;
        }
        return line;
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
