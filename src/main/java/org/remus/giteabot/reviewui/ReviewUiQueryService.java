package org.remus.giteabot.reviewui;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackState;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.learning.feedback.WorkflowFeedback;
import org.remus.giteabot.learning.knowledge.KnowledgeLesson;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonRepository;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonService;
import org.remus.giteabot.learning.knowledge.LessonState;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettingsResolver;
import org.remus.giteabot.review.structured.FinalizedReview;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewPublicationState;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.remus.giteabot.review.structured.WorkflowOutput;
import org.remus.giteabot.review.structured.WorkflowOutputRepository;
import org.remus.giteabot.reviewui.ReviewUiViews.BotOption;
import org.remus.giteabot.reviewui.ReviewUiViews.CitingLesson;
import org.remus.giteabot.reviewui.ReviewUiViews.FeedbackItem;
import org.remus.giteabot.reviewui.ReviewUiViews.FeedbackRow;
import org.remus.giteabot.reviewui.ReviewUiViews.LessonDetail;
import org.remus.giteabot.reviewui.ReviewUiViews.LessonPage;
import org.remus.giteabot.reviewui.ReviewUiViews.LessonRow;
import org.remus.giteabot.reviewui.ReviewUiViews.LessonUse;
import org.remus.giteabot.reviewui.ReviewUiViews.PageOf;
import org.remus.giteabot.reviewui.ReviewUiViews.ReviewDetail;
import org.remus.giteabot.reviewui.ReviewUiViews.ReviewRow;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The read side of the Reviews pages. Listings span every bot and repository, so each page is read
 * with a fixed number of grouped queries — rows first, then their counts, feedback, lessons and bot
 * names for exactly those rows — never with one lookup per row.
 */
@Service
@RequiredArgsConstructor
public class ReviewUiQueryService {

    /** Most lessons one listing reads; the "needs attention" filter is applied to these. */
    public static final int LESSON_SCAN_LIMIT = 500;
    /** Most reviews of one pull request the review page lists. */
    static final int PULL_REQUEST_REVIEWS = 20;
    /** Most recent usages the lesson page lists. */
    static final int LESSON_USAGE_ROWS = 20;

    private static final String WORKFLOW_KEY = StructuredReviewService.WORKFLOW_KEY;

    private final EntityManager entityManager;
    private final BotRepository botRepository;
    private final StructuredReviewService reviewService;
    private final WorkflowOutputRepository outputRepository;
    private final KnowledgeLessonService lessonService;
    private final KnowledgeLessonRepository lessonRepository;
    private final AgentReviewLearningSettingsResolver learningSettingsResolver;

    /** Bots that have agentic-review records, for the filter. */
    @Transactional(readOnly = true)
    public List<BotOption> botOptions() {
        List<Long> ids = entityManager.createQuery(
                "SELECT DISTINCT o.botId FROM WorkflowOutput o WHERE o.workflowKey = :key", Long.class)
                .setParameter("key", WORKFLOW_KEY).getResultList();
        return botNames(ids).entrySet().stream()
                .map(entry -> new BotOption(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
    }

    /** {@code owner/repo} of every repository with agentic-review records, for the filter. */
    @Transactional(readOnly = true)
    public List<String> projectOptions() {
        return entityManager.createQuery("SELECT DISTINCT o.repoOwner, o.repoName FROM WorkflowOutput o "
                        + "WHERE o.workflowKey = :key", Object[].class)
                .setParameter("key", WORKFLOW_KEY).getResultList().stream()
                .map(row -> row[0] + "/" + row[1]).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    // ------------------------------------------------------------------ reviews

    @Transactional(readOnly = true)
    public PageOf<ReviewRow> reviews(ReviewUiFilterForm filter) {
        Where where = new Where("o.workflowKey = :key").param("key", WORKFLOW_KEY);
        scopeConditions(where, filter, "o.botId", "o.repoOwner", "o.repoName");
        if (filter.getNumber() != null) {
            where.and("o.prNumber = :number").param("number", filter.getNumber());
        }
        ReviewState state = enumOrNull(ReviewState.class, filter.getState());
        if (state != null) {
            where.and("r.state = :state").param("state", state);
        }
        if (filter.isBlockers()) {
            where.and("EXISTS (SELECT f.id FROM ReviewFinding f WHERE f.review = r "
                    + "AND f.severity = :blocker AND f.withdrawn = false)").param("blocker", ReviewSeverity.BLOCKER);
        }
        String from = " FROM ReviewRecord r JOIN r.output o WHERE " + where;
        long total = where.bind(entityManager.createQuery("SELECT COUNT(r)" + from, Long.class))
                .getSingleResult();
        List<Long> outputIds = where.bind(entityManager.createQuery("SELECT o.id" + from
                        + " ORDER BY o.id DESC", Long.class))
                .setFirstResult(filter.pageIndex() * filter.effectiveSize()).setMaxResults(filter.effectiveSize())
                .getResultList();
        return new PageOf<>(reviewRows(outputIds), filter.pageIndex() + 1, filter.effectiveSize(), total);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewDetail> review(String reference) {
        Optional<WorkflowOutput> found = outputRepository.findByReference(reference)
                .filter(output -> WORKFLOW_KEY.equals(output.getWorkflowKey()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkflowOutput output = found.get();
        List<ReviewRow> rows = reviewRows(List.of(output.getId()));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        FinalizedReview finalized = reviewService.findFinalizedByReference(reference).orElse(null);
        Map<String, List<FeedbackItem>> byTarget = new LinkedHashMap<>();
        for (WorkflowFeedback record : entityManager.createQuery("SELECT f FROM WorkflowFeedback f "
                        + "WHERE f.outputId = :id ORDER BY f.id", WorkflowFeedback.class)
                .setParameter("id", output.getId()).getResultList()) {
            byTarget.computeIfAbsent(record.getTargetReference(), key -> new ArrayList<>())
                    .add(item(record));
        }
        List<Long> siblings = outputRepository.findByScope(output.getGitIntegrationId(),
                        output.getBotId(), output.getRepoOwner(), output.getRepoName(),
                        output.getPrNumber(), WORKFLOW_KEY, PageRequest.of(0, PULL_REQUEST_REVIEWS))
                .stream().map(WorkflowOutput::getId).toList();
        String runsQuery = "project=" + java.net.URLEncoder.encode(output.getRepoOwner() + "/"
                + output.getRepoName(), java.nio.charset.StandardCharsets.UTF_8)
                + (output.getPrNumber() == null ? "" : "&number=" + output.getPrNumber());
        return Optional.of(new ReviewDetail(rows.getFirst(), finalized, byTarget,
                reviewRows(siblings), runsQuery));
    }

    /** Rows of the given outputs, in the given order; outputs without a review record are left out. */
    private List<ReviewRow> reviewRows(List<Long> outputIds) {
        if (outputIds.isEmpty()) {
            return List.of();
        }
        List<Object[]> base = entityManager.createQuery("SELECT r.id, o.id, o.reference, o.botId, "
                        + "o.repoOwner, o.repoName, o.prNumber, r.state, r.headSha, o.createdAt, "
                        + "r.finalizedAt, a.conclusion FROM ReviewRecord r JOIN r.output o "
                        + "LEFT JOIN r.assessment a WHERE o.id IN :ids", Object[].class)
                .setParameter("ids", outputIds).getResultList();
        List<Long> reviewIds = base.stream().map(row -> (Long) row[0]).toList();

        Map<Long, int[]> severities = new HashMap<>();
        for (Object[] row : entityManager.createQuery("SELECT f.review.id, f.severity, COUNT(f) "
                        + "FROM ReviewFinding f WHERE f.review.id IN :ids AND f.withdrawn = false "
                        + "GROUP BY f.review.id, f.severity", Object[].class)
                .setParameter("ids", reviewIds).getResultList()) {
            severities.computeIfAbsent((Long) row[0], id -> new int[3])
                    [((ReviewSeverity) row[1]).ordinal()] = ((Number) row[2]).intValue();
        }
        Map<Long, ReviewPublicationState> publications = new HashMap<>();
        Map<Long, Integer> renderRevisions = new HashMap<>();
        for (Object[] row : entityManager.createQuery("SELECT p.reviewId, p.state, p.renderRevision "
                        + "FROM ReviewPublication p WHERE p.reviewId IN :ids", Object[].class)
                .setParameter("ids", reviewIds).getResultList()) {
            Long reviewId = (Long) row[0];
            int revision = ((Number) row[2]).intValue();
            if (revision >= renderRevisions.getOrDefault(reviewId, Integer.MIN_VALUE)) {
                renderRevisions.put(reviewId, revision);
                publications.put(reviewId, (ReviewPublicationState) row[1]);
            }
        }
        Map<Long, Long> feedback = new HashMap<>();
        for (Object[] row : entityManager.createQuery("SELECT f.outputId, COUNT(f) FROM WorkflowFeedback f "
                        + "WHERE f.outputId IN :ids AND f.state = :effective GROUP BY f.outputId", Object[].class)
                .setParameter("ids", outputIds).setParameter("effective", FeedbackState.EFFECTIVE)
                .getResultList()) {
            feedback.put((Long) row[0], ((Number) row[1]).longValue());
        }
        Map<Long, List<String>> lessons = new HashMap<>();
        for (Object[] row : entityManager.createQuery("SELECT u.outputId, l.reference, v.revisionNumber "
                        + "FROM LessonUsage u, KnowledgeLesson l, KnowledgeLessonRevision v "
                        + "WHERE l.id = u.lessonId AND v.id = u.revisionId AND u.outputId IN :ids "
                        + "ORDER BY u.id", Object[].class)
                .setParameter("ids", outputIds).getResultList()) {
            lessons.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add(row[1] + "@" + row[2]);
        }
        Map<Long, String> bots = botNames(base.stream().map(row -> (Long) row[3]).toList());

        Map<Long, ReviewRow> byOutput = new HashMap<>();
        for (Object[] row : base) {
            Long reviewId = (Long) row[0];
            Long outputId = (Long) row[1];
            int[] counts = severities.getOrDefault(reviewId, new int[3]);
            byOutput.put(outputId, new ReviewRow(reviewId, outputId, (String) row[2], (Long) row[3],
                    bots.get((Long) row[3]), (String) row[4], (String) row[5], (Long) row[6],
                    (ReviewState) row[7], (String) row[8], (Instant) row[9], (Instant) row[10],
                    (ReviewConclusion) row[11], counts[ReviewSeverity.BLOCKER.ordinal()],
                    counts[ReviewSeverity.MEDIUM.ordinal()], counts[ReviewSeverity.LOW.ordinal()],
                    feedback.getOrDefault(outputId, 0L), lessons.getOrDefault(outputId, List.of()),
                    publications.get(reviewId)));
        }
        return outputIds.stream().map(byOutput::get).filter(Objects::nonNull).toList();
    }

    // ------------------------------------------------------------------ feedback

    @Transactional(readOnly = true)
    public PageOf<FeedbackRow> feedback(ReviewUiFilterForm filter) {
        Where where = new Where("1 = 1");
        scopeConditions(where, filter, "f.botId", "f.repoOwner", "f.repoName");
        if (filter.getNumber() != null) {
            where.and("f.prNumber = :number").param("number", filter.getNumber());
        }
        if (!filter.isAll()) {
            where.and("f.state = :effective").param("effective", FeedbackState.EFFECTIVE);
        }
        FeedbackVerdict verdict = enumOrNull(FeedbackVerdict.class, filter.getVerdict());
        if (verdict != null) {
            where.and("f.verdict = :verdict").param("verdict", verdict);
        }
        if (filter.isUncited()) {
            where.and("NOT EXISTS (SELECT e.id FROM KnowledgeLessonEvidence e WHERE e.feedbackId = f.id)");
        }
        String from = " FROM WorkflowFeedback f WHERE " + where;
        long total = where.bind(entityManager.createQuery("SELECT COUNT(f)" + from, Long.class))
                .getSingleResult();
        List<WorkflowFeedback> records = where.bind(entityManager.createQuery("SELECT f" + from
                        + " ORDER BY f.id DESC", WorkflowFeedback.class))
                .setFirstResult(filter.pageIndex() * filter.effectiveSize()).setMaxResults(filter.effectiveSize())
                .getResultList();

        Map<Long, List<CitingLesson>> citing = new HashMap<>();
        if (!records.isEmpty()) {
            for (Object[] row : entityManager.createQuery("SELECT e.feedbackId, l.reference, "
                            + "v.revisionNumber, v.state FROM KnowledgeLessonEvidence e, KnowledgeLesson l, "
                            + "KnowledgeLessonRevision v WHERE l.id = e.lessonId AND v.id = e.revisionId "
                            + "AND e.feedbackId IN :ids ORDER BY e.id", Object[].class)
                    .setParameter("ids", records.stream().map(WorkflowFeedback::getId).toList())
                    .getResultList()) {
                citing.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add(new CitingLesson(
                        (String) row[1], ((Number) row[2]).intValue(), (LessonState) row[3]));
            }
        }
        List<Long> botIds = records.stream().map(WorkflowFeedback::getBotId).toList();
        Map<Long, String> bots = botNames(botIds);
        Map<Long, Boolean> curated = curatedByBot(botIds);
        List<FeedbackRow> rows = records.stream().map(record -> new FeedbackRow(item(record),
                record.getBotId(), bots.get(record.getBotId()), record.getRepoOwner(),
                record.getRepoName(), record.getPrNumber(),
                citing.getOrDefault(record.getId(), List.of()),
                curated.getOrDefault(record.getBotId(), false))).toList();
        return new PageOf<>(rows, filter.pageIndex() + 1, filter.effectiveSize(), total);
    }

    // ------------------------------------------------------------------ lessons

    /**
     * Lessons newest first. The state filter is applied in the query; "needs attention" depends on
     * validation, so it is applied to the summaries of at most {@link #LESSON_SCAN_LIMIT} lessons.
     */
    @Transactional(readOnly = true)
    public LessonPage lessons(ReviewUiFilterForm filter) {
        Where where = new Where("l.workflowKey = :key").param("key", WORKFLOW_KEY);
        scopeConditions(where, filter, "l.botId", "l.repoOwner", "l.repoName");
        String selected = filter.effectiveLessonState();
        boolean attention = ReviewUiFilterForm.ATTENTION.equalsIgnoreCase(selected);
        LessonState state = enumOrNull(LessonState.class, selected);
        if (attention) {
            where.and("l.state IN :states").param("states",
                    EnumSet.of(LessonState.ACTIVE, LessonState.CANDIDATE));
        } else if (state != null) {
            where.and("l.state = :state").param("state", state);
        }
        List<Object[]> ids = where.bind(entityManager.createQuery("SELECT l.id, l.botId FROM "
                        + "KnowledgeLesson l WHERE " + where + " ORDER BY l.id DESC", Object[].class))
                .setMaxResults(LESSON_SCAN_LIMIT + 1).getResultList();
        boolean truncated = ids.size() > LESSON_SCAN_LIMIT;
        List<Object[]> scanned = ids.stream().limit(LESSON_SCAN_LIMIT).toList();
        Map<Long, Long> botOf = scanned.stream().collect(Collectors.toMap(row -> (Long) row[0],
                row -> (Long) row[1], (a, b) -> a, LinkedHashMap::new));
        List<KnowledgeLessonService.Summary> summaries = lessonService.summaries(List.copyOf(botOf.keySet()));
        if (attention) {
            summaries = summaries.stream().filter(ReviewUiQueryService::needsAttention).toList();
        }
        int from = Math.min(summaries.size(), filter.pageIndex() * filter.effectiveSize());
        List<KnowledgeLessonService.Summary> page = summaries.subList(from,
                Math.min(summaries.size(), from + filter.effectiveSize()));
        List<Long> botIds = page.stream().map(summary -> botOf.get(summary.id())).toList();
        Map<Long, String> bots = botNames(botIds);
        Map<Long, Boolean> curated = curatedByBot(botIds);
        List<LessonRow> rows = page.stream().map(summary -> {
            Long botId = botOf.get(summary.id());
            return new LessonRow(summary, botId, bots.get(botId), curated.getOrDefault(botId, false));
        }).toList();
        return new LessonPage(new PageOf<>(rows, filter.pageIndex() + 1, filter.effectiveSize(), summaries.size()),
                truncated, LESSON_SCAN_LIMIT);
    }

    /**
     * Whether a maintainer has something to decide: a candidate waiting, or a lesson that is kept out
     * of reviews by its validation (review required, recheck due, withdrawn evidence, conflicts).
     */
    static boolean needsAttention(KnowledgeLessonService.Summary summary) {
        return summary.state() == LessonState.CANDIDATE
                || summary.pendingRevision() != null
                || summary.reviewRequired()
                || (summary.state() == LessonState.ACTIVE && !summary.validation().valid());
    }

    @Transactional(readOnly = true)
    public Optional<LessonDetail> lesson(String reference) {
        Optional<KnowledgeLesson> found = lessonRepository.findByReference(reference)
                .filter(lesson -> WORKFLOW_KEY.equals(lesson.getWorkflowKey()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        KnowledgeLesson lesson = found.get();
        FeedbackScope scope = new FeedbackScope(lesson.getGitIntegrationId(), lesson.getBotId(),
                lesson.getRepoOwner(), lesson.getRepoName(), null);
        return lessonService.show(scope, lesson.getReference()).map(detail -> {
            List<LessonUse> usage = entityManager.createQuery("SELECT o.reference, o.repoOwner, "
                            + "o.repoName, o.prNumber, v.revisionNumber, u.createdAt FROM LessonUsage u, "
                            + "WorkflowOutput o, KnowledgeLessonRevision v WHERE u.lessonId = :id "
                            + "AND o.id = u.outputId AND v.id = u.revisionId ORDER BY u.id DESC", Object[].class)
                    .setParameter("id", lesson.getId()).setMaxResults(LESSON_USAGE_ROWS)
                    .getResultList().stream()
                    .map(row -> new LessonUse((String) row[0], (String) row[1], (String) row[2],
                            (Long) row[3], ((Number) row[4]).intValue(), (Instant) row[5]))
                    .toList();
            AgentReviewLearningSettings settings = settingsOf(lesson.getBotId());
            KnowledgeLessonService.RevisionView latest = detail.revisions().stream()
                    .filter(revision -> revision.number() == detail.summary().latestRevision())
                    .findFirst().orElse(detail.summary().current());
            String paths = latest == null || latest.paths().isEmpty() ? "**" : String.join(" ", latest.paths());
            return new LessonDetail(detail, lesson.getBotId(), botNames(List.of(lesson.getBotId()))
                    .get(lesson.getBotId()), settings.curatedLearning(), settings.learningMode().name(),
                    usage, latest, paths);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static FeedbackItem item(WorkflowFeedback record) {
        return new FeedbackItem(record.getReference(), record.getTargetReference(),
                record.getTargetType(), record.getVerdict(), record.getState(), record.getReason(),
                record.getActor(), record.getSupersededBy(), record.getCreatedAt());
    }

    private static void scopeConditions(Where where, ReviewUiFilterForm filter, String bot,
                                        String owner, String repo) {
        if (filter.getBot() != null) {
            where.and(bot + " = :bot").param("bot", filter.getBot());
        }
        if (filter.owner() != null && filter.repo() != null) {
            where.and("LOWER(" + owner + ") = :owner AND LOWER(" + repo + ") = :repo")
                    .param("owner", filter.owner()).param("repo", filter.repo());
        }
    }

    private Map<Long, String> botNames(Collection<Long> botIds) {
        Set<Long> ids = botIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return botRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Bot::getId, Bot::getName, (a, b) -> a));
    }

    /** Whether each bot's agentic review runs curated learning; one resolution per distinct bot. */
    private Map<Long, Boolean> curatedByBot(Collection<Long> botIds) {
        Set<Long> ids = botIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return botRepository.findAllById(ids).stream().collect(Collectors.toMap(Bot::getId,
                bot -> learningSettingsResolver.resolve(bot).curatedLearning(), (a, b) -> a));
    }

    private AgentReviewLearningSettings settingsOf(Long botId) {
        return botId == null ? AgentReviewLearningSettings.DISABLED
                : botRepository.findById(botId).map(learningSettingsResolver::resolve)
                        .orElse(AgentReviewLearningSettings.DISABLED);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A JPQL condition with its parameters, so the count and the page query share one filter. */
    private static final class Where {

        private final StringBuilder condition;
        private final Map<String, Object> params = new LinkedHashMap<>();

        Where(String first) {
            this.condition = new StringBuilder(first);
        }

        Where and(String next) {
            condition.append(" AND ").append(next);
            return this;
        }

        Where param(String name, Object value) {
            params.put(name, value);
            return this;
        }

        <T> TypedQuery<T> bind(TypedQuery<T> query) {
            params.forEach(query::setParameter);
            return query;
        }

        @Override
        public String toString() {
            return condition.toString();
        }
    }
}
