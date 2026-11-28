package org.remus.giteabot.learning.knowledge;

import java.util.List;

/**
 * A parsed {@code @bot learning …} command.
 *
 * <p>Every one of these is consumed by the lesson pipeline: a recognized but unusable command
 * becomes {@link Help} and gets a short explanation, never an AI conversation.</p>
 */
public sealed interface LearningCommand {

    /** Commands that change a lesson; only learning maintainers may issue them. */
    sealed interface Management extends LearningCommand {
    }

    /**
     * {@code learning propose FB31 <text>}.
     *
     * @param paths validated patterns of a {@code paths:} line, {@code null} when there was none
     */
    record Propose(String feedbackReference, List<String> paths, String text) implements Management {
    }

    /**
     * {@code learning revise L17 <text>} — new wording, a new {@code paths:} line, or both.
     *
     * @param paths {@code null} to keep the current scope, empty for repository-wide
     * @param text  {@code null}/blank to keep the current wording
     */
    record Revise(String lessonReference, List<String> paths, String text) implements Management {
    }

    /** {@code learning activate L17@2} — always an exact revision. */
    record Activate(String lessonReference, int revision) implements Management {
    }

    /** {@code learning reject L17@2} — turn down a candidate revision. */
    record Reject(String lessonReference, int revision) implements Management {
    }

    /** {@code learning disable L17 [reason]}. */
    record Disable(String lessonReference, String reason) implements Management {
    }

    /** {@code learning show L17}. */
    record Show(String lessonReference) implements LearningCommand {
    }

    /**
     * {@code learning list [active|candidate|…] [page: <cursor>]}.
     *
     * @param state  {@code null} for every lifecycle state
     * @param cursor the opaque continuation of a previous page of the same listing
     */
    record ListLessons(LessonState state, String cursor) implements LearningCommand {
    }

    /** Recognized as a lesson command, but not actionable; {@code message} says why. */
    record Help(String message) implements LearningCommand {
    }
}
