package org.remus.giteabot.learning.feedback;

/**
 * A parsed {@code @bot feedback …} command.
 *
 * <p>Every one of these is consumed by the feedback pipeline: a recognized but unusable command
 * becomes {@link Help} and gets a short explanation, never an AI conversation, so malformed input
 * cannot start code review, code changes, test generation, or a coding session.</p>
 */
public sealed interface FeedbackCommand {

    record Submit(FeedbackTarget target, FeedbackVerdict verdict, String reason)
            implements FeedbackCommand {
    }

    record Retract(String reference) implements FeedbackCommand {
    }

    /**
     * {@code repositoryWide} distinguishes {@code feedback list repository} from {@code list};
     * {@code cursor} is the {@code page: <n>} continuation of a previous page, resolved again
     * inside the caller's own scope so a cursor cannot reach another repository's records.
     */
    record ListRecords(boolean repositoryWide, Long cursor) implements FeedbackCommand {
    }

    record Show(String reference) implements FeedbackCommand {
    }

    /** Recognized as feedback, but not actionable; {@code message} says why. */
    record Help(String message) implements FeedbackCommand {
    }
}
