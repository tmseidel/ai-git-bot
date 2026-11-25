package org.remus.giteabot.learning.context;

import org.remus.giteabot.learning.feedback.FeedbackState;
import org.remus.giteabot.learning.feedback.FeedbackTargetType;
import org.remus.giteabot.learning.feedback.FeedbackVerdict;
import org.remus.giteabot.learning.knowledge.KnowledgeLessonService;
import org.remus.giteabot.learning.knowledge.LessonActivationProvenance;
import org.remus.giteabot.learning.knowledge.LessonState;

import java.time.Instant;
import java.util.List;

/**
 * Active lessons as {@link KnowledgeLessonService#activeLessons} returns them, for tests that stub
 * the lesson service instead of writing lessons through Git commands.
 */
public final class ActiveLessons {

    public static final String TEXT =
            "Before reporting a missing permission check, inspect the enclosing security filter.";

    private ActiveLessons() {
    }

    /** {@code L<id>@1}, active, valid, resting on effective feedback {@code FB31}. */
    public static KnowledgeLessonService.ActiveLesson lesson(long id, String text, List<String> paths,
                                                             String category) {
        return lesson(id, text, paths, category, List.of(), List.of(), Instant.now());
    }

    public static KnowledgeLessonService.ActiveLesson lesson(long id, String text, List<String> paths,
                                                             String category, List<String> issues,
                                                             List<String> conflicts,
                                                             Instant activatedAt) {
        var evidence = new KnowledgeLessonService.EvidenceView("FB31", "O1200-F2",
                FeedbackTargetType.FINDING, FeedbackVerdict.INCORRECT, FeedbackState.EFFECTIVE, null,
                "alex", 42L, "PR_COMMENT", "c1");
        var revision = new KnowledgeLessonService.RevisionView(1, LessonState.ACTIVE, text, paths,
                category, "0123456789abcdef", Instant.now().plusSeconds(86_400), "alex",
                Instant.now(), LessonActivationProvenance.MAINTAINER_CURATED, "alex", activatedAt,
                null, null, List.of(evidence), 0);
        var summary = new KnowledgeLessonService.Summary(id, "L" + id, LessonState.ACTIVE, 1, 1,
                revision, null, !issues.isEmpty(), issues.isEmpty() ? null : issues.getFirst(), 0,
                "owner", "repo", new KnowledgeLessonService.Validation(issues, conflicts));
        return new KnowledgeLessonService.ActiveLesson(summary, id * 10, "f".repeat(64), activatedAt);
    }
}
