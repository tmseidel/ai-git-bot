package org.remus.giteabot.workflowrun;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The Workflow runs page's query string, bound by Spring ({@code @ModelAttribute}) for the list
 * and the export, and handed to the template as {@code filter}: the form fields read their values
 * from it, and its links change one parameter and keep the rest.
 *
 * <p>The dates come from {@code datetime-local} inputs and are read in the browser's time zone
 * ({@link #tz}), falling back to the server's.</p>
 */
@Slf4j
@Data
public class WorkflowRunFilterForm {

    private Long runId;
    private String project;
    private Long number;
    private String type;
    private Long bot;
    private String state;
    private boolean warnings;
    private boolean failedCalls;
    private String from;
    private String to;
    private String tz;
    /** 1-based page number. */
    private int page = 1;
    private int size = 20;
    private String dir = "desc";
    /** Id of a run to show expanded (links from the details modal and the dashboard). */
    private Long expand;

    /** The query filters this form describes. */
    public WorkflowRunFilter toFilter() {
        ZoneId zone = zone();
        return new WorkflowRunFilter(runId, blankToNull(project), number, blankToNull(type), bot,
                selectedState(), warnings, failedCalls, instantOf(from, zone), instantOf(to, zone));
    }

    /** The selected state, or {@code null} for all states (also for an unknown value). */
    public RunStatus selectedState() {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return RunStatus.valueOf(state.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isAscending() {
        return "asc".equalsIgnoreCase(dir);
    }

    /**
     * The query string of this page with {@code name} set to {@code value} ({@code null} removes
     * it). Any change but the page itself starts again at the first page.
     */
    public String with(String name, Object value) {
        Map<String, Object> params = params();
        if (!"page".equals(name)) {
            params.remove("page");
        }
        params.put(name, value);
        return queryOf(params);
    }

    /**
     * The current parameters as hidden-field values, without {@code excluded} (for small forms that
     * change those, such as rows per page).
     */
    public Map<String, Object> paramsWithout(String... excluded) {
        Map<String, Object> params = params();
        for (String name : excluded) {
            params.remove(name);
        }
        params.values().removeIf(value -> value == null);
        return params;
    }

    private Map<String, Object> params() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", runId);
        params.put("project", blankToNull(project));
        params.put("number", number);
        params.put("type", blankToNull(type));
        params.put("bot", bot);
        RunStatus selected = selectedState();
        params.put("state", selected != null ? selected.name() : null);
        params.put("warnings", warnings ? "true" : null);
        params.put("failedCalls", failedCalls ? "true" : null);
        params.put("from", blankToNull(from));
        params.put("to", blankToNull(to));
        params.put("tz", blankToNull(tz));
        params.put("page", page > 1 ? page : null);
        params.put("size", size);
        params.put("dir", isAscending() ? "asc" : "desc");
        return params;
    }

    /**
     * Form-encoded, like the browser submits the filter form: a {@code +} in a value (a time zone
     * such as {@code Etc/GMT+1}) must arrive as {@code %2B}, since the server reads a plain
     * {@code +} in the query as a space.
     */
    private static String queryOf(Map<String, Object> params) {
        return params.entrySet().stream()
                .filter(param -> param.getValue() != null)
                .map(param -> param.getKey() + "="
                        + URLEncoder.encode(String.valueOf(param.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private ZoneId zone() {
        if (tz == null || tz.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz.strip());
        } catch (DateTimeException e) {
            log.debug("Ignoring unknown time zone '{}'", tz);
            return ZoneId.systemDefault();
        }
    }

    private static Instant instantOf(String value, ZoneId zone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip()).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            log.debug("Ignoring invalid date filter '{}'", value);
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
