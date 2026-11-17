package org.remus.giteabot.learning.feedback;

/**
 * The feedback hint appended to a published review when the bot collects feedback.
 *
 * <p>It lives outside the renderer: rendering is a pure function of the stored review, while whether
 * a bot accepts feedback is a configuration fact at publication time. A bot with collection disabled
 * appends nothing rather than advertising a command that would be refused.</p>
 */
public final class FeedbackHint {

    private FeedbackHint() {
    }

    /**
     * @param mention         the bot's provider mention, e.g. {@code @ai_bot}
     * @param outputReference the review this hint is attached to
     * @return the hint block, or {@code null} when the review should not advertise feedback
     */
    public static String forReview(String mention, String outputReference) {
        if (mention == null || mention.isBlank() || outputReference == null) {
            return null;
        }
        String exampleTarget = outputReference + "-F1";
        return """
                ---
                Feedback on this review is welcome: answer with `%s feedback <target> <verdict> <why>`,
                for example `%s feedback %s incorrect The check already exists in the filter`.
                Verdicts: %s. Targets: `%s` for the whole review, `%s`-style for one finding, or
                `%s-A` for the assessment. Feedback only records your assessment; it never re-runs
                the review.""".formatted(mention, mention, exampleTarget, FeedbackVerdict.tokens(),
                outputReference, outputReference, outputReference);
    }
}
