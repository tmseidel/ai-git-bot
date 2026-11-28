package org.remus.giteabot.reviewquality;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.config.ReviewQualityProperties;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.PrKey;
import org.remus.giteabot.reviewquality.ReviewQualityFilterForm.Window;
import org.remus.giteabot.reviewquality.ReviewQualityLoader.ActiveLesson;
import org.remus.giteabot.reviewquality.ReviewQualityLoader.Scope;
import org.remus.giteabot.reviewquality.ReviewQualityViews.AccuracyView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.AuthorsView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.CostView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.OverviewView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.PrsView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.RepositoriesView;
import org.remus.giteabot.reviewquality.ReviewQualityViews.TopicsView;
import org.remus.giteabot.reviewui.ReviewUiViews.BotOption;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * The tabs of the Review quality page. Each tab reads the facts of its period (and the comparison
 * period) once through {@link ReviewQualityLoader} and aggregates them with
 * {@link ReviewQualityCalculator}; only the selected tab is computed.
 */
@Service
@RequiredArgsConstructor
public class ReviewQualityService {

    /** Oldest lesson activation the before/after comparison reads back to. */
    static final int MAX_LESSON_LOOKBACK_DAYS = 730;

    private final ReviewQualityLoader loader;
    private final ReviewQualitySettingsService settingsService;
    private final ReviewQualityProperties properties;
    private final MessageSource messageSource;
    private final Clock clock = Clock.systemUTC();

    @Transactional(readOnly = true)
    public List<BotOption> botOptions() {
        return loader.botOptions().entrySet().stream().map(entry -> new BotOption(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
    }

    @Transactional(readOnly = true)
    public List<String> projectOptions() {
        return loader.projectOptions();
    }

    @Transactional(readOnly = true)
    public OverviewView overview(ReviewQualityFilterForm filter, Locale locale) {
        return calculator(filter, locale, null).overview();
    }

    @Transactional(readOnly = true)
    public PrsView prs(ReviewQualityFilterForm filter, Locale locale) {
        return calculator(filter, locale, null).prs(filter.pageIndex(), filter.effectiveSize(),
                ReviewQualityService::reviewsQuery);
    }

    @Transactional(readOnly = true)
    public RepositoriesView repositories(ReviewQualityFilterForm filter, Locale locale) {
        return calculator(filter, locale, null).repositories(filter.pageIndex(), filter.effectiveSize(),
                project -> "project=" + ReviewQualityCalculator.encode(project));
    }

    @Transactional(readOnly = true)
    public AuthorsView authors(ReviewQualityFilterForm filter, Locale locale) {
        int minPrs = properties.effectiveAuthorMinPrs();
        if (!properties.isAuthorMetrics()) {
            return new ReviewQualityCalculator(emptyInputs(filter), texts(locale), locale)
                    .authors(false, minPrs, filter.pageIndex(), filter.effectiveSize());
        }
        return calculator(filter, locale, null).authors(true, minPrs, filter.pageIndex(), filter.effectiveSize());
    }

    @Transactional(readOnly = true)
    public TopicsView topics(ReviewQualityFilterForm filter, Locale locale) {
        return calculator(filter, locale, null).topics(filter.effectiveDepth(), filter.pageIndex(),
                filter.effectiveSize());
    }

    @Transactional(readOnly = true)
    public AccuracyView accuracy(ReviewQualityFilterForm filter, Locale locale) {
        List<ActiveLesson> lessons = loader.activeLessons(Scope.of(filter));
        Instant floor = clock.instant().minus(MAX_LESSON_LOOKBACK_DAYS, ChronoUnit.DAYS);
        Instant earliest = lessons.stream().map(ActiveLesson::activatedAt)
                .map(at -> at.minus(ReviewQualityCalculator.LESSON_WINDOW_DAYS, ChronoUnit.DAYS))
                .filter(at -> at.isAfter(floor)).min(Instant::compareTo).orElse(null);
        return calculator(filter, locale, earliest).accuracy(lessons, filter.pageIndex(), filter.effectiveSize(),
                category -> "category=" + ReviewQualityCalculator.encode(category) + "&uncited=true"
                        + scopeQuery(filter));
    }

    @Transactional(readOnly = true)
    public CostView cost(ReviewQualityFilterForm filter, Locale locale) {
        return calculator(filter, locale, null).cost();
    }

    public int minSample() {
        return properties.effectiveMinSample();
    }

    @Transactional(readOnly = true)
    public ReviewQualitySettings assumptions() {
        return settingsService.current();
    }

    // ------------------------------------------------------------------ helpers

    private ReviewQualityCalculator calculator(ReviewQualityFilterForm filter, Locale locale, Instant alsoSince) {
        Instant now = clock.instant();
        Window window = filter.window(now);
        Window comparison = filter.comparison(window);
        Instant since = comparison != null ? comparison.from() : window.from();
        if (alsoSince != null && alsoSince.isBefore(since)) {
            since = alsoSince;
        }
        ReviewQualityCalculator.Inputs inputs = new ReviewQualityCalculator.Inputs(
                loader.load(Scope.of(filter), since), window, comparison, filter.severities(), filter.monthly(),
                properties.effectiveMinSample(), settingsService.current(), now);
        return new ReviewQualityCalculator(inputs, texts(locale), locale);
    }

    private ReviewQualityCalculator.Inputs emptyInputs(ReviewQualityFilterForm filter) {
        Instant now = clock.instant();
        Window window = filter.window(now);
        return new ReviewQualityCalculator.Inputs(new ReviewQualityFacts.Dataset(List.of(), java.util.Map.of(),
                List.of(), List.of(), java.util.Map.of(), List.of(), java.util.Map.of(),
                loaderSince()), window, null, filter.severities(), filter.monthly(), properties.effectiveMinSample(),
                settingsService.current(), now);
    }

    private ReviewQualityFacts.DataSince loaderSince() {
        return new ReviewQualityFacts.DataSince(null, null, null, null, null);
    }

    private ReviewQualityCalculator.Texts texts(Locale locale) {
        return (key, args) -> messageSource.getMessage(key, args, key, locale);
    }

    /** The Reviews page filtered to one pull request. */
    static String reviewsQuery(PrKey key) {
        return "project=" + ReviewQualityCalculator.encode(key.project()) + "&number=" + key.number()
                + "&bot=" + key.botId();
    }

    private static String scopeQuery(ReviewQualityFilterForm filter) {
        StringBuilder query = new StringBuilder();
        if (filter.getBot() != null) {
            query.append("&bot=").append(filter.getBot());
        }
        if (filter.getProject() != null && !filter.getProject().isBlank()) {
            query.append("&project=").append(ReviewQualityCalculator.encode(filter.getProject().trim()));
        }
        return query.toString();
    }
}
