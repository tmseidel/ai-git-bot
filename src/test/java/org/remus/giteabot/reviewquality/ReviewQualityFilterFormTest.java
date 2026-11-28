package org.remus.giteabot.reviewquality;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.reviewquality.ReviewQualityFilterForm.Window;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Periods, comparison, severities and links of the Review quality filter. */
class ReviewQualityFilterFormTest {

    private static final Instant NOW = Instant.parse("2026-11-26T15:30:00Z");

    @Test
    void theDefaultPeriodIsTheLastTwelveWeeksUpToTheEndOfToday() {
        Window window = new ReviewQualityFilterForm().window(NOW);
        assertThat(window.to()).isEqualTo(Instant.parse("2026-11-27T00:00:00Z"));
        assertThat(window.length()).isEqualTo(Duration.ofDays(84));
    }

    @Test
    void periodsAreThirtyDaysTheQuarterOrACustomRange() {
        ReviewQualityFilterForm filter = new ReviewQualityFilterForm();
        filter.setPeriod("30d");
        assertThat(filter.window(NOW).length()).isEqualTo(Duration.ofDays(30));
        filter.setPeriod("quarter");
        assertThat(filter.window(NOW).from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        filter.setPeriod("custom");
        filter.setFrom("2026-01-01");
        filter.setTo("2026-01-31");
        assertThat(filter.window(NOW)).isEqualTo(new Window(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z")));
    }

    @Test
    void anInvalidOrHugeCustomRangeIsBounded() {
        ReviewQualityFilterForm filter = new ReviewQualityFilterForm();
        filter.setPeriod("custom");
        filter.setFrom("yesterday");
        filter.setTo("2026-01-31");
        assertThat(filter.window(NOW).length()).isEqualTo(Duration.ofDays(84));
        filter.setFrom("1990-01-01");
        assertThat(filter.window(NOW).length()).isEqualTo(Duration.ofDays(ReviewQualityFilterForm.MAX_DAYS));
        filter.setPeriod("forever");
        assertThat(filter.effectivePeriod()).isEqualTo(ReviewQualityFilterForm.PERIOD_12_WEEKS);
    }

    @Test
    void theComparisonIsTheSameLengthRightBeforeOrNothing() {
        ReviewQualityFilterForm filter = new ReviewQualityFilterForm();
        Window window = filter.window(NOW);
        Window before = filter.comparison(window);
        assertThat(before.to()).isEqualTo(window.from());
        assertThat(before.length()).isEqualTo(window.length());
        filter.setCompare("none");
        assertThat(filter.comparison(window)).isNull();
    }

    @Test
    void theSeverityFilterNarrowsTheCountedFindings() {
        ReviewQualityFilterForm filter = new ReviewQualityFilterForm();
        assertThat(filter.severities()).containsExactlyInAnyOrder(ReviewSeverity.values());
        filter.setSeverity("bm");
        assertThat(filter.severities()).containsExactlyInAnyOrder(ReviewSeverity.BLOCKER, ReviewSeverity.MEDIUM);
        filter.setSeverity("b");
        assertThat(filter.severities()).containsExactly(ReviewSeverity.BLOCKER);
    }

    @Test
    void linksKeepTheFilterAndStartOverOnPageOne() {
        ReviewQualityFilterForm filter = new ReviewQualityFilterForm();
        filter.setProject("acme/web shop");
        filter.setBucket("month");
        filter.setPage(3);
        filter.setSize(50);
        assertThat(filter.owner()).isEqualTo("acme");
        assertThat(filter.repo()).isEqualTo("web shop");
        assertThat(filter.with("page", 4)).isEqualTo("project=acme%2Fweb+shop&period=12w&bucket=month&page=4&size=50");
        assertThat(filter.with("severity", "b")).isEqualTo("project=acme%2Fweb+shop&period=12w&severity=b&bucket=month&size=50");
        assertThat(filter.scope()).isEqualTo("project=acme%2Fweb+shop&period=12w&bucket=month");
        assertThat(filter.paramsWithout("size", "page")).containsOnlyKeys("project", "period", "bucket");
        filter.setSize(100_000);
        assertThat(filter.effectiveSize()).isEqualTo(ReviewQualityFilterForm.MAX_PAGE_SIZE);
        filter.setDepth(99);
        assertThat(filter.effectiveDepth()).isEqualTo(ReviewQualityFilterForm.MAX_DEPTH);
    }

    @Test
    void theAssumptionsFormReturnsOnlyToAPlainQueryOfThisPage() {
        assertThat(ReviewQualityController.safeQuery("period=30d&project=acme%2Fweb")).isEqualTo("?period=30d&project=acme%2Fweb");
        assertThat(ReviewQualityController.safeQuery("x=1\r\nLocation: //evil")).isEmpty();
        assertThat(ReviewQualityController.safeQuery("a=<script>")).isEmpty();
        assertThat(ReviewQualityController.safeQuery(null)).isEmpty();
    }

    @Test
    void chartDataCannotCloseTheScriptElement() {
        assertThat(ReviewQualityController.scriptSafe("{\"label\":\"</script><b>&\"}"))
                .doesNotContain("<").doesNotContain(">").doesNotContain("&")
                .contains("\\u003c/script\\u003e");
    }
}
