package org.remus.giteabot.reviewquality;

import lombok.Data;
import org.remus.giteabot.review.structured.ReviewSeverity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The query string of the Review quality page, bound by Spring ({@code @ModelAttribute}) and handed
 * to the templates as {@code filter} — the same mechanism as the Reviews and Workflow runs pages.
 * Periods, buckets and dates are in UTC.
 */
@Data
public class ReviewQualityFilterForm {

    public static final String PERIOD_30_DAYS = "30d";
    public static final String PERIOD_12_WEEKS = "12w";
    public static final String PERIOD_QUARTER = "quarter";
    public static final String PERIOD_CUSTOM = "custom";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_DEPTH = 4;
    public static final int MAX_DEPTH = 10;
    /** Longest custom period, so one page never reads an unbounded history. */
    public static final int MAX_DAYS = 731;

    private Long bot;
    /** {@code owner/repo}. */
    private String project;
    private String period = PERIOD_12_WEEKS;
    /** Custom period: first and last day, {@code yyyy-MM-dd}. */
    private String from;
    private String to;
    /** {@code previous} (default) or {@code none}. */
    private String compare = "previous";
    /** {@code all} (default), {@code bm} (blocker + medium) or {@code b} (blocker). */
    private String severity = "all";
    /** {@code week} (default) or {@code month}. */
    private String bucket = "week";
    /** Directory depth of the hot spots. */
    private int depth = DEFAULT_DEPTH;
    private int page = 1;
    private int size = DEFAULT_PAGE_SIZE;

    /** A period: {@code from} inclusive, {@code to} exclusive. */
    public record Window(Instant from, Instant to) {

        public boolean contains(Instant instant) {
            return instant != null && !instant.isBefore(from) && instant.isBefore(to);
        }

        public Duration length() {
            return Duration.between(from, to);
        }
    }

    /** The selected period, ending now unless a custom period says otherwise. */
    public Window window(Instant now) {
        ZonedDateTime today = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        Instant end = today.plusDays(1).toInstant();
        return switch (effectivePeriod()) {
            case PERIOD_30_DAYS -> new Window(today.minusDays(29).toInstant(), end);
            case PERIOD_QUARTER -> new Window(today.withDayOfMonth(1)
                    .withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).toInstant(), end);
            case PERIOD_CUSTOM -> customWindow(end);
            default -> new Window(today.minusWeeks(12).plusDays(1).toInstant(), end);
        };
    }

    /** The period of the same length right before {@code window}, or {@code null} for no comparison. */
    public Window comparison(Window window) {
        if ("none".equalsIgnoreCase(compare)) {
            return null;
        }
        return new Window(window.from().minus(window.length()), window.from());
    }

    private Window customWindow(Instant fallbackEnd) {
        LocalDate first = date(from);
        LocalDate last = date(to);
        if (first == null || last == null || last.isBefore(first)) {
            return new Window(fallbackEnd.minus(84, ChronoUnit.DAYS), fallbackEnd);
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            first = last.minusDays(MAX_DAYS - 1);
        }
        return new Window(first.atStartOfDay(ZoneOffset.UTC).toInstant(),
                last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public String effectivePeriod() {
        String value = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case PERIOD_30_DAYS, PERIOD_QUARTER, PERIOD_CUSTOM -> value;
            default -> PERIOD_12_WEEKS;
        };
    }

    public boolean monthly() {
        return "month".equalsIgnoreCase(bucket);
    }

    /** The severities the finding figures count. */
    public Set<ReviewSeverity> severities() {
        return switch (severity == null ? "" : severity.trim().toLowerCase(Locale.ROOT)) {
            case "b" -> EnumSet.of(ReviewSeverity.BLOCKER);
            case "bm" -> EnumSet.of(ReviewSeverity.BLOCKER, ReviewSeverity.MEDIUM);
            default -> EnumSet.allOf(ReviewSeverity.class);
        };
    }

    public int effectiveDepth() {
        return depth < 1 ? DEFAULT_DEPTH : Math.min(depth, MAX_DEPTH);
    }

    /** Lower-cased repository owner of {@link #project}, or {@code null}. */
    public String owner() {
        int slash = slash();
        return slash > 0 ? project.substring(0, slash).trim().toLowerCase(Locale.ROOT) : null;
    }

    /** Lower-cased repository name of {@link #project}, or {@code null}. */
    public String repo() {
        int slash = slash();
        return slash > 0 && slash < project.length() - 1
                ? project.substring(slash + 1).trim().toLowerCase(Locale.ROOT) : null;
    }

    public int pageIndex() {
        return Math.max(0, page - 1);
    }

    /** Rows per page: the requested number, at most {@link #MAX_PAGE_SIZE}. */
    public int effectiveSize() {
        return size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    }

    /** The current query string with {@code name} set to {@code value} (and back on page 1). */
    public String with(String name, Object value) {
        Map<String, Object> params = params();
        if (!"page".equals(name)) {
            params.remove("page");
        }
        params.put(name, value);
        StringJoiner query = new StringJoiner("&");
        params.forEach((key, v) -> {
            if (present(v)) {
                query.add(key + "=" + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
            }
        });
        return query.toString();
    }

    /** The current query string without paging, for links between the tabs. */
    public String scope() {
        Map<String, Object> params = paramsWithout("page", "size");
        StringJoiner query = new StringJoiner("&");
        params.forEach((key, v) -> query.add(key + "=" + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)));
        return query.toString();
    }

    /** The current parameters as hidden-field values, without {@code excluded}. */
    public Map<String, Object> paramsWithout(String... excluded) {
        Map<String, Object> params = params();
        for (String name : excluded) {
            params.remove(name);
        }
        params.values().removeIf(value -> !present(value));
        return params;
    }

    private Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bot", bot);
        params.put("project", project);
        params.put("period", effectivePeriod());
        boolean custom = PERIOD_CUSTOM.equals(effectivePeriod());
        params.put("from", custom ? from : null);
        params.put("to", custom ? to : null);
        params.put("compare", "none".equalsIgnoreCase(compare) ? "none" : null);
        params.put("severity", "b".equalsIgnoreCase(severity) || "bm".equalsIgnoreCase(severity)
                ? severity.toLowerCase(Locale.ROOT) : null);
        params.put("bucket", monthly() ? "month" : null);
        params.put("depth", effectiveDepth() != DEFAULT_DEPTH ? effectiveDepth() : null);
        params.put("page", page > 1 ? page : null);
        params.put("size", effectiveSize());
        return params;
    }

    private static boolean present(Object value) {
        return value != null && !(value instanceof Boolean b && !b) && !(value instanceof String s && s.isBlank());
    }

    private int slash() {
        return project == null ? -1 : project.indexOf('/');
    }
}
