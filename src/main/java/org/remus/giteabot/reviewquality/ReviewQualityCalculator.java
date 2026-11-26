package org.remus.giteabot.reviewquality;

import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.Dataset;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.FeedbackFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.FindingFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.OutcomeFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.PrKey;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.ReviewFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.RunUsage;
import org.remus.giteabot.reviewquality.ReviewQualityFilterForm.Window;
import org.remus.giteabot.reviewquality.ReviewQualityLoader.ActiveLesson;
import org.remus.giteabot.reviewquality.ReviewQualityLoader.Prices;
import org.remus.giteabot.reviewquality.ReviewQualityViews.AccuracyView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.AuthorRow;
import org.remus.giteabot.reviewquality.ReviewQualityViews.AuthorsView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.CostView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.Counts;
import org.remus.giteabot.reviewquality.ReviewQualityViews.HotSpotRow;
import org.remus.giteabot.reviewquality.ReviewQualityViews.Kpi;
import org.remus.giteabot.reviewquality.ReviewQualityViews.LessonRow;
import org.remus.giteabot.reviewquality.ReviewQualityViews.OverviewView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.PrRow;
import org.remus.giteabot.reviewquality.ReviewQualityViews.PrsView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.Rate;
import org.remus.giteabot.reviewquality.ReviewQualityViews.RepositoriesView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.RepositoryRow;
import org.remus.giteabot.reviewquality.ReviewQualityViews.SeverityMix;
import org.remus.giteabot.reviewquality.ReviewQualityViews.Since;
import org.remus.giteabot.reviewquality.ReviewQualityViews.Sparkline;
import org.remus.giteabot.reviewquality.ReviewQualityViews.TopicsView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.WrongRow;
import org.remus.giteabot.reviewui.ReviewUiViews.PageOf;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aggregates the facts of one Review quality page. Pure: no database, no clock — so every
 * counting rule can be tested with plain data.
 *
 * <p>Counting rules: only finalized agentic reviews count (failed, cancelled and abandoned runs
 * only appear in "Review conclusions"); withdrawn findings never count. A pull request belongs to the
 * period of its first finalized review, and finding figures use that first review, so a pull request
 * reviewed five times weighs as much as one reviewed once. Feedback counts the effective record per
 * actor and target. A rate is only shown when it rests on at least the minimum sample.</p>
 */
final class ReviewQualityCalculator {

    static final String BLOCKER_COLOR = "#dc3545";
    static final String MEDIUM_COLOR = "#e0a800";
    static final String LOW_COLOR = "#8c959f";
    static final String OK_COLOR = "#198754";
    static final String INFO_COLOR = "#0d6efd";
    /** Days before and after a lesson's activation that are compared. */
    static final int LESSON_WINDOW_DAYS = 30;
    static final int WRONG_ROWS = 10;

    /** Message lookup of the request's locale. */
    interface Texts {
        String get(String key, Object... args);
    }

    record Inputs(Dataset data, Window window, Window comparison, Set<ReviewSeverity> severities, boolean monthly,
                  int minSample, Map<String, Prices> prices, ReviewQualitySettings settings, Instant now) {
    }

    /** What the page knows about one pull request reviewed in a period. */
    record PrStats(PrKey key, List<ReviewFact> reviews, Counts first, Counts last, Integer resolved,
                   Integer persisting, Integer added, int firstFindings, Integer roundsToClean,
                   OutcomeFact outcome, boolean blockersAtMerge) {

        ReviewFact firstReview() {
            return reviews.getFirst();
        }

        ReviewFact lastReview() {
            return reviews.getLast();
        }

        int rounds() {
            return reviews.size();
        }

        boolean resolvable() {
            return resolved != null;
        }
    }

    /** Cost of a set of runs: money where the AI integration has prices, tokens where it has none. */
    record Cost(double money, long pricedTokens, long unpricedTokens) {

        static final Cost NONE = new Cost(0, 0, 0);

        Cost plus(Cost other) {
            return new Cost(money + other.money, pricedTokens + other.pricedTokens,
                    unpricedTokens + other.unpricedTokens);
        }

        long tokens() {
            return pricedTokens + unpricedTokens;
        }
    }

    private final Inputs in;
    private final Texts texts;
    private final ReviewQualityFormats fmt;
    private final Map<Long, List<FindingFact>> findingsByReview = new HashMap<>();
    private final Map<Long, List<FeedbackFact>> feedbackByFinding = new HashMap<>();
    private final Map<PrKey, List<ReviewFact>> finalizedByPr = new LinkedHashMap<>();
    private final Map<Window, List<PrStats>> prsByWindow = new HashMap<>();

    ReviewQualityCalculator(Inputs in, Texts texts, Locale locale) {
        this.in = in;
        this.texts = texts;
        this.fmt = new ReviewQualityFormats(locale);
        for (FindingFact finding : in.data().findings()) {
            if (in.severities().contains(finding.severity())) {
                findingsByReview.computeIfAbsent(finding.reviewId(), id -> new ArrayList<>()).add(finding);
            }
        }
        for (FeedbackFact feedback : in.data().feedback()) {
            if (feedback.findingId() != null) {
                feedbackByFinding.computeIfAbsent(feedback.findingId(), id -> new ArrayList<>()).add(feedback);
            }
        }
        for (ReviewFact review : in.data().reviews()) {
            if (review.finalized()) {
                finalizedByPr.computeIfAbsent(review.pr(), key -> new ArrayList<>()).add(review);
            }
        }
        finalizedByPr.values().forEach(list -> list.sort(Comparator.comparing(ReviewFact::createdAt)
                .thenComparingLong(ReviewFact::outputId)));
    }

    // ================================================================== overview

    OverviewView overview() {
        List<Kpi> kpis = new ArrayList<>();
        List<PrStats> prs = prs(in.window());
        List<PrStats> before = in.comparison() == null ? null : prs(in.comparison());
        long reviews = finalizedIn(in.window()).size();

        kpis.add(new Kpi("reviewQuality.kpi.prs", String.valueOf(prs.size()), "reviewQuality.kpi.prs.detail",
                new Object[]{reviews, fmt.ratio(reviews, prs.size(), 1)},
                before == null ? null : percentDelta(prs.size(), before.size()), "secondary",
                "reviewQuality.kpi.prs.hint", null));
        kpis.add(averageKpi("reviewQuality.kpi.findings", "reviewQuality.kpi.findings.hint",
                prs, before, stats -> stats.first().total(), 1));
        kpis.add(averageKpi("reviewQuality.kpi.blockers", "reviewQuality.kpi.blockers.hint",
                prs, before, stats -> stats.first().blocker(), 2));
        Rate resolved = resolvedShare(prs);
        kpis.add(rateKpi("reviewQuality.kpi.resolved", "reviewQuality.kpi.resolved.hint", resolved,
                before == null ? null : resolvedShare(before), false,
                "reviewQuality.kpi.resolved.detail", new Object[]{resolved.denominator()}));
        List<FindingFact> findings = findingsOf(finalizedIn(in.window()));
        Rate precision = precision(findings);
        Rate coverage = coverage(findings);
        kpis.add(rateKpi("reviewQuality.kpi.precision", "reviewQuality.kpi.precision.hint", precision,
                in.comparison() == null ? null : precision(findingsOf(finalizedIn(in.comparison()))), false,
                "reviewQuality.kpi.precision.detail", new Object[]{coverage.text(), coverage.counts()}));
        Cost cost = total(runsIn(in.window()));
        kpis.add(new Kpi("reviewQuality.kpi.costPerPr", costText(cost, prs.size()),
                "reviewQuality.kpi.costPerPr.detail", new Object[]{costText(cost, 1)}, null, "secondary",
                "reviewQuality.kpi.costPerPr.hint", null));

        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("trendChart", trendChart(prs));
        charts.put("conclusionChart", conclusionChart());
        List<Since> notes = List.of(new Since("reviewQuality.since.fingerprints", in.data().since().fingerprints()),
                new Since("reviewQuality.since.diffSize", in.data().since().diffSize()),
                new Since("reviewQuality.since.runLinks", in.data().since().runLinks()));
        return new OverviewView(kpis, charts, notes);
    }

    private Map<String, Object> trendChart(List<PrStats> prs) {
        List<Instant> buckets = buckets(in.window());
        int n = buckets.size();
        double[][] perPr = new double[3][n];
        double[][] perLines = new double[3][n];
        long[] count = new long[n];
        long[] lines = new long[n];
        for (PrStats stats : prs) {
            int bucket = bucketIndex(buckets, stats.firstReview().createdAt());
            if (bucket < 0) {
                continue;
            }
            count[bucket]++;
            int[] values = {stats.first().blocker(), stats.first().medium(), stats.first().low()};
            Integer changed = stats.firstReview().changedLines();
            for (int s = 0; s < 3; s++) {
                perPr[s][bucket] += values[s];
                if (changed != null && changed > 0) {
                    perLines[s][bucket] += values[s];
                }
            }
            if (changed != null && changed > 0) {
                lines[bucket] += changed;
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        List<Map<String, Object>> linesSeries = new ArrayList<>();
        String[] keys = {"reviews.severity.BLOCKER", "reviews.severity.MEDIUM", "reviews.severity.LOW"};
        String[] colors = {BLOCKER_COLOR, MEDIUM_COLOR, LOW_COLOR};
        ReviewSeverity[] severities = {ReviewSeverity.BLOCKER, ReviewSeverity.MEDIUM, ReviewSeverity.LOW};
        for (int s = 0; s < 3; s++) {
            if (!in.severities().contains(severities[s])) {
                continue;
            }
            List<Double> values = new ArrayList<>();
            List<Double> valuesPerLines = new ArrayList<>();
            for (int b = 0; b < n; b++) {
                values.add(count[b] == 0 ? null : round(perPr[s][b] / count[b], 2));
                valuesPerLines.add(lines[b] == 0 ? null : round(perLines[s][b] * 1000.0 / lines[b], 2));
            }
            series.add(series(texts.get(keys[s]), values, colors[s], "bar", "y"));
            linesSeries.add(series(texts.get(keys[s]), valuesPerLines, colors[s], "bar", "y"));
        }
        List<Double> prCounts = new ArrayList<>();
        for (long c : count) {
            prCounts.add((double) c);
        }
        series.add(series(texts.get("reviewQuality.chart.prsReviewed"), prCounts, INFO_COLOR, "line", "y2"));
        linesSeries.add(series(texts.get("reviewQuality.chart.prsReviewed"), prCounts, INFO_COLOR, "line", "y2"));
        Map<String, Object> chart = chart("stacked", labels(buckets), series);
        chart.put("alternative", linesSeries);
        chart.put("yTitle", texts.get("reviewQuality.chart.findingsPerPr"));
        chart.put("yTitleAlternative", texts.get("reviewQuality.chart.findingsPerKloc"));
        chart.put("y2Title", texts.get("reviewQuality.chart.prs"));
        return chart;
    }

    private Map<String, Object> conclusionChart() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReviewConclusion conclusion : ReviewConclusion.values()) {
            counts.put(conclusion.name(), 0L);
        }
        long failed = 0;
        for (ReviewFact review : in.data().reviews()) {
            if (!in.window().contains(review.createdAt())) {
                continue;
            }
            if (review.finalized() && review.conclusion() != null) {
                counts.merge(review.conclusion().name(), 1L, Long::sum);
            } else if (review.state() != ReviewState.DRAFT && review.state() != ReviewState.FINALIZED) {
                failed++;
            }
        }
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        counts.forEach((conclusion, value) -> {
            labels.add(texts.get("reviews.conclusion." + conclusion));
            values.add((double) value);
        });
        labels.add(texts.get("reviewQuality.chart.failedCancelled"));
        values.add((double) failed);
        Map<String, Object> dataset = series(null, values, null, "doughnut", null);
        dataset.put("colors", List.of(MEDIUM_COLOR, OK_COLOR, LOW_COLOR, BLOCKER_COLOR));
        return chart("doughnut", labels, List.of(dataset));
    }

    // ================================================================== per pull request

    PrsView prs(int pageIndex, int pageSize, Function<PrKey, String> reviewsQuery) {
        List<PrStats> prs = prs(in.window());
        List<Kpi> kpis = new ArrayList<>();
        List<Integer> toClean = prs.stream().map(PrStats::roundsToClean).filter(Objects::nonNull).sorted().toList();
        long withBlockers = prs.stream().filter(stats -> stats.first().blocker() > 0).count();
        kpis.add(new Kpi("reviewQuality.kpi.roundsToClean", toClean.isEmpty() ? "–" : fmt.number(median(toClean), 1),
                "reviewQuality.kpi.roundsToClean.detail", new Object[]{toClean.size(), withBlockers}, null,
                "secondary", "reviewQuality.kpi.roundsToClean.hint", null));
        List<PrStats> merged = prs.stream().filter(stats -> stats.outcome() != null
                && stats.outcome().state() == PrOutcome.State.MERGED).toList();
        Rate atMerge = rate(merged.stream().filter(PrStats::blockersAtMerge).count(), merged.size());
        kpis.add(new Kpi("reviewQuality.kpi.blockersAtMerge", atMerge.text(), "reviewQuality.kpi.blockersAtMerge.detail",
                new Object[]{atMerge.numerator(), atMerge.denominator()}, null, "secondary",
                "reviewQuality.kpi.blockersAtMerge.hint", atMerge.counts()));
        Rate resolved = resolvedShare(prs);
        kpis.add(new Kpi("reviewQuality.kpi.resolved", resolved.text(), "reviewQuality.kpi.resolved.detail",
                new Object[]{resolved.denominator()}, null, "secondary", "reviewQuality.kpi.resolved.hint",
                resolved.counts()));

        List<PrStats> repeated = prs.stream().filter(stats -> stats.rounds() >= 2)
                .sorted(Comparator.comparing((PrStats stats) -> stats.lastReview().createdAt()).reversed())
                .toList();
        List<PrRow> rows = page(repeated, pageIndex, pageSize).stream().map(stats -> {
            ReviewFact first = stats.firstReview();
            OutcomeFact outcome = stats.outcome();
            String outcomeKey = outcome == null ? "reviewQuality.outcome.open"
                    : "reviewQuality.outcome." + outcome.state().name().toLowerCase(Locale.ROOT);
            Long days = outcome != null && outcome.state() == PrOutcome.State.MERGED && outcome.closedAt() != null
                    ? Math.max(0, Duration.between(first.createdAt(), outcome.closedAt()).toDays()) : null;
            String title = stats.reviews().stream().map(ReviewFact::title).filter(Objects::nonNull)
                    .reduce((a, b) -> b).orElse(null);
            String author = stats.reviews().stream().map(ReviewFact::author).filter(Objects::nonNull)
                    .reduce((a, b) -> b).orElse(null);
            return new PrRow(stats.key().project(), stats.key().number(), title, author,
                    in.data().botNames().get(stats.key().botId()), stats.rounds(), stats.first(), stats.last(),
                    stats.resolved(), stats.persisting(), stats.added(), stats.last().blocker(), outcomeKey, days,
                    stats.blockersAtMerge(), reviewsQuery.apply(stats.key()));
        }).toList();

        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("roundsChart", roundsChart(prs));
        List<Since> notes = List.of(new Since("reviewQuality.since.fingerprints", in.data().since().fingerprints()),
                new Since("reviewQuality.since.outcomes", in.data().since().outcomes()));
        return new PrsView(kpis, new PageOf<>(rows, pageIndex + 1, pageSize, repeated.size()), charts, notes);
    }

    private Map<String, Object> roundsChart(List<PrStats> prs) {
        double[] all = new double[4];
        double[] blockers = new double[4];
        long[] count = new long[4];
        for (PrStats stats : prs) {
            for (int round = 0; round < stats.rounds(); round++) {
                int slot = Math.min(round, 3);
                Counts counts = counts(stats.reviews().get(round));
                all[slot] += counts.total();
                blockers[slot] += counts.blocker();
                count[slot]++;
            }
        }
        List<Double> allValues = new ArrayList<>();
        List<Double> blockerValues = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            allValues.add(count[slot] == 0 ? null : round(all[slot] / count[slot], 2));
            blockerValues.add(count[slot] == 0 ? null : round(blockers[slot] / count[slot], 2));
        }
        List<String> labels = List.of(texts.get("reviewQuality.chart.round", 1), texts.get("reviewQuality.chart.round", 2),
                texts.get("reviewQuality.chart.round", 3), texts.get("reviewQuality.chart.roundPlus", 4));
        return chart("line", labels, List.of(
                series(texts.get("reviewQuality.chart.openFindings"), allValues, INFO_COLOR, "line", "y"),
                series(texts.get("reviews.severity.BLOCKER"), blockerValues, BLOCKER_COLOR, "line", "y")));
    }

    // ================================================================== repositories

    RepositoriesView repositories(int pageIndex, int pageSize, Function<String, String> reviewsQuery) {
        Map<String, List<PrStats>> byProject = new TreeMap<>();
        for (PrStats stats : prs(in.window())) {
            byProject.computeIfAbsent(stats.key().project(), key -> new ArrayList<>()).add(stats);
        }
        Map<String, List<ReviewFact>> reviewsByProject = finalizedIn(in.window()).stream()
                .collect(Collectors.groupingBy(review -> review.owner() + "/" + review.repo()));
        Map<String, Cost> costByProject = new HashMap<>();
        for (RunUsage run : runsIn(in.window())) {
            costByProject.merge(run.owner() + "/" + run.repo(), cost(run), Cost::plus);
        }
        List<Instant> buckets = buckets(in.window());
        List<RepositoryRow> all = new ArrayList<>();
        byProject.forEach((project, prs) -> {
            int[] totals = new int[3];
            for (PrStats stats : prs) {
                totals[0] += stats.first().blocker();
                totals[1] += stats.first().medium();
                totals[2] += stats.first().low();
            }
            int findings = totals[0] + totals[1] + totals[2];
            List<ReviewFact> reviews = reviewsByProject.getOrDefault(project, List.of());
            double blockersPerPr = (double) totals[0] / prs.size();
            all.add(new RepositoryRow(project, prs.size(), reviews.size(), fmt.ratio(reviews.size(), prs.size(), 1),
                    fmt.ratio(findings, prs.size(), 1), fmt.ratio(totals[0], prs.size(), 2), blockersPerPr,
                    mix(totals), resolvedShare(prs), precision(findingsOf(reviews)), trend(prs, buckets),
                    costText(costByProject.getOrDefault(project, Cost.NONE), prs.size()), reviewsQuery.apply(project)));
        });
        all.sort(Comparator.comparingDouble(RepositoryRow::blockersPerPrValue).reversed()
                .thenComparing(RepositoryRow::project));
        return new RepositoriesView(new PageOf<>(page(all, pageIndex, pageSize), pageIndex + 1, pageSize, all.size()),
                List.of(new Since("reviewQuality.since.fingerprints", in.data().since().fingerprints()),
                        new Since("reviewQuality.since.runLinks", in.data().since().runLinks())));
    }

    // ================================================================== authors

    AuthorsView authors(boolean enabled, int minPrs, int pageIndex, int pageSize) {
        List<Since> notes = List.of(new Since("reviewQuality.since.authors", in.data().since().authors()));
        if (!enabled) {
            return new AuthorsView(false, minPrs, new PageOf<>(List.of(), 1, pageSize, 0), notes);
        }
        Map<String, List<PrStats>> byAuthor = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PrStats stats : prs(in.window())) {
            String author = stats.firstReview().author();
            if (author != null && !author.isBlank()) {
                byAuthor.computeIfAbsent(author.trim(), key -> new ArrayList<>()).add(stats);
            }
        }
        List<Instant> buckets = buckets(in.window());
        List<AuthorRow> rows = new ArrayList<>();
        List<PrStats> others = new ArrayList<>();
        int otherAuthors = 0;
        for (Map.Entry<String, List<PrStats>> entry : byAuthor.entrySet()) {
            if (entry.getValue().size() < minPrs) {
                others.addAll(entry.getValue());
                otherAuthors++;
            } else {
                rows.add(authorRow(entry.getKey(), false, 0, entry.getValue(), buckets));
            }
        }
        if (!others.isEmpty()) {
            rows.add(authorRow(null, true, otherAuthors, others, buckets));
        }
        return new AuthorsView(true, minPrs, new PageOf<>(page(rows, pageIndex, pageSize), pageIndex + 1, pageSize,
                rows.size()), notes);
    }

    private AuthorRow authorRow(String author, boolean others, int othersCount, List<PrStats> prs,
                                List<Instant> buckets) {
        int findings = 0;
        int blockers = 0;
        int rounds = 0;
        Map<String, Integer> categories = new HashMap<>();
        for (PrStats stats : prs) {
            findings += stats.first().total();
            blockers += stats.first().blocker();
            rounds += stats.rounds();
            for (FindingFact finding : findingsByReview.getOrDefault(stats.firstReview().reviewId(), List.of())) {
                categories.merge(finding.category(), 1, Integer::sum);
            }
        }
        List<String> top = categories.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(3).map(Map.Entry::getKey).toList();
        return new AuthorRow(author, others, othersCount, prs.size(), fmt.ratio(findings, prs.size(), 1),
                fmt.ratio(blockers, prs.size(), 2), top, resolvedShare(prs), fmt.ratio(rounds, prs.size(), 1),
                trend(prs, buckets));
    }

    // ================================================================== categories and hot spots

    TopicsView topics(int depth, int pageIndex, int pageSize) {
        List<PrStats> prs = prs(in.window());
        Map<String, Long> now = categoryCounts(prs);
        Map<String, Long> before = in.comparison() == null ? null : categoryCounts(prs(in.comparison()));
        long total = now.values().stream().mapToLong(Long::longValue).sum();
        long totalBefore = before == null ? 0 : before.values().stream().mapToLong(Long::longValue).sum();
        List<Map.Entry<String, Long>> sorted = now.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(15).toList();
        List<String> labels = new ArrayList<>();
        List<Double> shares = new ArrayList<>();
        List<Double> changes = new ArrayList<>();
        for (Map.Entry<String, Long> entry : sorted) {
            labels.add(entry.getKey());
            double share = total == 0 ? 0 : 100.0 * entry.getValue() / total;
            shares.add(round(share, 1));
            if (before != null && totalBefore > 0) {
                changes.add(round(share - 100.0 * before.getOrDefault(entry.getKey(), 0L) / totalBefore, 1));
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        series.add(series(texts.get("reviewQuality.chart.share"), shares, INFO_COLOR, "bar", "x"));
        if (!changes.isEmpty()) {
            Map<String, Object> change = series(texts.get("reviewQuality.chart.change"), changes, OK_COLOR, "bar", "x");
            change.put("colors", changes.stream().map(value -> value != null && value > 0 ? BLOCKER_COLOR : OK_COLOR).toList());
            series.add(change);
        }
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("categoryChart", chart("hbar", labels, series));

        Map<String, long[]> spots = new HashMap<>();
        Map<String, Set<PrKey>> spotPrs = new HashMap<>();
        Map<String, Map<String, Integer>> spotCategories = new HashMap<>();
        for (PrStats stats : prs) {
            for (FindingFact finding : findingsByReview.getOrDefault(stats.firstReview().reviewId(), List.of())) {
                if (finding.severity() == ReviewSeverity.LOW) {
                    continue;
                }
                String key = stats.key().project() + "\u0000" + directory(finding.path(), depth);
                spots.computeIfAbsent(key, k -> new long[1])[0]++;
                spotPrs.computeIfAbsent(key, k -> new HashSet<>()).add(stats.key());
                spotCategories.computeIfAbsent(key, k -> new HashMap<>()).merge(finding.category(), 1, Integer::sum);
            }
        }
        List<HotSpotRow> rows = spots.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("\u0000", 2);
            String top = spotCategories.get(entry.getKey()).entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            return new HotSpotRow(parts[0], parts[1], entry.getValue()[0], spotPrs.get(entry.getKey()).size(), top);
        }).sorted(Comparator.comparingLong(HotSpotRow::findings).reversed()
                .thenComparing(HotSpotRow::project).thenComparing(HotSpotRow::directory)).toList();
        return new TopicsView(charts, new PageOf<>(page(rows, pageIndex, pageSize), pageIndex + 1, pageSize,
                rows.size()), depth);
    }

    private Map<String, Long> categoryCounts(List<PrStats> prs) {
        Map<String, Long> counts = new HashMap<>();
        for (PrStats stats : prs) {
            for (FindingFact finding : findingsByReview.getOrDefault(stats.firstReview().reviewId(), List.of())) {
                counts.merge(finding.category(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** The first {@code depth} directories of {@code path}; the repository root is "/". */
    static String directory(String path, int depth) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./") || normalized.startsWith("/")) {
            normalized = normalized.startsWith("./") ? normalized.substring(2) : normalized.substring(1);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) {
            return "/";
        }
        String[] segments = normalized.substring(0, slash).split("/");
        int keep = Math.min(depth, segments.length);
        return String.join("/", java.util.Arrays.copyOf(segments, keep)) + "/";
    }

    // ================================================================== accuracy and lessons

    AccuracyView accuracy(List<ActiveLesson> lessons, int pageIndex, int pageSize, Function<String, String> proposeQuery) {
        List<FindingFact> findings = findingsOf(finalizedIn(in.window()));
        Rate precision = precision(findings);
        Rate coverage = coverage(findings);
        List<Kpi> kpis = List.of(
                rateKpi("reviewQuality.kpi.precision", "reviewQuality.kpi.precision.hint", precision,
                        in.comparison() == null ? null : precision(findingsOf(finalizedIn(in.comparison()))), false,
                        "reviewQuality.kpi.judged", new Object[]{precision.denominator()}),
                rateKpi("reviewQuality.kpi.coverage", "reviewQuality.kpi.coverage.hint", coverage, null, false,
                        "reviewQuality.kpi.coverage.detail", new Object[]{coverage.numerator(), coverage.denominator()}),
                new Kpi("reviewQuality.kpi.activeLessons", String.valueOf(lessons.size()),
                        "reviewQuality.kpi.activeLessons.detail",
                        new Object[]{lessons.stream().filter(ActiveLesson::reviewRequired).count()}, null, "secondary",
                        "reviewQuality.kpi.activeLessons.hint", null));

        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("verdictChart", verdictChart());

        Map<String, long[]> byCategory = new HashMap<>();
        for (FindingFact finding : findings) {
            for (FeedbackFact feedback : feedbackByFinding.getOrDefault(finding.id(), List.of())) {
                long[] counts = byCategory.computeIfAbsent(finding.category(), key -> new long[2]);
                if (judged(feedback.verdict())) {
                    counts[0]++;
                    if (feedback.verdict() != FeedbackVerdict.USEFUL) {
                        counts[1]++;
                    }
                }
            }
        }
        Map<String, List<String>> lessonsByCategory = new HashMap<>();
        for (ActiveLesson lesson : lessons) {
            if (lesson.category() != null) {
                lessonsByCategory.computeIfAbsent(lesson.category(), key -> new ArrayList<>()).add(lesson.reference());
            }
        }
        List<WrongRow> wrong = byCategory.entrySet().stream().filter(entry -> entry.getValue()[0] > 0)
                .map(entry -> new WrongRow(entry.getKey(), entry.getValue()[0],
                        rate(entry.getValue()[1], entry.getValue()[0]),
                        lessonsByCategory.getOrDefault(entry.getKey(), List.of()), proposeQuery.apply(entry.getKey())))
                .sorted(Comparator.comparing((WrongRow row) -> row.wrong().sufficient()).reversed()
                        .thenComparing(Comparator.comparingDouble((WrongRow row) ->
                                (double) row.wrong().numerator() / row.wrong().denominator()).reversed())
                        .thenComparing(WrongRow::category))
                .limit(WRONG_ROWS).toList();

        List<LessonRow> rows = page(lessons, pageIndex, pageSize).stream().map(this::lessonRow).toList();
        return new AccuracyView(kpis, charts, wrong, new PageOf<>(rows, pageIndex + 1, pageSize, lessons.size()),
                LESSON_WINDOW_DAYS);
    }

    private Map<String, Object> verdictChart() {
        List<Instant> buckets = buckets(in.window());
        int n = buckets.size();
        FeedbackVerdict[] order = {FeedbackVerdict.USEFUL, FeedbackVerdict.NEEDS_IMPROVEMENT,
                FeedbackVerdict.IRRELEVANT, FeedbackVerdict.INCORRECT};
        String[] colors = {OK_COLOR, MEDIUM_COLOR, LOW_COLOR, BLOCKER_COLOR};
        Map<FeedbackVerdict, double[]> counts = new EnumMap<>(FeedbackVerdict.class);
        for (FeedbackVerdict verdict : order) {
            counts.put(verdict, new double[n]);
        }
        long[] useful = new long[n];
        long[] judged = new long[n];
        for (FeedbackFact feedback : in.data().feedback()) {
            int bucket = in.window().contains(feedback.createdAt()) ? bucketIndex(buckets, feedback.createdAt()) : -1;
            if (bucket < 0 || !counts.containsKey(feedback.verdict())) {
                continue;
            }
            counts.get(feedback.verdict())[bucket]++;
            if (feedback.findingId() != null && judged(feedback.verdict())) {
                judged[bucket]++;
                if (feedback.verdict() == FeedbackVerdict.USEFUL) {
                    useful[bucket]++;
                }
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        for (int v = 0; v < order.length; v++) {
            List<Double> values = new ArrayList<>();
            for (double value : counts.get(order[v])) {
                values.add(value);
            }
            series.add(series(texts.get("reviews.verdict." + order[v].name()), values, colors[v], "bar", "y"));
        }
        List<Double> precision = new ArrayList<>();
        for (int b = 0; b < n; b++) {
            Integer percent = rate(useful[b], judged[b]).percent();
            precision.add(percent == null ? null : percent.doubleValue());
        }
        series.add(series(texts.get("reviewQuality.chart.precision"), precision, INFO_COLOR, "line", "y2"));
        Map<String, Object> chart = chart("stacked", labels(buckets), series);
        chart.put("yTitle", texts.get("reviewQuality.chart.feedbackRecords"));
        chart.put("y2Title", texts.get("reviewQuality.chart.precisionPercent"));
        chart.put("y2Max", 100);
        return chart;
    }

    private LessonRow lessonRow(ActiveLesson lesson) {
        Instant activated = lesson.activatedAt();
        Window before = new Window(activated.minus(LESSON_WINDOW_DAYS, ChronoUnit.DAYS), activated);
        Instant afterEnd = activated.plus(LESSON_WINDOW_DAYS, ChronoUnit.DAYS);
        Window after = new Window(activated, afterEnd.isAfter(in.now()) ? in.now() : afterEnd);
        List<PathMatcher> matchers = matchers(lesson.paths());
        long[] beforeStats = lessonStats(lesson, matchers, before);
        long[] afterStats = lessonStats(lesson, matchers, after);
        return new LessonRow(lesson.reference(), lesson.revision(), lesson.owner() + "/" + lesson.repo(),
                in.data().botNames().get(lesson.botId()), lesson.category(), lesson.paths(), activated, lesson.usage(),
                lesson.reviewRequired(), rate(beforeStats[2], beforeStats[3]), rate(afterStats[2], afterStats[3]),
                fmt.ratio(beforeStats[1], beforeStats[0], 1), fmt.ratio(afterStats[1], afterStats[0], 1));
    }

    /** Reviews, matching findings, incorrect and judged feedback on them, for one lesson and window. */
    private long[] lessonStats(ActiveLesson lesson, List<PathMatcher> matchers, Window window) {
        long[] stats = new long[4];
        for (ReviewFact review : in.data().reviews()) {
            if (!review.finalized() || !window.contains(review.createdAt())
                    || !Objects.equals(review.botId(), lesson.botId()) || !review.owner().equals(lesson.owner())
                    || !review.repo().equals(lesson.repo())) {
                continue;
            }
            stats[0]++;
            for (FindingFact finding : findingsByReview.getOrDefault(review.reviewId(), List.of())) {
                if ((lesson.category() != null && !lesson.category().equals(finding.category()))
                        || !matches(matchers, finding.path())) {
                    continue;
                }
                stats[1]++;
                for (FeedbackFact feedback : feedbackByFinding.getOrDefault(finding.id(), List.of())) {
                    if (judged(feedback.verdict())) {
                        stats[3]++;
                        if (feedback.verdict() == FeedbackVerdict.INCORRECT) {
                            stats[2]++;
                        }
                    }
                }
            }
        }
        return stats;
    }

    private static List<PathMatcher> matchers(List<String> patterns) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            } catch (IllegalArgumentException e) {
                // Validated when the lesson was written; a pattern that no longer compiles matches nothing.
            }
        }
        return matchers;
    }

    private static boolean matches(List<PathMatcher> matchers, String path) {
        if (matchers.isEmpty()) {
            return true;
        }
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            Path candidate = Path.of(path);
            return matchers.stream().anyMatch(matcher -> matcher.matches(candidate));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    // ================================================================== cost and return

    CostView cost() {
        ReviewQualitySettings settings = in.settings();
        List<PrStats> prs = prs(in.window());
        List<RunUsage> runs = runsIn(in.window());
        Set<Long> reviewRuns = in.data().reviews().stream().map(ReviewFact::runId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Cost total = total(runs);
        boolean money = money(total);
        Cost reviewsCost = Cost.NONE;
        Cost followUps = Cost.NONE;
        List<Instant> buckets = buckets(in.window());
        double[] costPerBucket = new double[buckets.size()];
        for (RunUsage run : runs) {
            Cost cost = cost(run);
            if (reviewRuns.contains(run.runId())) {
                reviewsCost = reviewsCost.plus(cost);
            } else {
                followUps = followUps.plus(cost);
            }
            int bucket = bucketIndex(buckets, run.startedAt());
            if (bucket >= 0) {
                costPerBucket[bucket] += money ? cost.money() : cost.tokens();
            }
        }

        long[] accepted = new long[3];
        long incorrect = 0;
        double[] valuePerBucket = new double[buckets.size()];
        for (PrStats stats : prs) {
            int bucket = bucketIndex(buckets, stats.firstReview().createdAt());
            Map<Long, FindingFact> chains = new LinkedHashMap<>();
            Map<Long, List<FeedbackFact>> chainFeedback = new HashMap<>();
            for (ReviewFact review : stats.reviews()) {
                for (FindingFact finding : findingsByReview.getOrDefault(review.reviewId(), List.of())) {
                    chains.putIfAbsent(finding.chain(), finding);
                    chainFeedback.computeIfAbsent(finding.chain(), key -> new ArrayList<>())
                            .addAll(feedbackByFinding.getOrDefault(finding.id(), List.of()));
                }
            }
            Set<Long> lastChains = stats.resolvable()
                    ? chainsOf(stats.lastReview()) : Set.of();
            Set<Long> startedLast = new HashSet<>();
            if (stats.resolvable()) {
                for (FindingFact finding : findingsByReview.getOrDefault(stats.lastReview().reviewId(), List.of())) {
                    if (finding.originId() == null || finding.originId() == finding.id()) {
                        startedLast.add(finding.chain());
                    }
                }
            }
            for (Map.Entry<Long, FindingFact> chain : chains.entrySet()) {
                List<FeedbackFact> feedback = chainFeedback.getOrDefault(chain.getKey(), List.of());
                boolean useful = feedback.stream().anyMatch(f -> f.verdict() == FeedbackVerdict.USEFUL);
                boolean resolvedLater = stats.resolvable() && !lastChains.contains(chain.getKey())
                        && !startedLast.contains(chain.getKey());
                if (useful || resolvedLater) {
                    int severity = chain.getValue().severity().ordinal();
                    accepted[severity]++;
                    if (bucket >= 0) {
                        valuePerBucket[bucket] += minutes(settings, chain.getValue().severity());
                    }
                } else if (feedback.stream().anyMatch(f -> f.verdict() == FeedbackVerdict.INCORRECT)) {
                    incorrect++;
                    if (bucket >= 0) {
                        valuePerBucket[bucket] -= settings.getMinutesIncorrect();
                    }
                }
            }
        }
        double rate = settings.getHourlyRate().doubleValue();
        double hours = (accepted[0] * settings.getMinutesBlocker() + accepted[1] * settings.getMinutesMedium()
                + accepted[2] * settings.getMinutesLow() - incorrect * settings.getMinutesIncorrect()) / 60.0;
        double value = hours * rate;
        long reviews = finalizedIn(in.window()).size();

        List<Kpi> kpis = new ArrayList<>();
        kpis.add(new Kpi("reviewQuality.kpi.aiCost", costText(total, 1), "reviewQuality.kpi.aiCost.detail",
                new Object[]{costText(total, reviews)}, null, "secondary", "reviewQuality.kpi.aiCost.hint", null));
        kpis.add(new Kpi("reviewQuality.kpi.costPerBlocker", accepted[0] == 0 ? "–" : costText(total, accepted[0]),
                "reviewQuality.kpi.costPerBlocker.detail", new Object[]{accepted[0]}, null, "secondary",
                "reviewQuality.kpi.costPerBlocker.hint", null));
        kpis.add(new Kpi("reviewQuality.kpi.hoursSaved", fmt.number(hours, 0), "reviewQuality.kpi.hoursSaved.detail",
                new Object[]{accepted[0] + accepted[1] + accepted[2], incorrect}, null, "secondary",
                "reviewQuality.kpi.hoursSaved.hint", null));
        boolean returnKnown = money && total.money() > 0;
        kpis.add(new Kpi("reviewQuality.kpi.return", returnKnown ? fmt.number(value / total.money(), 1) + "×" : "–",
                "reviewQuality.kpi.return.detail", new Object[]{fmt.money(value, settings.getCurrency())}, null,
                "secondary", "reviewQuality.kpi.return.hint", null));

        List<Double> costs = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int b = 0; b < buckets.size(); b++) {
            costs.add(round(costPerBucket[b], 2));
            values.add(round(valuePerBucket[b] / 60.0 * rate, 2));
        }
        List<Map<String, Object>> series = new ArrayList<>();
        series.add(series(texts.get(money ? "reviewQuality.chart.aiCostMoney" : "reviewQuality.chart.aiCostTokens",
                settings.getCurrency()), costs, LOW_COLOR, "bar", money ? "y" : "y2"));
        series.add(series(texts.get("reviewQuality.chart.estimatedValue", settings.getCurrency()), values, OK_COLOR,
                "line", "y"));
        Map<String, Object> charts = new LinkedHashMap<>();
        Map<String, Object> chart = chart("mixed", labels(buckets), series);
        chart.put("y2Title", money ? null : texts.get("reviewQuality.chart.tokens"));
        charts.put("costChart", chart);
        return new CostView(kpis, charts, settings, money, total.unpricedTokens() > 0 && money
                ? fmt.compact(total.unpricedTokens()) : null, costText(reviewsCost, 1), costText(followUps, 1),
                List.of(new Since("reviewQuality.since.runLinks", in.data().since().runLinks()),
                        new Since("reviewQuality.since.fingerprints", in.data().since().fingerprints())));
    }

    private Set<Long> chainsOf(ReviewFact review) {
        return findingsByReview.getOrDefault(review.reviewId(), List.of()).stream().map(FindingFact::chain)
                .collect(Collectors.toSet());
    }

    private static int minutes(ReviewQualitySettings settings, ReviewSeverity severity) {
        return switch (severity) {
            case BLOCKER -> settings.getMinutesBlocker();
            case MEDIUM -> settings.getMinutesMedium();
            case LOW -> settings.getMinutesLow();
        };
    }

    Cost cost(RunUsage run) {
        Prices prices = in.prices().get(run.integration());
        long tokens = run.inputTokens() + run.outputTokens();
        if (prices == null || !prices.priced()) {
            return new Cost(0, 0, tokens);
        }
        long cacheWrite = run.cacheWriteTokens();
        long cacheRead = run.cacheReadTokens();
        long uncached = Math.max(0, run.inputTokens() - cacheWrite - cacheRead);
        double writePrice = prices.cacheWrite() != null ? prices.cacheWrite() : prices.input();
        double readPrice = prices.cacheRead() != null ? prices.cacheRead() : prices.input();
        double money = (uncached * prices.input() + cacheWrite * writePrice + cacheRead * readPrice
                + run.outputTokens() * prices.output()) / 1_000_000.0;
        return new Cost(money, tokens, 0);
    }

    private Cost total(List<RunUsage> runs) {
        Cost total = Cost.NONE;
        for (RunUsage run : runs) {
            total = total.plus(cost(run));
        }
        return total;
    }

    private static boolean money(Cost cost) {
        return cost.pricedTokens() > 0;
    }

    /** The cost divided by {@code count}: money when anything is priced, tokens otherwise. */
    private String costText(Cost cost, long count) {
        if (count <= 0) {
            return "–";
        }
        if (money(cost)) {
            return fmt.money(cost.money() / count, in.settings().getCurrency());
        }
        return texts.get("reviewQuality.tokens", fmt.compact(Math.round((double) cost.tokens() / count)));
    }

    // ================================================================== shared figures

    /** Pull requests whose first finalized review falls into {@code window}, with all their reviews. */
    List<PrStats> prs(Window window) {
        return prsByWindow.computeIfAbsent(window, w -> {
            List<PrStats> prs = new ArrayList<>();
            finalizedByPr.forEach((key, reviews) -> {
                Instant first = in.data().firstFinalized().getOrDefault(key, reviews.getFirst().createdAt());
                if (w.contains(first) && !reviews.getFirst().createdAt().isAfter(first)) {
                    prs.add(stats(key, reviews));
                }
            });
            prs.sort(Comparator.comparing(PrStats::firstReview, Comparator.comparing(ReviewFact::createdAt)));
            return prs;
        });
    }

    private PrStats stats(PrKey key, List<ReviewFact> reviews) {
        ReviewFact first = reviews.getFirst();
        ReviewFact last = reviews.getLast();
        Counts firstCounts = counts(first);
        Counts lastCounts = counts(last);
        Integer resolved = null;
        Integer persisting = null;
        Integer added = null;
        boolean resolvable = reviews.size() >= 2
                && reviews.subList(1, reviews.size()).stream().allMatch(ReviewFact::fingerprinted);
        if (resolvable) {
            Set<Long> firstChains = chainsOf(first);
            List<FindingFact> lastFindings = findingsByReview.getOrDefault(last.reviewId(), List.of());
            int continued = (int) lastFindings.stream().filter(finding -> firstChains.contains(finding.chain())).count();
            persisting = continued;
            resolved = firstChains.size() - continued;
            added = lastFindings.size() - continued;
        }
        Integer roundsToClean = null;
        if (firstCounts.blocker() > 0) {
            for (int round = 1; round < reviews.size(); round++) {
                if (counts(reviews.get(round)).blocker() == 0) {
                    roundsToClean = round + 1;
                    break;
                }
            }
        }
        OutcomeFact outcome = in.data().outcomes().get(key);
        boolean blockersAtMerge = false;
        if (outcome != null && outcome.state() == PrOutcome.State.MERGED && outcome.closedAt() != null) {
            ReviewFact lastBeforeMerge = null;
            for (ReviewFact review : reviews) {
                if (!review.createdAt().isAfter(outcome.closedAt())) {
                    lastBeforeMerge = review;
                }
            }
            blockersAtMerge = lastBeforeMerge != null && counts(lastBeforeMerge).blocker() > 0;
        }
        return new PrStats(key, reviews, firstCounts, lastCounts, resolved, persisting, added,
                firstCounts.total(), roundsToClean, outcome, blockersAtMerge);
    }

    private Counts counts(ReviewFact review) {
        int[] counts = new int[3];
        for (FindingFact finding : findingsByReview.getOrDefault(review.reviewId(), List.of())) {
            counts[finding.severity().ordinal()]++;
        }
        return new Counts(counts[0], counts[1], counts[2]);
    }

    private List<ReviewFact> finalizedIn(Window window) {
        return in.data().reviews().stream().filter(review -> review.finalized() && window.contains(review.createdAt()))
                .toList();
    }

    private List<FindingFact> findingsOf(Collection<ReviewFact> reviews) {
        List<FindingFact> findings = new ArrayList<>();
        for (ReviewFact review : reviews) {
            findings.addAll(findingsByReview.getOrDefault(review.reviewId(), List.of()));
        }
        return findings;
    }

    private List<RunUsage> runsIn(Window window) {
        return in.data().runs().stream().filter(run -> window.contains(run.startedAt())).toList();
    }

    private Rate resolvedShare(List<PrStats> prs) {
        long resolved = 0;
        long first = 0;
        for (PrStats stats : prs) {
            if (stats.resolvable()) {
                resolved += stats.resolved();
                first += stats.resolved() + stats.persisting();
            }
        }
        return rate(resolved, first);
    }

    /** useful ÷ (useful + incorrect + irrelevant) over the feedback on {@code findings}. */
    Rate precision(Collection<FindingFact> findings) {
        long useful = 0;
        long judged = 0;
        for (FindingFact finding : findings) {
            for (FeedbackFact feedback : feedbackByFinding.getOrDefault(finding.id(), List.of())) {
                if (judged(feedback.verdict())) {
                    judged++;
                    if (feedback.verdict() == FeedbackVerdict.USEFUL) {
                        useful++;
                    }
                }
            }
        }
        return rate(useful, judged);
    }

    /** Findings with any feedback ÷ findings. */
    Rate coverage(Collection<FindingFact> findings) {
        long withFeedback = findings.stream().filter(finding -> feedbackByFinding.containsKey(finding.id())).count();
        return rate(withFeedback, findings.size());
    }

    private static boolean judged(FeedbackVerdict verdict) {
        return verdict == FeedbackVerdict.USEFUL || verdict == FeedbackVerdict.INCORRECT
                || verdict == FeedbackVerdict.IRRELEVANT;
    }

    Rate rate(long numerator, long denominator) {
        return new Rate(numerator, denominator, denominator >= in.minSample());
    }

    private Kpi averageKpi(String label, String hint, List<PrStats> prs, List<PrStats> before,
                           java.util.function.ToIntFunction<PrStats> value, int decimals) {
        double now = prs.isEmpty() ? 0 : prs.stream().mapToInt(value).average().orElse(0);
        String delta = null;
        String tone = "secondary";
        if (before != null && !before.isEmpty() && !prs.isEmpty()) {
            double previous = before.stream().mapToInt(value).average().orElse(0);
            double change = now - previous;
            delta = texts.get(change < 0 ? "reviewQuality.delta.down" : "reviewQuality.delta.up",
                    fmt.number(Math.abs(change), decimals));
            tone = change < 0 ? "success" : change > 0 ? "danger" : "secondary";
        }
        return new Kpi(label, prs.isEmpty() ? "–" : fmt.number(now, decimals), "reviewQuality.kpi.firstReview",
                new Object[0], delta, tone, hint, null);
    }

    private Kpi rateKpi(String label, String hint, Rate now, Rate before, boolean lowerIsBetter, String detailKey,
                        Object[] detailArgs) {
        String delta = null;
        String tone = "secondary";
        if (before != null && now.percent() != null && before.percent() != null) {
            int change = now.percent() - before.percent();
            delta = texts.get(change < 0 ? "reviewQuality.delta.downPoints" : "reviewQuality.delta.upPoints",
                    Math.abs(change));
            boolean better = lowerIsBetter ? change < 0 : change > 0;
            tone = change == 0 ? "secondary" : better ? "success" : "danger";
        }
        return new Kpi(label, now.text(), detailKey, detailArgs, delta, tone, hint, now.counts());
    }

    private String percentDelta(long now, long before) {
        if (before == 0) {
            return null;
        }
        long change = Math.round(100.0 * (now - before) / before);
        return texts.get("reviewQuality.delta.percent", (change > 0 ? "+" : "") + change);
    }

    private static SeverityMix mix(int[] totals) {
        int sum = totals[0] + totals[1] + totals[2];
        if (sum == 0) {
            return new SeverityMix(0, 0, 0);
        }
        int blocker = (int) Math.round(100.0 * totals[0] / sum);
        int medium = (int) Math.round(100.0 * totals[1] / sum);
        return new SeverityMix(blocker, medium, Math.max(0, 100 - blocker - medium));
    }

    /** Findings per pull request (first review) per bucket, as a sparkline. */
    private Sparkline trend(List<PrStats> prs, List<Instant> buckets) {
        double[] sums = new double[buckets.size()];
        long[] counts = new long[buckets.size()];
        for (PrStats stats : prs) {
            int bucket = bucketIndex(buckets, stats.firstReview().createdAt());
            if (bucket >= 0) {
                sums[bucket] += stats.first().total();
                counts[bucket]++;
            }
        }
        List<double[]> points = new ArrayList<>();
        for (int b = 0; b < buckets.size(); b++) {
            if (counts[b] > 0) {
                points.add(new double[]{b, sums[b] / counts[b]});
            }
        }
        if (points.size() < 2) {
            return null;
        }
        double min = points.stream().mapToDouble(p -> p[1]).min().orElse(0);
        double max = points.stream().mapToDouble(p -> p[1]).max().orElse(0);
        double span = max - min == 0 ? 1 : max - min;
        int last = Math.max(1, buckets.size() - 1);
        String svg = points.stream().map(p -> String.format(Locale.ROOT, "%.1f,%.1f",
                        p[0] / last * Sparkline.WIDTH, Sparkline.HEIGHT - 2 - (p[1] - min) / span * (Sparkline.HEIGHT - 4)))
                .collect(Collectors.joining(" "));
        return new Sparkline(svg, points.getLast()[1] < points.getFirst()[1]);
    }

    // ================================================================== buckets and charts

    List<Instant> buckets(Window window) {
        List<Instant> buckets = new ArrayList<>();
        Instant start = floor(window.from());
        while (start.isBefore(window.to())) {
            buckets.add(start);
            LocalDate date = LocalDate.ofInstant(start, ZoneOffset.UTC);
            start = (in.monthly() ? date.plusMonths(1) : date.plusWeeks(1)).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return buckets;
    }

    private Instant floor(Instant instant) {
        LocalDate date = LocalDate.ofInstant(instant, ZoneOffset.UTC);
        LocalDate start = in.monthly() ? date.withDayOfMonth(1)
                : date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return start.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private int bucketIndex(List<Instant> buckets, Instant instant) {
        if (instant == null || buckets.isEmpty()) {
            return -1;
        }
        int index = buckets.indexOf(floor(instant));
        return index;
    }

    private List<String> labels(List<Instant> buckets) {
        return buckets.stream().map(start -> {
            LocalDate date = LocalDate.ofInstant(start, ZoneOffset.UTC);
            return in.monthly() ? date.toString().substring(0, 7) : date.toString();
        }).toList();
    }

    private static Map<String, Object> chart(String kind, List<String> labels, List<Map<String, Object>> series) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("kind", kind);
        chart.put("labels", labels);
        chart.put("series", series);
        return chart;
    }

    private static Map<String, Object> series(String label, List<Double> data, String color, String type, String axis) {
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("label", label);
        series.put("data", data);
        series.put("color", color);
        series.put("type", type);
        series.put("axis", axis);
        return series;
    }

    private static <T> List<T> page(List<T> rows, int pageIndex, int pageSize) {
        int from = Math.min(rows.size(), pageIndex * pageSize);
        return rows.subList(from, Math.min(rows.size(), from + pageSize));
    }

    private static double median(List<Integer> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static Double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
