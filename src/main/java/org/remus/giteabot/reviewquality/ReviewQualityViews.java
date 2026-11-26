package org.remus.giteabot.reviewquality;

import org.remus.giteabot.reviewui.ReviewUiViews.PageOf;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** View models of the Review quality page, one per tab. Numbers arrive formatted for the locale. */
public final class ReviewQualityViews {

    private ReviewQualityViews() {
    }

    /**
     * A rate with its numerator and denominator. Below the minimum sample it is not shown: the
     * page prints "–" instead of a misleading percentage, and the counts on hover.
     */
    public record Rate(long numerator, long denominator, boolean sufficient) {

        public Integer percent() {
            return sufficient && denominator > 0 ? (int) Math.round(100.0 * numerator / denominator) : null;
        }

        public String text() {
            Integer percent = percent();
            return percent == null ? "–" : percent + "%";
        }

        public String counts() {
            return numerator + " / " + denominator;
        }
    }

    /** One KPI card: label and hint are message keys, detail a key with arguments. */
    public record Kpi(String labelKey, String value, String detailKey, Object[] detailArgs, String delta,
                      String tone, String hintKey, String title) {
    }

    /** A small line chart in a table cell: SVG points, coloured by whether the figure fell. */
    public record Sparkline(String points, boolean falling) {

        public static final int WIDTH = 90;
        public static final int HEIGHT = 22;
    }

    /** Shares of blocker / medium / low findings, in percent. */
    public record SeverityMix(int blocker, int medium, int low) {
    }

    /** Blocker / medium / low counts. */
    public record Counts(int blocker, int medium, int low) {

        public int total() {
            return blocker + medium + low;
        }
    }

    /** Since when a kind of data exists ({@code null}: not yet recorded). */
    public record Since(String messageKey, Instant since) {
    }

    public record OverviewView(List<Kpi> kpis, Map<String, Object> charts, List<Since> notes) {
    }

    public record PrRow(String project, long number, String title, String author, String botName, int rounds,
                        Counts first, Counts last, Integer resolved, Integer persisting, Integer added,
                        int blockersLeft, String outcomeKey, Long daysToMerge, boolean blockersAtMerge,
                        String reviewsQuery) {
    }

    public record PrsView(List<Kpi> kpis, PageOf<PrRow> rows, Map<String, Object> charts, List<Since> notes) {
    }

    public record RepositoryRow(String project, long prs, long reviews, String reviewsPerPr, String findingsPerPr,
                                String blockersPerPr, double blockersPerPrValue, SeverityMix mix, Rate resolved,
                                Rate precision, Sparkline trend, String costPerPr, String reviewsQuery) {
    }

    public record RepositoriesView(PageOf<RepositoryRow> rows, List<Since> notes) {
    }

    public record AuthorRow(String author, boolean others, int othersCount, long prs, String findingsPerPr,
                            String blockersPerPr, List<String> categories, Rate resolved, String averageRounds,
                            Sparkline trend) {
    }

    public record AuthorsView(boolean enabled, int minPrs, PageOf<AuthorRow> rows, List<Since> notes) {
    }

    public record HotSpotRow(String project, String directory, long findings, long prs, String topCategory) {
    }

    public record TopicsView(Map<String, Object> charts, PageOf<HotSpotRow> rows, int depth) {
    }

    public record WrongRow(String category, long judged, Rate wrong, List<String> lessons, String proposeQuery) {
    }

    public record LessonRow(String reference, int revision, String project, String botName, String category,
                            List<String> paths, Instant activeSince, long usage, boolean reviewRequired,
                            Rate incorrectBefore, Rate incorrectAfter, String findingsBefore,
                            String findingsAfter) {
    }

    public record AccuracyView(List<Kpi> kpis, Map<String, Object> charts, List<WrongRow> wrong,
                               PageOf<LessonRow> lessons, int lessonWindowDays) {
    }

    public record CostView(List<Kpi> kpis, Map<String, Object> charts, ReviewQualitySettings assumptions,
                           boolean money, String unpricedTokens, String reviewCost, String followUpCost,
                           List<Since> notes) {
    }
}
