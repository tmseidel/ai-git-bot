package org.remus.giteabot.reviewui;

import lombok.Data;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The query string of the Reviews pages, bound by Spring ({@code @ModelAttribute}) and handed to the
 * templates as {@code filter}. Bot and repository are shared by the three tabs; the other fields
 * belong to one tab each and are ignored by the others.
 */
@Data
public class ReviewUiFilterForm {

    /** Lesson filter value for "candidates, review required, recheck due or conflicts". */
    public static final String ATTENTION = "attention";
    /** Lesson filter value for every state. */
    public static final String ALL = "all";
    /** Rows per page unless chosen otherwise. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** Most rows one page shows, whatever the request asks for. */
    public static final int MAX_PAGE_SIZE = 100;

    private Long bot;
    /** {@code owner/repo}. */
    private String project;
    private Long number;
    /** Review state ({@code FINALIZED}, {@code FAILED}, …); blank for all. */
    private String state;
    private boolean blockers;
    /** Feedback verdict; blank for all. */
    private String verdict;
    /** Feedback: include superseded and retracted records. */
    private boolean all;
    /** Feedback: only records no lesson cites. */
    private boolean uncited;
    /** Lesson state, {@link #ALL} (default) or {@link #ATTENTION}. */
    private String lessonState;
    /** 1-based page number. */
    private int page = 1;
    /** Rows per page, as on the Workflow runs page. */
    private int size = DEFAULT_PAGE_SIZE;

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

    public String effectiveLessonState() {
        return lessonState == null || lessonState.isBlank() ? ALL : lessonState;
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

    /**
     * The current parameters as hidden-field values, without {@code excluded} (for small forms that
     * change those, such as rows per page).
     */
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
        params.put("number", number);
        params.put("state", state);
        params.put("blockers", blockers);
        params.put("verdict", verdict);
        params.put("all", all);
        params.put("uncited", uncited);
        params.put("lessonState", lessonState);
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
