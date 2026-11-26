package org.remus.giteabot.reviewquality;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.learning.feedback.FeedbackState;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.learning.knowledge.LessonPathScope;
import org.remus.giteabot.review.structured.FindingCategories;
import org.remus.giteabot.review.structured.ReviewConclusion;
import org.remus.giteabot.review.structured.ReviewSeverity;
import org.remus.giteabot.review.structured.ReviewState;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.DataSince;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.Dataset;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.FeedbackFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.FindingFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.OutcomeFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.PrKey;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.ReviewFact;
import org.remus.giteabot.reviewquality.ReviewQualityFacts.RunUsage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the facts of the Review quality page. Every read is one query over the selected scope and
 * period — rows first, then grouped counts — so a page takes a fixed number of queries however many
 * reviews, findings and feedback records there are.
 */
@Service
@RequiredArgsConstructor
public class ReviewQualityLoader {

    static final String WORKFLOW_KEY = StructuredReviewService.WORKFLOW_KEY;

    private final EntityManager entityManager;
    private final BotRepository botRepository;

    /** The scope of the page: bot and repository filters. */
    public record Scope(Long bot, String owner, String repo) {

        static Scope of(ReviewQualityFilterForm filter) {
            return new Scope(filter.getBot(), filter.owner(), filter.repo());
        }
    }

    /** An active lesson revision, for the Accuracy tab. */
    public record ActiveLesson(long lessonId, String reference, Long botId, String owner, String repo,
                               long revisionId, int revision, String category, List<String> paths,
                               Instant activatedAt, boolean reviewRequired, long usage) {
    }

    /** Reviews created since {@code since} with their findings, feedback, outcomes and AI usage. */
    @Transactional(readOnly = true)
    public Dataset load(Scope scope, Instant since) {
        return new Dataset(reviews(scope, since), firstFinalized(scope, since), findings(scope, since),
                feedback(scope, since), outcomes(scope, since), runs(scope, since), botNames(), dataSince());
    }

    /** Lesson revisions active now, with the number of reviews each was supplied to. */
    @Transactional(readOnly = true)
    public List<ActiveLesson> activeLessons(Scope scope) {
        Where where = new Where("v.lessonId = l.id AND l.activeRevision = v.revisionNumber "
                + "AND v.activatedAt IS NOT NULL AND l.workflowKey = :key").param("key", WORKFLOW_KEY);
        scope(where, scope, "l.botId", "l.repoOwner", "l.repoName");
        List<Object[]> rows = where.bind(entityManager.createQuery("SELECT l.id, l.reference, l.botId, "
                + "l.repoOwner, l.repoName, v.id, v.revisionNumber, v.category, v.pathScopes, v.activatedAt, "
                + "l.reviewRequired FROM KnowledgeLesson l, KnowledgeLessonRevision v WHERE " + where
                + " ORDER BY v.activatedAt DESC, l.id DESC", Object[].class)).getResultList();
        Map<Long, Long> usage = new HashMap<>();
        Where usageWhere = new Where("l.id = u.lessonId AND l.workflowKey = :key").param("key", WORKFLOW_KEY);
        scope(usageWhere, scope, "l.botId", "l.repoOwner", "l.repoName");
        for (Object[] row : usageWhere.bind(entityManager.createQuery("SELECT u.revisionId, COUNT(u) "
                + "FROM LessonUsage u, KnowledgeLesson l WHERE " + usageWhere + " GROUP BY u.revisionId",
                Object[].class)).getResultList()) {
            usage.put((Long) row[0], ((Number) row[1]).longValue());
        }
        List<ActiveLesson> lessons = new ArrayList<>();
        for (Object[] row : rows) {
            long revisionId = (Long) row[5];
            lessons.add(new ActiveLesson((Long) row[0], (String) row[1], (Long) row[2], lower(row[3]),
                    lower(row[4]), revisionId, ((Number) row[6]).intValue(),
                    row[7] == null ? null : FindingCategories.normalize((String) row[7]),
                    LessonPathScope.deserialize((String) row[8]), (Instant) row[9], (Boolean) row[10],
                    usage.getOrDefault(revisionId, 0L)));
        }
        return lessons;
    }

    // ------------------------------------------------------------------ reads

    private List<ReviewFact> reviews(Scope scope, Instant since) {
        Where where = outputs(scope, since);
        List<ReviewFact> reviews = new ArrayList<>();
        for (Object[] row : where.bind(entityManager.createQuery("SELECT r.id, o.id, o.reference, o.botId, "
                + "o.gitIntegrationId, o.repoOwner, o.repoName, o.prNumber, o.prAuthor, o.prTitle, "
                + "o.workflowRunId, r.state, o.createdAt, a.conclusion, r.diffFiles, r.diffAdditions, "
                + "r.diffDeletions, r.fingerprinted FROM ReviewRecord r JOIN r.output o LEFT JOIN r.assessment a "
                + "WHERE " + where + " ORDER BY o.createdAt, o.id", Object[].class)).getResultList()) {
            if (row[3] == null || row[7] == null) {
                continue;
            }
            reviews.add(new ReviewFact((Long) row[0], (Long) row[1], (String) row[2], (Long) row[3], (Long) row[4],
                    lower(row[5]), lower(row[6]), (Long) row[7], (String) row[8], (String) row[9], (Long) row[10],
                    (ReviewState) row[11], (Instant) row[12], (ReviewConclusion) row[13], (Integer) row[14],
                    (Integer) row[15], (Integer) row[16], Boolean.TRUE.equals(row[17])));
        }
        return reviews;
    }

    /** First finalized review of every pull request with a finalized review since {@code since}. */
    private Map<PrKey, Instant> firstFinalized(Scope scope, Instant since) {
        Where where = new Where("o.workflowKey = :key AND r.state = :finalized")
                .param("key", WORKFLOW_KEY).param("finalized", ReviewState.FINALIZED);
        scope(where, scope, "o.botId", "o.repoOwner", "o.repoName");
        Map<PrKey, Instant> firsts = new HashMap<>();
        for (Object[] row : where.bind(entityManager.createQuery("SELECT o.gitIntegrationId, o.botId, "
                        + "LOWER(o.repoOwner), LOWER(o.repoName), o.prNumber, MIN(o.createdAt) "
                        + "FROM ReviewRecord r JOIN r.output o WHERE " + where + " GROUP BY o.gitIntegrationId, "
                        + "o.botId, LOWER(o.repoOwner), LOWER(o.repoName), o.prNumber "
                        + "HAVING MAX(o.createdAt) >= :since", Object[].class))
                .setParameter("since", since).getResultList()) {
            if (row[1] == null || row[4] == null) {
                continue;
            }
            firsts.put(new PrKey((Long) row[0], (Long) row[1], (String) row[2], (String) row[3], (Long) row[4]),
                    (Instant) row[5]);
        }
        return firsts;
    }

    private List<FindingFact> findings(Scope scope, Instant since) {
        Where where = outputs(scope, since).and("f.withdrawn = false AND r.state = :finalized")
                .param("finalized", ReviewState.FINALIZED);
        List<FindingFact> findings = new ArrayList<>();
        for (Object[] row : where.bind(entityManager.createQuery("SELECT f.id, r.id, f.severity, f.category, "
                + "f.originFindingId, l.path FROM ReviewFinding f JOIN f.review r JOIN r.output o "
                + "LEFT JOIN f.locations l ON l.position = 0 WHERE " + where, Object[].class)).getResultList()) {
            findings.add(new FindingFact((Long) row[0], (Long) row[1], (ReviewSeverity) row[2],
                    FindingCategories.normalize((String) row[3]), (Long) row[4], (String) row[5]));
        }
        return findings;
    }

    private List<FeedbackFact> feedback(Scope scope, Instant since) {
        Where where = outputs(scope, since).and("o.id = fb.outputId AND fb.state = :effective")
                .param("effective", FeedbackState.EFFECTIVE);
        List<FeedbackFact> feedback = new ArrayList<>();
        for (Object[] row : where.bind(entityManager.createQuery("SELECT fb.outputId, fb.findingId, fb.verdict, "
                + "fb.createdAt FROM WorkflowFeedback fb, WorkflowOutput o WHERE " + where, Object[].class))
                .getResultList()) {
            feedback.add(new FeedbackFact((Long) row[0], (Long) row[1], (FeedbackVerdict) row[2], (Instant) row[3]));
        }
        return feedback;
    }

    private Map<PrKey, OutcomeFact> outcomes(Scope scope, Instant since) {
        Where where = new Where("p.closedAt >= :since").param("since", since);
        if (scope.bot() != null) {
            where.and("p.botId = :bot").param("bot", scope.bot());
        }
        if (scope.owner() != null && scope.repo() != null) {
            where.and("p.repoOwner = :owner AND p.repoName = :repo")
                    .param("owner", scope.owner()).param("repo", scope.repo());
        }
        Map<PrKey, OutcomeFact> outcomes = new HashMap<>();
        for (PrOutcome outcome : where.bind(entityManager.createQuery("SELECT p FROM PrOutcome p WHERE " + where,
                PrOutcome.class)).getResultList()) {
            outcomes.put(new PrKey(outcome.getGitIntegrationId(), outcome.getBotId(), outcome.getRepoOwner(),
                    outcome.getRepoName(), outcome.getPrNumber()), new OutcomeFact(outcome.getState(),
                    outcome.getClosedAt()));
        }
        return outcomes;
    }

    private List<RunUsage> runs(Scope scope, Instant since) {
        Where where = new Where("w.workflowType = :key AND w.startedAt >= :since")
                .param("key", WORKFLOW_KEY).param("since", since);
        scope(where, scope, "w.botId", "w.repoOwner", "w.repoName");
        List<RunUsage> runs = new ArrayList<>();
        // The cost is the one stored when each interaction ended; today's prices play no part.
        for (Object[] row : where.bind(entityManager.createQuery("SELECT w.id, w.botId, w.repoOwner, w.repoName, "
                + "w.triggerNumber, w.startedAt, SUM(i.cost), "
                + "SUM(CASE WHEN i.cost IS NOT NULL THEN i.inputTokens + i.outputTokens ELSE 0 END), "
                + "SUM(CASE WHEN i.cost IS NULL THEN i.inputTokens + i.outputTokens ELSE 0 END) "
                + "FROM AiInteraction i JOIN i.workflowRun w WHERE " + where
                + " GROUP BY w.id, w.botId, w.repoOwner, w.repoName, w.triggerNumber, w.startedAt",
                Object[].class)).getResultList()) {
            runs.add(new RunUsage((Long) row[0], (Long) row[1], lower(row[2]), lower(row[3]), (Long) row[4],
                    (Instant) row[5], row[6] == null ? 0 : ((Number) row[6]).doubleValue(), number(row[7]),
                    number(row[8])));
        }
        return runs;
    }

    private DataSince dataSince() {
        Object[] row = entityManager.createQuery("SELECT "
                        + "MIN(CASE WHEN r.fingerprinted = true THEN o.createdAt ELSE NULL END), "
                        + "MIN(CASE WHEN o.prAuthor IS NOT NULL THEN o.createdAt ELSE NULL END), "
                        + "MIN(CASE WHEN r.diffFiles IS NOT NULL THEN o.createdAt ELSE NULL END), "
                        + "MIN(CASE WHEN o.workflowRunId IS NOT NULL THEN o.createdAt ELSE NULL END) "
                        + "FROM ReviewRecord r JOIN r.output o WHERE o.workflowKey = :key", Object[].class)
                .setParameter("key", WORKFLOW_KEY).getSingleResult();
        Instant outcomes = entityManager.createQuery("SELECT MIN(p.updatedAt) FROM PrOutcome p", Instant.class)
                .getSingleResult();
        return new DataSince((Instant) row[0], (Instant) row[1], (Instant) row[2], (Instant) row[3], outcomes);
    }

    private Map<Long, String> botNames() {
        List<Long> ids = entityManager.createQuery("SELECT DISTINCT o.botId FROM WorkflowOutput o "
                + "WHERE o.workflowKey = :key", Long.class).setParameter("key", WORKFLOW_KEY).getResultList();
        return names(ids);
    }

    /** Bots that have agentic-review records, for the filter. */
    @Transactional(readOnly = true)
    public Map<Long, String> botOptions() {
        return botNames();
    }

    /** {@code owner/repo} of every repository with agentic-review records, for the filter. */
    @Transactional(readOnly = true)
    public List<String> projectOptions() {
        return entityManager.createQuery("SELECT DISTINCT o.repoOwner, o.repoName FROM WorkflowOutput o "
                        + "WHERE o.workflowKey = :key", Object[].class)
                .setParameter("key", WORKFLOW_KEY).getResultList().stream()
                .map(row -> row[0] + "/" + row[1]).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private Map<Long, String> names(Collection<Long> botIds) {
        Set<Long> ids = botIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Bot bot : botRepository.findAllById(ids)) {
            names.put(bot.getId(), bot.getName());
        }
        return names;
    }

    private static Where outputs(Scope scope, Instant since) {
        Where where = new Where("o.workflowKey = :key AND o.createdAt >= :since")
                .param("key", WORKFLOW_KEY).param("since", since);
        scope(where, scope, "o.botId", "o.repoOwner", "o.repoName");
        return where;
    }

    private static void scope(Where where, Scope scope, String bot, String owner, String repo) {
        if (scope.bot() != null) {
            where.and(bot + " = :bot").param("bot", scope.bot());
        }
        if (scope.owner() != null && scope.repo() != null) {
            where.and("LOWER(" + owner + ") = :owner AND LOWER(" + repo + ") = :repo")
                    .param("owner", scope.owner()).param("repo", scope.repo());
        }
    }

    private static String lower(Object value) {
        return value == null ? null : ((String) value).toLowerCase(Locale.ROOT);
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** A JPQL condition with its parameters. */
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
