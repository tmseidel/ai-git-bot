package org.remus.giteabot.learning.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.learning.feedback.FeedbackAcknowledgement;
import org.remus.giteabot.learning.feedback.FeedbackScope;
import org.remus.giteabot.learning.feedback.FeedbackService;
import org.remus.giteabot.learning.feedback.FeedbackSource;
import org.remus.giteabot.learning.feedback.FeedbackTarget;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.review.structured.StructuredReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.comment;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.newRepository;
import static org.remus.giteabot.learning.knowledge.LessonFixtures.scope;

/**
 * The lesson lifecycle against the real (H2) database of the test profile: a lesson is written from
 * stored feedback, activated by naming an exact revision, revised without touching active text,
 * disabled without losing history, and never visible outside its bot and repository.
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeLessonServiceTest {

    private static final String TEXT =
            "Before reporting a missing permission check, inspect the enclosing security filter.";

    @Autowired private KnowledgeLessonService lessons;
    @Autowired private FeedbackService feedbackService;
    @Autowired private StructuredReviewService reviewService;

    private String repo;
    private String output;
    private String feedback;

    @BeforeEach
    void setUp() {
        LessonFixtures fixtures = new LessonFixtures(reviewService, feedbackService);
        repo = newRepository();
        output = fixtures.finalizedReview(repo);
        feedback = fixtures.incorrectFinding(repo, output, "alex");
    }

    @Test
    void aProposalIsARepositoryWideCandidateWithItsEvidenceAndScopeSuggestion() {
        KnowledgeLessonService.Recorded recorded = lessons.propose(scope(repo), comment(repo),
                feedback, null, TEXT, "alex");

        assertThat(recorded.revision()).isEqualTo(1);
        assertThat(recorded.type()).isEqualTo(LessonEventType.PROPOSED);
        assertThat(recorded.duplicate()).isFalse();
        assertThat(recorded.acknowledgement()).isEqualTo(FeedbackAcknowledgement.PENDING);

        KnowledgeLessonService.Detail detail = show(recorded.lessonReference());
        KnowledgeLessonService.Summary summary = detail.summary();
        assertThat(summary.state()).isEqualTo(LessonState.CANDIDATE);
        assertThat(summary.activeRevision()).isNull();
        assertThat(summary.current().text()).isEqualTo(TEXT);
        assertThat(summary.current().repositoryWide()).isTrue();
        assertThat(summary.current().category()).isEqualTo("authorization");
        assertThat(summary.current().sourceCommitSha()).isEqualTo(LessonFixtures.HEAD_SHA);
        assertThat(summary.current().recheckAfter()).isNotNull();
        assertThat(summary.current().evidence()).singleElement().satisfies(cited -> {
            assertThat(cited.feedbackReference()).isEqualTo(feedback);
            assertThat(cited.targetReference()).isEqualTo(output + "-F1");
            assertThat(cited.effective()).isTrue();
        });
        assertThat(summary.usageCount()).isZero();
        // The finding's location is offered, never applied: the scope stays explicit.
        assertThat(detail.suggestedPaths()).containsExactly("src/Foo.java");
        assertThat(summary.validation().valid()).isTrue();
    }

    @Test
    void aProposalKeepsTheValidatedPathScope() {
        String lesson = lessons.propose(scope(repo), comment(repo), feedback,
                LessonPathScope.parse("src/main/java/example/settings/**"), TEXT, "alex")
                .lessonReference();

        KnowledgeLessonService.Detail detail = show(lesson);
        assertThat(detail.summary().current().paths())
                .containsExactly("src/main/java/example/settings/**");
        assertThat(detail.suggestedPaths()).isEmpty();
    }

    @Test
    void activationNamesTheExactRevisionAndIsMaintainerCurated() {
        String lesson = propose();

        assertThatThrownBy(() -> lessons.activate(scope(repo), comment(repo), lesson, 2, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("unknown-revision"));

        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");

        KnowledgeLessonService.RevisionView active = show(lesson).summary().current();
        assertThat(show(lesson).summary().state()).isEqualTo(LessonState.ACTIVE);
        assertThat(active.state()).isEqualTo(LessonState.ACTIVE);
        assertThat(active.activationProvenance())
                .isEqualTo(LessonActivationProvenance.MAINTAINER_CURATED);
        assertThat(active.activatedBy()).isEqualTo("alex");
        assertThatThrownBy(() -> lessons.activate(scope(repo), comment(repo), lesson, 1, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("already-active"));
    }

    @Test
    void revisingNeverTouchesTheActiveTextAndAnObsoleteRevisionCannotBeActivated() {
        String lesson = propose();
        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");

        KnowledgeLessonService.Recorded revised = lessons.revise(scope(repo), comment(repo), lesson,
                LessonPathScope.parse("src/**"), "Inspect the filter chain first.", "alex");

        assertThat(revised.revision()).isEqualTo(2);
        KnowledgeLessonService.Summary summary = show(lesson).summary();
        assertThat(summary.state()).isEqualTo(LessonState.ACTIVE);
        assertThat(summary.activeRevision()).isEqualTo(1);
        assertThat(summary.current().text()).isEqualTo(TEXT);
        assertThat(summary.pendingRevision()).isEqualTo(2);

        assertThatThrownBy(() -> lessons.activate(scope(repo), comment(repo), lesson, 1, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class, rejected -> {
                    assertThat(rejected.getCode()).isEqualTo("obsolete-revision");
                    assertThat(rejected.getMessage()).contains(lesson + "@2");
                });

        lessons.activate(scope(repo), comment(repo), lesson, 2, "alex");
        KnowledgeLessonService.Detail detail = show(lesson);
        assertThat(detail.revisions()).extracting(KnowledgeLessonService.RevisionView::state)
                .containsExactly(LessonState.SUPERSEDED, LessonState.ACTIVE);
        assertThat(detail.revisions().getFirst().text()).isEqualTo(TEXT);
        assertThat(detail.summary().current().text()).isEqualTo("Inspect the filter chain first.");
        // The evidence travels with every revision.
        assertThat(detail.summary().current().evidence()).extracting(
                KnowledgeLessonService.EvidenceView::feedbackReference).containsExactly(feedback);
    }

    @Test
    void aRevisionMustChangeSomething() {
        String lesson = propose();

        assertThatThrownBy(() -> lessons.revise(scope(repo), comment(repo), lesson, null, TEXT, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("unchanged"));
    }

    @Test
    void revisingACandidateSupersedesIt() {
        String lesson = propose();

        lessons.revise(scope(repo), comment(repo), lesson, null, "A sharper wording.", "alex");

        assertThat(show(lesson).revisions()).extracting(KnowledgeLessonService.RevisionView::state)
                .containsExactly(LessonState.SUPERSEDED, LessonState.CANDIDATE);
        assertThat(show(lesson).summary().state()).isEqualTo(LessonState.CANDIDATE);
    }

    @Test
    void disablingKeepsHistoryAndTheLatestRevisionCanBeActivatedAgain() {
        String lesson = propose();
        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");

        lessons.disable(scope(repo), comment(repo), lesson, "Too broad.", "alex");

        KnowledgeLessonService.Detail detail = show(lesson);
        assertThat(detail.summary().state()).isEqualTo(LessonState.DISABLED);
        assertThat(detail.summary().activeRevision()).isNull();
        assertThat(detail.summary().current().state()).isEqualTo(LessonState.DISABLED);
        assertThat(detail.history()).extracting(KnowledgeLessonService.HistoryEntry::type)
                .containsExactly(LessonEventType.PROPOSED, LessonEventType.ACTIVATED,
                        LessonEventType.DISABLED);
        assertThat(detail.history().getLast().detail()).isEqualTo("Too broad.");
        assertThatThrownBy(() -> lessons.disable(scope(repo), comment(repo), lesson, null, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("already-disabled"));

        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");
        assertThat(show(lesson).summary().state()).isEqualTo(LessonState.ACTIVE);
    }

    @Test
    void disablingRejectsAWaitingCandidate() {
        String lesson = propose();
        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");
        lessons.revise(scope(repo), comment(repo), lesson, null, "Another wording.", "alex");

        lessons.disable(scope(repo), comment(repo), lesson, null, "alex");

        assertThat(show(lesson).revisions()).extracting(KnowledgeLessonService.RevisionView::state)
                .containsExactly(LessonState.DISABLED, LessonState.REJECTED);
        assertThat(show(lesson).summary().state()).isEqualTo(LessonState.DISABLED);
    }

    @Test
    void aRejectedCandidateCannotBeActivatedUntilRevised() {
        String lesson = propose();

        lessons.reject(scope(repo), comment(repo), lesson, 1, "alex");

        assertThat(show(lesson).summary().state()).isEqualTo(LessonState.REJECTED);
        assertThatThrownBy(() -> lessons.activate(scope(repo), comment(repo), lesson, 1, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("rejected"));
        assertThatThrownBy(() -> lessons.disable(scope(repo), comment(repo), lesson, null, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("not-active"));
    }

    @Test
    void retractedEvidenceFlagsTheLessonAndCannotSupportAnActivation() {
        String lesson = propose();
        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");

        feedbackService.retract(scope(repo), feedback, "alex");

        KnowledgeLessonService.Detail detail = show(lesson);
        assertThat(detail.summary().reviewRequired()).isTrue();
        assertThat(detail.summary().reviewReason()).contains(feedback).contains("retracted");
        assertThat(detail.summary().validation().valid()).isFalse();
        assertThat(detail.summary().validation().eligibleForAutomaticMerge()).isFalse();
        assertThat(detail.summary().validation().issues())
                .anySatisfy(issue -> assertThat(issue).contains(feedback + " is retracted"));
        assertThat(detail.history().getLast().type()).isEqualTo(LessonEventType.EVIDENCE_WITHDRAWN);
        // The flag does not switch the lesson off: that stays a maintainer's decision.
        assertThat(detail.summary().state()).isEqualTo(LessonState.ACTIVE);

        lessons.revise(scope(repo), comment(repo), lesson, null, "Revised wording.", "alex");
        assertThatThrownBy(() -> lessons.activate(scope(repo), comment(repo), lesson, 2, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("unsupported"));
        assertThatThrownBy(() -> lessons.propose(scope(repo), comment(repo), feedback, null, TEXT,
                "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("retracted-feedback"));
    }

    @Test
    void aCorrectionByTheSameReviewerAlsoFlagsTheLesson() {
        String lesson = propose();

        String correction = feedbackService.submit(scope(repo), comment(repo),
                FeedbackTarget.finding(output, 1), FeedbackVerdict.USEFUL, "I was wrong.", "alex")
                .reference();

        KnowledgeLessonService.Summary summary = show(lesson).summary();
        assertThat(summary.reviewRequired()).isTrue();
        assertThat(summary.reviewReason()).contains(correction);
        assertThat(summary.validation().issues())
                .anySatisfy(issue -> assertThat(issue).contains("superseded (by " + correction + ")"));
    }

    @Test
    void aLessonIsInvisibleOutsideItsBotAndRepository() {
        String lesson = propose();
        FeedbackScope otherRepository = new FeedbackScope(1L, 2L, "acme", newRepository(), 1L);
        FeedbackScope otherBot = new FeedbackScope(1L, 3L, "acme", repo, 1L);
        FeedbackScope otherHost = new FeedbackScope(9L, 2L, "acme", repo, 1L);

        for (FeedbackScope foreign : List.of(otherRepository, otherBot, otherHost)) {
            assertThat(lessons.show(foreign, lesson)).isEmpty();
            assertThat(lessons.list(foreign, null, 20, null).rows()).isEmpty();
            assertThatThrownBy(() -> lessons.activate(foreign, comment(repo), lesson, 1, "alex"))
                    .isInstanceOfSatisfying(LessonRejectedException.class,
                            rejected -> assertThat(rejected.getCode()).isEqualTo("unknown-lesson"));
            assertThatThrownBy(() -> lessons.propose(foreign, comment(repo), feedback, null, TEXT,
                    "alex"))
                    .isInstanceOfSatisfying(LessonRejectedException.class,
                            rejected -> assertThat(rejected.getCode()).isEqualTo("unknown-feedback"));
        }
        // Another pull request of the same repository is the same scope.
        assertThat(lessons.show(new FeedbackScope(1L, 2L, "ACME", repo, 77L), lesson)).isPresent();
    }

    @Test
    void aRedeliveredCommandChangesNothing() {
        FeedbackSource source = comment(repo);
        KnowledgeLessonService.Recorded first = lessons.propose(scope(repo), source, feedback, null,
                TEXT, "alex");

        KnowledgeLessonService.Recorded replay = lessons.propose(scope(repo), source, feedback, null,
                TEXT, "alex");

        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.lessonReference()).isEqualTo(first.lessonReference());
        assertThat(replay.acknowledgementEventId()).isEqualTo(first.acknowledgementEventId());
        assertThat(lessons.list(scope(repo), null, 20, null).rows()).hasSize(1);
    }

    @Test
    void confirmationsAreTrackedLikeFeedbackConfirmations() {
        FeedbackSource source = comment(repo);
        KnowledgeLessonService.Recorded first = lessons.propose(scope(repo), source, feedback, null,
                TEXT, "alex");
        lessons.markAcknowledgement(first.acknowledgementEventId(), FeedbackAcknowledgement.FAILED);

        KnowledgeLessonService.Recorded replay = lessons.propose(scope(repo), source, feedback, null,
                TEXT, "alex");

        assertThat(replay.acknowledgement()).isEqualTo(FeedbackAcknowledgement.FAILED);
        assertThat(lessons.claimAcknowledgementRetry(replay.acknowledgementEventId())).isTrue();
        assertThat(lessons.claimAcknowledgementRetry(replay.acknowledgementEventId())).isFalse();
    }

    @Test
    void listingFiltersByStateAndPagesWithACursorBoundToTheFilter() {
        String first = propose();
        String second = propose();
        String third = propose();
        lessons.activate(scope(repo), comment(repo), second, 1, "alex");

        assertThat(lessons.list(scope(repo), LessonState.ACTIVE, 20, null).rows())
                .extracting(KnowledgeLessonService.Summary::reference).containsExactly(second);

        KnowledgeLessonService.ListPage page = lessons.list(scope(repo), null, 2, null);
        assertThat(page.rows()).extracting(KnowledgeLessonService.Summary::reference)
                .containsExactly(third, second);
        assertThat(page.nextCursor()).isNotNull();
        assertThat(lessons.list(scope(repo), null, 2, page.nextCursor()).rows())
                .extracting(KnowledgeLessonService.Summary::reference).containsExactly(first);
        assertThatThrownBy(() -> lessons.list(scope(repo), LessonState.ACTIVE, 2, page.nextCursor()))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("invalid-cursor"));
    }

    @Test
    void feedbackKnowsWhichLessonsCiteIt() {
        String lesson = propose();
        Long feedbackId = feedbackService.show(scope(repo), feedback).orElseThrow().summary().id();

        assertThat(lessons.lessonsCiting(scope(repo), feedbackId))
                .containsExactly(lesson + "@1 (candidate)");
        assertThat(lessons.lessonsCiting(LessonFixtures.scope(newRepository()), feedbackId)).isEmpty();
    }

    @Test
    void twoActiveLessonsFromTheSameFindingAreFlaggedAsConflicting() {
        String first = propose();
        String second = lessons.propose(scope(repo), comment(repo), feedback, null,
                "Always report missing permission checks.", "alex").lessonReference();
        lessons.activate(scope(repo), comment(repo), first, 1, "alex");
        lessons.activate(scope(repo), comment(repo), second, 1, "alex");

        KnowledgeLessonService.Validation validation = show(first).summary().validation();
        assertThat(validation.conflicts()).singleElement().asString()
                .contains(second + "@1").contains(output + "-F1");
        assertThat(validation.eligibleForAutomaticMerge()).isFalse();
    }

    @Test
    void aStaleLessonLeavesUseAndMustBeRevised() {
        String lesson = propose();
        lessons.activate(scope(repo), comment(repo), lesson, 1, "alex");
        Long id = show(lesson).summary().id();

        lessons.markStale(id, "src/Foo.java changed", "system");

        assertThat(show(lesson).summary().state()).isEqualTo(LessonState.STALE);
        assertThatThrownBy(() -> lessons.activate(scope(repo), comment(repo), lesson, 1, "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("stale"));
        lessons.revise(scope(repo), comment(repo), lesson, null, "Current wording.", "alex");
        lessons.activate(scope(repo), comment(repo), lesson, 2, "alex");
        assertThat(show(lesson).summary().state()).isEqualTo(LessonState.ACTIVE);
    }

    @Test
    void lessonTextIsRequiredAndNeverTruncated() {
        assertThatThrownBy(() -> lessons.propose(scope(repo), comment(repo), feedback, null, "  ",
                "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("missing-text"));
        assertThatThrownBy(() -> lessons.propose(scope(repo), comment(repo), feedback, null,
                "x".repeat(KnowledgeLessonService.MAX_LESSON_CHARS + 1), "alex"))
                .isInstanceOfSatisfying(LessonRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("text-too-long"));
    }

    private String propose() {
        return lessons.propose(scope(repo), comment(repo), feedback, null, TEXT, "alex")
                .lessonReference();
    }

    private KnowledgeLessonService.Detail show(String lesson) {
        return lessons.show(scope(repo), lesson).orElseThrow();
    }
}
