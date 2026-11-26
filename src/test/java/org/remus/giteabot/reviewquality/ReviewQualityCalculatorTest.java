package org.remus.giteabot.reviewquality;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.DataSince;
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
import org.remus.giteabot.reviewquality.ReviewQualityViews.Kpi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counting rules of the Review quality page over plain facts: first review per pull request,
 * resolved / persisting / new from finding chains, the minimum sample, author grouping, cost and the
 * estimated return.
 */
class ReviewQualityCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Window WINDOW = new Window(Instant.parse("2026-09-04T00:00:00Z"), Instant.parse("2026-11-27T00:00:00Z"));
    private static final Window BEFORE = new Window(Instant.parse("2026-06-12T00:00:00Z"), WINDOW.from());

    private final List<ReviewFact> reviews = new ArrayList<>();
    private final List<FindingFact> findings = new ArrayList<>();
    private final List<FeedbackFact> feedback = new ArrayList<>();
    private final Map<PrKey, OutcomeFact> outcomes = new HashMap<>();
    private final List<RunUsage> runs = new ArrayList<>();
    private final Map<String, Prices> prices = new HashMap<>();
    private long nextId = 1;
    private int minSample = 1;
    private Set<ReviewSeverity> severities = EnumSet.allOf(ReviewSeverity.class);

    @Test
    void aPullRequestCountsOnceWithItsFirstReviewInThePeriodOfThatReview() {
        ReviewFact first = review("web", 1, "2026-11-02T10:00:00Z", true, "alex");
        finding(first, ReviewSeverity.BLOCKER, "security", null);
        finding(first, ReviewSeverity.MEDIUM, "correctness", null);
        ReviewFact second = review("web", 1, "2026-11-03T10:00:00Z", true, "alex");
        finding(second, ReviewSeverity.LOW, "style", null);
        ReviewFact other = review("web", 2, "2026-11-04T10:00:00Z", true, "kim");
        finding(other, ReviewSeverity.LOW, "testing", null);
        review("web", 3, "2026-11-05T10:00:00Z", false, "kim");
        ReviewFact earlier = review("api", 9, "2026-08-01T10:00:00Z", true, "alex");
        finding(earlier, ReviewSeverity.BLOCKER, "security", null);
        review("api", 9, "2026-11-10T10:00:00Z", true, "alex");

        List<Kpi> kpis = calculator().overview().kpis();
        assertThat(value(kpis, "reviewQuality.kpi.prs")).as("PR 9 started before the period").isEqualTo("2");
        assertThat(value(kpis, "reviewQuality.kpi.findings")).as("(2 + 1) / 2 on first reviews").isEqualTo("1.5");
        assertThat(value(kpis, "reviewQuality.kpi.blockers")).isEqualTo("0.50");
        assertThat(kpi(kpis, "reviewQuality.kpi.findings").delta()).as("previous period: 1 finding on 1 PR")
                .isEqualTo("reviewQuality.delta.up[0.5]");
    }

    @Test
    void findingChainsTellResolvedPersistingAndNewApart() {
        ReviewFact first = review("web", 1, "2026-11-02T10:00:00Z", true, "alex");
        FindingFact kept = finding(first, ReviewSeverity.MEDIUM, "correctness", null);
        finding(first, ReviewSeverity.BLOCKER, "security", null);
        ReviewFact second = review("web", 1, "2026-11-03T10:00:00Z", true, "alex");
        finding(second, ReviewSeverity.MEDIUM, "correctness", kept.id());
        finding(second, ReviewSeverity.LOW, "style", null);

        ReviewQualityCalculator.PrStats stats = calculator().prs(WINDOW).getFirst();
        assertThat(stats.resolved()).isEqualTo(1);
        assertThat(stats.persisting()).isEqualTo(1);
        assertThat(stats.added()).isEqualTo(1);
        assertThat(stats.roundsToClean()).isEqualTo(2);
    }

    @Test
    void reviewsFinalizedBeforeMatchingExistedAreNotJudged() {
        ReviewFact first = review("web", 1, "2026-11-02T10:00:00Z", true, "alex");
        finding(first, ReviewSeverity.MEDIUM, "correctness", null);
        reviews.add(copy(review("web", 1, "2026-11-03T10:00:00Z", true, "alex"), false));
        reviews.remove(reviews.size() - 2);

        ReviewQualityCalculator.PrStats stats = calculator().prs(WINDOW).getFirst();
        assertThat(stats.rounds()).isEqualTo(2);
        assertThat(stats.resolved()).isNull();
    }

    @Test
    void aRateBelowTheMinimumSampleIsADash() {
        minSample = 3;
        ReviewFact first = review("web", 1, "2026-11-02T10:00:00Z", true, "alex");
        FindingFact a = finding(first, ReviewSeverity.MEDIUM, "correctness", null);
        FindingFact b = finding(first, ReviewSeverity.MEDIUM, "correctness", null);
        verdict(first, a, FeedbackVerdict.USEFUL);
        verdict(first, b, FeedbackVerdict.INCORRECT);
        ReviewQualityCalculator calculator = calculator();
        assertThat(calculator.precision(findings).text()).isEqualTo("–");
        assertThat(calculator.precision(findings).counts()).isEqualTo("1 / 2");
        minSample = 2;
        assertThat(calculator().precision(findings).text()).isEqualTo("50%");
    }

    @Test
    void needsImprovementIsFeedbackButNotPartOfPrecision() {
        ReviewFact first = review("web", 1, "2026-11-02T10:00:00Z", true, "alex");
        FindingFact a = finding(first, ReviewSeverity.MEDIUM, "correctness", null);
        FindingFact b = finding(first, ReviewSeverity.LOW, "style", null);
        verdict(first, a, FeedbackVerdict.USEFUL);
        verdict(first, b, FeedbackVerdict.NEEDS_IMPROVEMENT);
        ReviewQualityCalculator calculator = calculator();
        assertThat(calculator.precision(findings).counts()).isEqualTo("1 / 1");
        assertThat(calculator.coverage(findings).counts()).isEqualTo("2 / 2");
    }

    @Test
    void authorsAreSortedByNameAndSmallSamplesGroupedAsOthers() {
        for (int pr = 1; pr <= 2; pr++) {
            review("web", pr, "2026-11-0" + pr + "T10:00:00Z", true, "zoe");
            review("web", 10 + pr, "2026-11-0" + pr + "T11:00:00Z", true, "Alex");
        }
        review("web", 30, "2026-11-05T10:00:00Z", true, "kim");
        review("web", 31, "2026-11-05T11:00:00Z", true, null);

        ReviewQualityViews.AuthorsView view = calculator().authors(true, 2, 0, 20);
        assertThat(view.rows().rows()).extracting(ReviewQualityViews.AuthorRow::author)
                .containsExactly("Alex", "zoe", null);
        assertThat(view.rows().rows().getLast().others()).isTrue();
        assertThat(view.rows().rows().getLast().othersCount()).isEqualTo(1);
        assertThat(calculator().authors(false, 2, 0, 20).rows().rows()).isEmpty();
    }

    @Test
    void costUsesTheIntegrationsPricesAndFallsBackToTokens() {
        prices.put("claude", new Prices(3.0, 15.0, 3.75, null));
        RunUsage priced = new RunUsage(1, 1L, "acme", "web", 1L, Instant.parse("2026-11-02T10:00:00Z"), "claude",
                1_000_000, 100_000, 200_000, 500_000);
        ReviewQualityCalculator.Cost cost = calculator().cost(priced);
        // uncached 300k x 3 + cache write 200k x 3.75 + cache read 500k x 3 (no read price: input) + 100k x 15
        assertThat(cost.money()).isEqualTo((300_000 * 3.0 + 200_000 * 3.75 + 500_000 * 3.0 + 100_000 * 15.0) / 1e6);
        RunUsage unpriced = new RunUsage(2, 1L, "acme", "web", 1L, Instant.parse("2026-11-02T10:00:00Z"), "local",
                10_000, 2_000, 0, 0);
        assertThat(calculator().cost(unpriced).unpricedTokens()).isEqualTo(12_000);
    }

    @Test
    void theReturnCountsAcceptedFindingsAndChargesIncorrectOnes() {
        prices.put("claude", new Prices(1.0, 1.0, null, null));
        ReviewFact first = review("web", 1, "2026-11-02T10:00:00Z", true, "alex");
        finding(first, ReviewSeverity.BLOCKER, "security", null);
        FindingFact useful = finding(first, ReviewSeverity.LOW, "style", null);
        FindingFact wrong = finding(first, ReviewSeverity.MEDIUM, "correctness", null);
        verdict(first, useful, FeedbackVerdict.USEFUL);
        verdict(first, wrong, FeedbackVerdict.INCORRECT);
        ReviewFact second = review("web", 1, "2026-11-03T10:00:00Z", true, "alex");
        finding(second, ReviewSeverity.MEDIUM, "correctness", wrong.id());
        runs.add(new RunUsage(1, 1L, "acme", "web", 1L, Instant.parse("2026-11-02T10:00:00Z"), "claude",
                1_000_000, 0, 0, 0));

        ReviewQualitySettings settings = new ReviewQualitySettings();
        List<Kpi> kpis = calculator(settings).cost().kpis();
        // blocker resolved (90) + useful low (5) - incorrect medium (10) = 85 minutes
        assertThat(value(kpis, "reviewQuality.kpi.hoursSaved")).isEqualTo("1");
        assertThat(kpi(kpis, "reviewQuality.kpi.hoursSaved").detailArgs()).containsExactly(2L, 1L);
        assertThat(value(kpis, "reviewQuality.kpi.return")).isEqualTo("120.4×");
    }

    @Test
    void lessonsCompareIncorrectFeedbackBeforeAndAfterActivationInTheirScope() {
        Instant activated = Instant.parse("2026-11-01T00:00:00Z");
        ReviewFact before = review("web", 1, "2026-10-20T10:00:00Z", true, "alex");
        FindingFact inScope = finding(before, ReviewSeverity.MEDIUM, "security", null, "src/main/A.java");
        FindingFact outOfScope = finding(before, ReviewSeverity.MEDIUM, "security", null, "docs/A.md");
        verdict(before, inScope, FeedbackVerdict.INCORRECT);
        verdict(before, outOfScope, FeedbackVerdict.INCORRECT);
        ReviewFact after = review("web", 2, "2026-11-10T10:00:00Z", true, "alex");
        FindingFact later = finding(after, ReviewSeverity.MEDIUM, "security", null, "src/main/B.java");
        verdict(after, later, FeedbackVerdict.USEFUL);
        ActiveLesson lesson = new ActiveLesson(5, "L5", 1L, "acme", "web", 50, 1, "security",
                List.of("src/**"), activated, false, 3);

        ReviewQualityViews.LessonRow row = calculator().accuracy(List.of(lesson), 0, 20, category -> "")
                .lessons().rows().getFirst();
        assertThat(row.incorrectBefore().counts()).isEqualTo("1 / 1");
        assertThat(row.incorrectAfter().counts()).isEqualTo("0 / 1");
        assertThat(row.findingsBefore()).isEqualTo("1.0");
        assertThat(row.usage()).isEqualTo(3);
    }

    @Test
    void hotSpotsGroupByTheFirstDirectories() {
        assertThat(ReviewQualityCalculator.directory("src/main/java/shop/order/Order.java", 4)).isEqualTo("src/main/java/shop/");
        assertThat(ReviewQualityCalculator.directory("./src/Order.java", 4)).isEqualTo("src/");
        assertThat(ReviewQualityCalculator.directory("Order.java", 4)).isEqualTo("/");
        assertThat(ReviewQualityCalculator.directory(null, 4)).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private ReviewQualityCalculator calculator() {
        return calculator(new ReviewQualitySettings());
    }

    private ReviewQualityCalculator calculator(ReviewQualitySettings settings) {
        Map<PrKey, Instant> firsts = new HashMap<>();
        for (ReviewFact review : reviews) {
            if (review.finalized()) {
                firsts.merge(review.pr(), review.createdAt(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        Dataset data = new Dataset(reviews, firsts, findings, feedback, outcomes, runs, Map.of(1L, "bot"),
                new DataSince(null, null, null, null, null));
        return new ReviewQualityCalculator(new ReviewQualityCalculator.Inputs(data, WINDOW, BEFORE, severities,
                false, minSample, prices, settings, NOW), (key, args) -> key + java.util.Arrays.toString(args)
                .replace(", ", ","), Locale.ROOT);
    }

    private ReviewFact review(String repo, long pr, String at, boolean finalized, String author) {
        long id = nextId++;
        ReviewFact review = new ReviewFact(id, 1000 + id, "O" + id, 1L, 7L, "acme", repo, pr, author, "title", null,
                finalized ? ReviewState.FINALIZED : ReviewState.FAILED, Instant.parse(at),
                finalized ? ReviewConclusion.FINDINGS_PRESENT : null, null, null, null, true);
        reviews.add(review);
        return review;
    }

    private static ReviewFact copy(ReviewFact r, boolean fingerprinted) {
        return new ReviewFact(r.reviewId(), r.outputId(), r.reference(), r.botId(), r.integrationId(), r.owner(),
                r.repo(), r.prNumber(), r.author(), r.title(), r.runId(), r.state(), r.createdAt(), r.conclusion(),
                r.diffFiles(), r.diffAdditions(), r.diffDeletions(), fingerprinted);
    }

    private FindingFact finding(ReviewFact review, ReviewSeverity severity, String category, Long origin) {
        return finding(review, severity, category, origin, "src/A.java");
    }

    private FindingFact finding(ReviewFact review, ReviewSeverity severity, String category, Long origin, String path) {
        long id = nextId++;
        FindingFact finding = new FindingFact(id, review.reviewId(), severity, category, origin, path);
        findings.add(finding);
        return finding;
    }

    private void verdict(ReviewFact review, FindingFact finding, FeedbackVerdict verdict) {
        feedback.add(new FeedbackFact(review.outputId(), finding.id(), verdict,
                review.createdAt().plus(1, ChronoUnit.HOURS)));
    }

    private static Kpi kpi(List<Kpi> kpis, String label) {
        return kpis.stream().filter(k -> k.labelKey().equals(label)).findFirst().orElseThrow();
    }

    private static String value(List<Kpi> kpis, String label) {
        return kpi(kpis, label).value();
    }
}
