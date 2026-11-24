package org.remus.giteabot.learning.knowledge;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.learning.feedback.FeedbackService;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.comment;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.newRepository;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.scope;

/**
 * Racing commands against the real (H2) database: a redelivered comment creates one lesson, and of
 * several maintainers activating the same revision at the same moment exactly one wins — the others
 * are told so, and the history shows one activation.
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeLessonConcurrencyTest {

    private static final int RACERS = 6;

    @Autowired private KnowledgeLessonService lessons;
    @Autowired private FeedbackService feedbackService;
    @Autowired private StructuredReviewService reviewService;

    @Test
    void simultaneousDeliveriesOfOneProposalCreateOneLesson() throws Exception {
        LessonFixtures fixtures = new LessonFixtures(reviewService, feedbackService);
        String repo = newRepository();
        String feedback = fixtures.incorrectFinding(repo, fixtures.finalizedReview(repo), "alex");
        FeedbackSource sameComment = comment(repo);

        List<Outcome> outcomes = race(() -> lessons.propose(scope(repo), sameComment, feedback, null,
                "Inspect the filter first.", "alex"));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes.stream().filter(outcome -> !outcome.recorded().duplicate()).count())
                .isEqualTo(1);
        assertThat(outcomes.stream().map(outcome -> outcome.recorded().lessonReference()).distinct())
                .hasSize(1);
        assertThat(lessons.list(scope(repo), null, 20, null).rows()).hasSize(1);
    }

    @Test
    void ofSimultaneousActivationsExactlyOneWins() throws Exception {
        LessonFixtures fixtures = new LessonFixtures(reviewService, feedbackService);
        String repo = newRepository();
        String feedback = fixtures.incorrectFinding(repo, fixtures.finalizedReview(repo), "alex");
        String lesson = lessons.propose(scope(repo), comment(repo), feedback, null,
                "Inspect the filter first.", "alex").lessonReference();

        List<Outcome> outcomes = race(() -> lessons.activate(scope(repo), comment(repo), lesson, 1,
                "alex"));

        assertThat(outcomes.stream().filter(outcome -> outcome.recorded() != null)).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null))
                .hasSize(RACERS - 1)
                .allSatisfy(outcome -> assertThat(outcome.failure())
                        .isInstanceOfSatisfying(LessonRejectedException.class, rejected ->
                                assertThat(rejected.getCode()).isIn("already-active",
                                        "concurrent-change")));
        KnowledgeLessonService.Detail detail = lessons.show(scope(repo), lesson).orElseThrow();
        assertThat(detail.summary().state()).isEqualTo(LessonState.ACTIVE);
        assertThat(detail.history().stream()
                .filter(entry -> entry.type() == LessonEventType.ACTIVATED)).hasSize(1);
    }

    private record Outcome(KnowledgeLessonService.Recorded recorded, Throwable failure) {
    }

    private static List<Outcome> race(Callable<KnowledgeLessonService.Recorded> command)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<KnowledgeLessonService.Recorded>> futures = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return command.call();
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<KnowledgeLessonService.Recorded> future : futures) {
                try {
                    outcomes.add(new Outcome(future.get(30, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome(null, e.getCause()));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
