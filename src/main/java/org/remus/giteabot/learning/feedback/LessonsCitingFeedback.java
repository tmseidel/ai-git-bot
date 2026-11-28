package org.remus.giteabot.learning.feedback;

import java.util.List;

/**
 * Lessons that rest on a feedback record, for the feedback overview. Implemented by the lesson
 * store; feedback itself knows nothing about lessons beyond this read-only question.
 */
public interface LessonsCitingFeedback {

    /** {@code L17@1 (active)}-style labels, limited to the scope's bot and repository. */
    List<String> lessonsCiting(FeedbackScope scope, Long feedbackId);
}
