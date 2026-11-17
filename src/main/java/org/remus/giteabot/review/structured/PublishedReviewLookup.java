package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read access to the reviews this bot already published on a pull request — what a follow-up agent
 * uses to resolve a reference instead of being handed a static digest of everything.
 *
 * <p>Every lookup is bound to the caller's own scope (integration, bot, repository, pull request), so
 * a guessed reference from another repository simply does not exist here. Only finalized reviews are
 * visible, and withdrawn findings are omitted: they were retracted before publication, so describing
 * them would misrepresent what was posted.</p>
 *
 * <p>All results are bounded, because a tool result is prompt text: a call returns either the
 * reviews' index, one target in full, or the matching findings, and says so when it had to stop.</p>
 */
@Service
@RequiredArgsConstructor
public class PublishedReviewLookup {

    static final int MAX_REVIEWS_SCANNED = 25;
    static final int MAX_LISTED_REVIEWS = 10;
    static final int MAX_LISTED_FINDINGS = 6;
    static final int MAX_SEARCH_HITS = 10;
    static final int MAX_TEXT_CHARS = 6_000;
    static final int MAX_FIELD_CHARS = 600;

    private final WorkflowOutputRepository outputRepository;
    private final ReviewRecordRepository reviewRepository;

    /** One published review, as far as a lookup needs to know it. */
    private record Published(WorkflowOutput output, ReviewRecord record) {

        List<ReviewFinding> activeFindings() {
            return record.getFindings().stream().filter(finding -> !finding.isWithdrawn()).toList();
        }
    }

    /** The reviews published on this pull request, newest first, with their finding references. */
    @Transactional(readOnly = true)
    public String list(Long gitIntegrationId, Long botId, String repoOwner, String repoName,
                       Long prNumber) {
        List<Published> published = published(gitIntegrationId, botId, repoOwner, repoName, prNumber);
        if (published.isEmpty()) {
            return "No agentic review has been published on this pull request yet.";
        }
        StringBuilder out = new StringBuilder("Agentic reviews published on this pull request, newest "
                + "first:\n");
        int listed = 0;
        for (Published review : published) {
            if (listed == MAX_LISTED_REVIEWS) {
                out.append("\nMore reviews exist; ask for a specific reference.\n");
                break;
            }
            out.append("\nReview ").append(review.output().getReference())
                    .append(" \u00B7 ").append(conclusionOf(review)).append(" \u00B7 ")
                    .append(countsOf(review)).append('\n');
            List<ReviewFinding> findings = review.activeFindings();
            for (ReviewFinding finding : findings.subList(0, Math.min(MAX_LISTED_FINDINGS,
                    findings.size()))) {
                out.append("  - ").append(findingReference(review, finding)).append(" \u00B7 ")
                        .append(finding.getSeverity()).append(" \u00B7 ")
                        .append(oneLine(finding.getTitle())).append('\n');
            }
            if (findings.size() > MAX_LISTED_FINDINGS) {
                out.append("  - … ").append(findings.size() - MAX_LISTED_FINDINGS)
                        .append(" more finding(s)\n");
            }
            listed++;
        }
        out.append("\nRead one with `reference`: O<number> for the review, O<number>-F<number> for a "
                + "finding, O<number>-A for its assessment.");
        return bound(out);
    }

    /** One target in full: the review, a single finding, or the assessment. */
    @Transactional(readOnly = true)
    public String byReference(Long gitIntegrationId, Long botId, String repoOwner, String repoName,
                              Long prNumber, String reference) {
        Optional<FeedbackTargetView> target = FeedbackTargetView.parse(reference);
        if (target.isEmpty()) {
            return "'" + reference + "' is not a review reference. They look like O12, O12-F3 or O12-A.";
        }
        Published published = published(gitIntegrationId, botId, repoOwner, repoName, prNumber).stream()
                .filter(candidate -> candidate.output().getReference()
                        .equalsIgnoreCase(target.get().outputReference()))
                .findFirst()
                .orElse(null);
        if (published == null) {
            return "No review " + target.get().outputReference()
                    + " is available on this pull request.";
        }
        StringBuilder out = new StringBuilder();
        switch (target.get().kind()) {
            case REVIEW -> {
                out.append(reviewBlock(published, true));
            }
            case ASSESSMENT -> {
                out.append(assessmentBlock(published));
            }
            case FINDING -> {
                ReviewFinding finding = published.activeFindings().stream()
                        .filter(candidate -> candidate.getOrdinal() == target.get().ordinal())
                        .findFirst()
                        .orElse(null);
                if (finding == null) {
                    return "Finding " + reference + " is not part of review "
                            + published.output().getReference()
                            + " (it may have been withdrawn before publication).";
                }
                out.append(findingBlock(published, finding, true));
            }
        }
        return bound(out);
    }

    /** Findings whose title, explanation, category or path contains {@code query}. */
    @Transactional(readOnly = true)
    public String search(Long gitIntegrationId, Long botId, String repoOwner, String repoName,
                         Long prNumber, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return "Pass `query` (text to search for) or `reference`, or call without arguments to "
                    + "list the published reviews.";
        }
        StringBuilder out = new StringBuilder();
        int hits = 0;
        for (Published review : published(gitIntegrationId, botId, repoOwner, repoName, prNumber)) {
            for (ReviewFinding finding : review.activeFindings()) {
                if (!matches(finding, needle)) {
                    continue;
                }
                if (hits == MAX_SEARCH_HITS) {
                    out.append("\nMore findings match; narrow the query.\n");
                    return bound(out);
                }
                out.append("- ").append(findingReference(review, finding)).append(" \u00B7 ")
                        .append(finding.getSeverity()).append(" \u00B7 ")
                        .append(oneLine(finding.getTitle())).append('\n');
                String location = finding.getLocations().stream().findFirst()
                        .map(PublishedReviewLookup::describeLocation).orElse(null);
                if (location != null) {
                    out.append("  at ").append(location).append('\n');
                }
                hits++;
            }
        }
        if (hits == 0) {
            return "No published finding matches '" + query + "' on this pull request.";
        }
        out.append("\nRead one with `reference`, for example ").append("O12-F1")
                .append(" (use the reference shown above).");
        return bound(out);
    }

    // ------------------------------------------------------------------ internals

    private List<Published> published(Long gitIntegrationId, Long botId, String repoOwner,
                                      String repoName, Long prNumber) {
        if (gitIntegrationId == null || botId == null || repoOwner == null || repoName == null
                || prNumber == null) {
            return List.of();
        }
        List<WorkflowOutput> outputs = outputRepository.findByScope(gitIntegrationId, botId, repoOwner,
                repoName, prNumber, StructuredReviewService.WORKFLOW_KEY,
                PageRequest.of(0, MAX_REVIEWS_SCANNED));
        if (outputs == null || outputs.isEmpty()) {
            return List.of();
        }
        List<Published> published = new ArrayList<>();
        for (WorkflowOutput output : outputs) {
            ReviewRecord record = reviewRepository.findByOutputId(output.getId()).orElse(null);
            if (record != null && record.getState() == ReviewState.FINALIZED) {
                published.add(new Published(output, record));
            }
        }
        return published;
    }

    private String reviewBlock(Published published, boolean withFindings) {
        StringBuilder out = new StringBuilder();
        out.append("Review ").append(published.output().getReference()).append(" \u00B7 ")
                .append(conclusionOf(published)).append(" \u00B7 ").append(countsOf(published))
                .append('\n');
        out.append(assessmentBlock(published));
        if (withFindings) {
            List<ReviewFinding> findings = published.activeFindings();
            if (findings.isEmpty()) {
                out.append("\nNo active findings.\n");
            } else {
                out.append("\nFindings:\n");
                for (ReviewFinding finding : findings) {
                    out.append('\n').append(findingBlock(published, finding, false));
                }
            }
        }
        return out.toString();
    }

    private String assessmentBlock(Published published) {
        ReviewAssessment assessment = published.record().getAssessment();
        if (assessment == null) {
            return "Assessment " + published.output().getReference() + "-A: not recorded.\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("Assessment ").append(published.output().getReference()).append("-A \u00B7 ")
                .append(assessment.getConclusion()).append('\n');
        out.append("Summary: ").append(truncate(oneLine(assessment.getSummary()))).append('\n');
        if (assessment.getReviewedAreas() != null && !assessment.getReviewedAreas().isBlank()) {
            out.append("Reviewed: ").append(truncate(oneLine(assessment.getReviewedAreas())))
                    .append('\n');
        }
        if (assessment.getLimitations() != null && !assessment.getLimitations().isBlank()) {
            out.append("Not verified: ").append(truncate(oneLine(assessment.getLimitations())))
                    .append('\n');
        }
        return out.toString();
    }

    private String findingBlock(Published published, ReviewFinding finding, boolean detailed) {
        StringBuilder out = new StringBuilder();
        out.append(findingReference(published, finding)).append(" \u00B7 ")
                .append(finding.getSeverity()).append(" \u00B7 ")
                .append(oneLine(finding.getTitle())).append('\n');
        if (detailed) {
            out.append("Why: ").append(truncate(oneLine(finding.getExplanation()))).append('\n');
            out.append("Impact: ").append(truncate(oneLine(finding.getImpact()))).append('\n');
            out.append("Recommendation: ").append(truncate(oneLine(finding.getRecommendation())))
                    .append('\n');
            for (ReviewFindingLocation location : finding.getLocations()) {
                out.append("At: ").append(describeLocation(location)).append(" \u00B7 ")
                        .append(truncate(oneLine(location.getDescription()))).append('\n');
            }
        } else {
            out.append("  ").append(truncate(oneLine(finding.getExplanation()))).append('\n');
        }
        return out.toString();
    }

    private static boolean matches(ReviewFinding finding, String needle) {
        return contains(finding.getTitle(), needle) || contains(finding.getExplanation(), needle)
                || contains(finding.getCategory(), needle)
                || finding.getLocations().stream()
                        .anyMatch(location -> contains(location.getPath(), needle));
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String conclusionOf(Published published) {
        ReviewAssessment assessment = published.record().getAssessment();
        return assessment == null ? "FINALIZED" : assessment.getConclusion().name();
    }

    private static String countsOf(Published published) {
        List<ReviewFinding> findings = published.activeFindings();
        long blocker = findings.stream().filter(f -> f.getSeverity() == ReviewSeverity.BLOCKER).count();
        long medium = findings.stream().filter(f -> f.getSeverity() == ReviewSeverity.MEDIUM).count();
        long low = findings.stream().filter(f -> f.getSeverity() == ReviewSeverity.LOW).count();
        return blocker + " blocker, " + medium + " medium, " + low + " low";
    }

    private static String findingReference(Published published, ReviewFinding finding) {
        return ReviewReferences.finding(published.output().getReference(), finding.getOrdinal());
    }

    private static String describeLocation(ReviewFindingLocation location) {
        StringBuilder text = new StringBuilder(location.getPath());
        if (location.getStartLine() != null) {
            text.append(':').append(location.getStartLine());
            if (location.getEndLine() != null && !location.getEndLine().equals(location.getStartLine())) {
                text.append('-').append(location.getEndLine());
            }
        }
        return text.append(" (").append(location.getSide()).append(')').toString();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String value) {
        return value.length() <= MAX_FIELD_CHARS ? value
                : value.substring(0, MAX_FIELD_CHARS) + "\u2026";
    }

    private static String bound(StringBuilder out) {
        if (out.length() <= MAX_TEXT_CHARS) {
            return out.toString().stripTrailing();
        }
        return out.substring(0, MAX_TEXT_CHARS) + "\n… (result truncated; ask for a narrower "
                + "reference or query)";
    }

    /** Parsed form of a user-supplied reference: {@code O12}, {@code O12-F3}, {@code O12-A}. */
    record FeedbackTargetView(Kind kind, String outputReference, int ordinal) {

        enum Kind { REVIEW, FINDING, ASSESSMENT }

        static Optional<FeedbackTargetView> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String trimmed = raw.trim().toUpperCase(Locale.ROOT);
            boolean assessment = trimmed.endsWith("-A");
            String withoutSuffix = assessment ? trimmed.substring(0, trimmed.length() - 2) : trimmed;
            int f = withoutSuffix.lastIndexOf("-F");
            if (f > 0) {
                try {
                    int ordinal = Integer.parseInt(withoutSuffix.substring(f + 2));
                    return Optional.of(new FeedbackTargetView(Kind.FINDING,
                            withoutSuffix.substring(0, f), ordinal));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            if (!withoutSuffix.matches("O[1-9][0-9]*")) {
                return Optional.empty();
            }
            return Optional.of(new FeedbackTargetView(
                    assessment ? Kind.ASSESSMENT : Kind.REVIEW, withoutSuffix, 0));
        }
    }
}
