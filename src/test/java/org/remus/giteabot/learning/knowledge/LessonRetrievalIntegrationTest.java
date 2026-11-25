package org.remus.giteabot.learning.knowledge;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.learning.context.LearningContextProvider;
import org.remus.giteabot.learning.context.LessonBundle;
import org.remus.giteabot.learning.context.LessonSelectionRequest;
import org.remus.giteabot.learning.feedback.FeedbackService;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningMode;
import org.remus.giteabot.prworkflow.agentreview.AgentReviewLearningSettings;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.comment;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.newRepository;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.scope;

/**
 * Lesson retrieval over lessons written and activated through the real services (H2): the curated
 * lesson reaches the next relevant agentic review of its repository — and nothing else — and its
 * usage is recorded against that review. This proves delivery, not that reviews got better.
 */
@SpringBootTest
@ActiveProfiles("test")
class LessonRetrievalIntegrationTest {

    private static final String TEXT =
            "Before reporting a missing permission check, inspect the enclosing security filter.";
    private static final String SETTINGS_FILE = "src/main/java/example/settings/SettingsController.java";
    private static final AgentReviewLearningSettings CURATED =
            new AgentReviewLearningSettings(true, AgentReviewLearningMode.CURATED, Set.of("alex"));

    @Autowired private KnowledgeLessonService lessons;
    @Autowired private FeedbackService feedbackService;
    @Autowired private StructuredReviewService reviewService;
    @Autowired private LearningContextProvider provider;
    @Autowired private KnowledgeLessonRevisionRepository revisionRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private LessonFixtures fixtures;
    private String repo;
    private String feedback;
    private String lesson;

    @BeforeEach
    void setUp() {
        fixtures = new LessonFixtures(reviewService, feedbackService);
        repo = newRepository();
        String firstReview = fixtures.finalizedReview(repo);
        feedback = fixtures.incorrectFinding(repo, firstReview, "alex");
        lesson = lessons.propose(scope(repo), comment(repo), feedback,
                LessonPathScope.parse("src/main/java/example/settings/**"), TEXT, "alex")
                .lessonReference();
        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");
    }

    private LessonSelectionRequest request(long integration, long bot, String repository,
                                           String workflow, List<String> changed) {
        return new LessonSelectionRequest(integration, bot, LessonFixtures.OWNER, repository, workflow,
                changed, "Settings endpoint", CURATED, 200_000);
    }

    private LessonSelectionRequest relevant() {
        return request(LessonFixtures.INTEGRATION, LessonFixtures.BOT, repo,
                LearningContextProvider.WORKFLOW_KEY, List.of(SETTINGS_FILE));
    }

    @Test
    void theNextRelevantReviewReceivesTheCuratedLessonAndItsUsageIsRecorded() {
        String secondReview = fixtures.finalizedReview(repo);
        LessonBundle bundle = provider.select(relevant());

        assertThat(bundle.status()).isEqualTo(LessonBundle.Status.SELECTED);
        assertThat(bundle.revisionReferences()).isEqualTo(lesson + "@1");
        assertThat(bundle.text()).contains("> " + TEXT)
                .contains("applies to src/main/java/example/settings/**")
                .contains("Evidence: feedback " + feedback + " on ");

        String invocation = UUID.randomUUID().toString();
        assertThat(provider.recordUsage(bundle, secondReview, invocation)).isEqualTo(1);
        // A retried recording of the same delivery counts nothing twice.
        assertThat(provider.recordUsage(bundle, secondReview, invocation)).isZero();

        KnowledgeLessonService.Summary summary =
                lessons.show(scope(repo), lesson).orElseThrow().summary();
        assertThat(summary.usageCount()).isEqualTo(1);
        assertThat(summary.current().usageCount()).isEqualTo(1);
    }

    @Test
    void theLessonIsAbsentForOtherRepositoriesBotsIntegrationsWorkflowsAndPaths() {
        assertThat(provider.select(request(LessonFixtures.INTEGRATION, LessonFixtures.BOT,
                newRepository(), LearningContextProvider.WORKFLOW_KEY, List.of(SETTINGS_FILE)))
                .hasGuidance()).as("other repository").isFalse();
        assertThat(provider.select(request(LessonFixtures.INTEGRATION, LessonFixtures.BOT + 100,
                repo, LearningContextProvider.WORKFLOW_KEY, List.of(SETTINGS_FILE)))
                .hasGuidance()).as("other bot").isFalse();
        assertThat(provider.select(request(LessonFixtures.INTEGRATION + 100, LessonFixtures.BOT,
                repo, LearningContextProvider.WORKFLOW_KEY, List.of(SETTINGS_FILE)))
                .hasGuidance()).as("other integration").isFalse();
        assertThat(provider.select(request(LessonFixtures.INTEGRATION, LessonFixtures.BOT, repo,
                "review", List.of(SETTINGS_FILE))).status())
                .as("normal review").isEqualTo(LessonBundle.Status.NOT_APPLICABLE);
        assertThat(provider.select(request(LessonFixtures.INTEGRATION, LessonFixtures.BOT, repo,
                LearningContextProvider.WORKFLOW_KEY, List.of("src/main/java/example/web/Home.java")))
                .hasGuidance()).as("unrelated paths").isFalse();
        assertThat(provider.select(relevant()).hasGuidance()).as("the relevant change").isTrue();
    }

    @Test
    void disablingTheLessonRevokesItForARunInFlightAndForTheNextRun() {
        LessonBundle inFlight = provider.select(relevant());

        lessons.disable(scope(repo), comment(repo), lesson, "Too broad.", "alex");

        assertThat(provider.revocation(relevant(), inFlight, CURATED))
                .hasValue(lesson + "@1 is no longer active");
        assertThat(provider.select(relevant()).hasGuidance()).isFalse();
    }

    @Test
    void activatingANewRevisionRevokesTheOneARunReceived() {
        LessonBundle inFlight = provider.select(relevant());

        lessons.revise(scope(repo), comment(repo), lesson, null,
                "Inspect the security filter chain before reporting a missing permission check.",
                "alex");
        lessons.activate(scope(repo), comment(repo), lesson, 2, "alex");

        assertThat(provider.revocation(relevant(), inFlight, CURATED))
                .hasValue(lesson + "@1 was replaced by " + lesson + "@2");
        assertThat(provider.select(relevant()).revisionReferences()).isEqualTo(lesson + "@2");
    }

    @Test
    void aConflictingLessonActivatedDuringTheRunRevokesTheGuidanceLikeLearningShowReportsIt() {
        LessonBundle inFlight = provider.select(relevant());

        String rival = lessons.propose(scope(repo), comment(repo), feedback, List.of(),
                "Report every endpoint without an explicit permission check.", "alex").lessonReference();
        lessons.activate(scope(repo), comment(repo), rival, 1, "alex");

        List<String> shown = lessons.show(scope(repo), lesson).orElseThrow().summary().validation()
                .conflicts();
        assertThat(shown).isNotEmpty();
        assertThat(provider.revocation(relevant(), inFlight, CURATED)).hasValueSatisfying(reason ->
                assertThat(reason).startsWith(lesson + "@1 no longer validates: ")
                        .contains(shown.getFirst()));
    }

    @Test
    void theRevocationCheckReadsOnlyTheSuppliedLessons() {
        LessonBundle inFlight = provider.select(relevant());
        // Unrelated active lessons of the same repository neither revoke nor are read.
        for (int i = 0; i < 3; i++) {
            String other = fixtures.incorrectFinding(repo, fixtures.finalizedReview(repo), "alex");
            String unrelated = lessons.propose(scope(repo), comment(repo), other,
                    List.of(), "Unrelated lesson " + i + ".", "alex").lessonReference();
            lessons.activate(scope(repo), comment(repo), unrelated, 1, "alex");
        }

        assertThat(provider.revocation(relevant(), inFlight, CURATED)).isEmpty();
        LessonBundle.SelectedLesson supplied = inFlight.lessons().getFirst();
        Map<Long, KnowledgeLessonService.Standing> standing =
                lessons.standing(scope(repo), Map.of(supplied.lessonId(), supplied.revisionId()));
        assertThat(standing).containsOnlyKeys(supplied.lessonId());
        assertThat(standing.get(supplied.lessonId()).problems()).isEmpty();
        assertThat(standing.get(supplied.lessonId()).activeRevision()).isEqualTo(1);
    }

    @Test
    void readingActiveLessonsAndTheirStandingTakesAFixedNumberOfQueries() {
        LessonBundle.SelectedLesson supplied = provider.select(relevant()).lessons().getFirst();
        long listingWithOne = statements(() -> lessons.activeLessons(scope(repo), 100));
        long standingWithOne = statements(() -> lessons.standing(scope(repo),
                Map.of(supplied.lessonId(), supplied.revisionId())));

        // Five more active lessons, each with its own evidence and two revisions, two of them
        // resting on the same finding as the first lesson (conflicts to resolve).
        for (int i = 0; i < 5; i++) {
            String source = i < 2 ? feedback
                    : fixtures.incorrectFinding(repo, fixtures.finalizedReview(repo), "alex");
            String more = lessons.propose(scope(repo), comment(repo), source, List.of(),
                    "Lesson " + i + ".", "alex").lessonReference();
            lessons.revise(scope(repo), comment(repo), more, null, "Lesson " + i + ", revised.", "alex");
            lessons.activate(scope(repo), comment(repo), more, 2, "alex");
        }
        assertThat(lessons.activeLessons(scope(repo), 100)).hasSize(6);

        assertThat(statements(() -> lessons.activeLessons(scope(repo), 100)))
                .as("listing queries").isEqualTo(listingWithOne);
        assertThat(statements(() -> lessons.standing(scope(repo),
                Map.of(supplied.lessonId(), supplied.revisionId()))))
                .as("standing queries").isEqualTo(standingWithOne);
    }

    @Test
    void selectionAndRevocationApplyTheSameRulesInTheSameWords() {
        LessonBundle.SelectedLesson supplied = provider.select(relevant()).lessons().getFirst();
        // Every rule at once: a conflicting lesson, an overdue recheck and a withdrawn evidence.
        String rival = lessons.propose(scope(repo), comment(repo), feedback, List.of(),
                "Report every endpoint without an explicit permission check.", "alex").lessonReference();
        lessons.activate(scope(repo), comment(repo), rival, 1, "alex");
        KnowledgeLessonRevision row = revisionRepository.findById(supplied.revisionId()).orElseThrow();
        row.setRecheckAfter(java.time.Instant.now().minusSeconds(60));
        revisionRepository.save(row);
        feedbackService.retract(scope(repo), feedback, "alex");

        KnowledgeLessonService.Validation shown = lessons.activeLessons(scope(repo), 100).stream()
                .filter(active -> active.summary().id().equals(supplied.lessonId()))
                .findFirst().orElseThrow().summary().validation();
        List<String> expected = new java.util.ArrayList<>(shown.issues());
        expected.addAll(shown.conflicts());
        assertThat(expected).anySatisfy(issue -> assertThat(issue).startsWith("review required"))
                .anySatisfy(issue -> assertThat(issue).contains(" is retracted"))
                .anySatisfy(issue -> assertThat(issue).startsWith("recheck due since"))
                .anySatisfy(issue -> assertThat(issue).contains(" also rests on "));

        assertThat(lessons.standing(scope(repo), Map.of(supplied.lessonId(), supplied.revisionId()))
                .get(supplied.lessonId()).problems()).isEqualTo(expected);
    }

    private long statements(Runnable read) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            read.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void retractedEvidenceTakesTheLessonOutOfReviews() {
        LessonBundle inFlight = provider.select(relevant());

        feedbackService.retract(scope(repo), feedback, "alex");

        assertThat(provider.revocation(relevant(), inFlight, CURATED))
                .hasValueSatisfying(reason -> assertThat(reason).contains("no longer validates"));
        LessonBundle next = provider.select(relevant());
        assertThat(next.hasGuidance()).isFalse();
        assertThat(next.excluded()).singleElement().satisfies(exclusion ->
                assertThat(exclusion.reason()).contains("review required"));
    }
}
