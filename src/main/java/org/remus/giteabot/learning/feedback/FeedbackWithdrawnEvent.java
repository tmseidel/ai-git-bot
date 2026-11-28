package org.remus.giteabot.learning.feedback;

/**
 * Published inside the transaction that retracts or supersedes a feedback record, so everything
 * resting on that feedback — lessons citing it as evidence — is flagged in the same commit. A
 * listener failure rolls the withdrawal back rather than leaving a lesson silently unsupported.
 *
 * @param feedbackId        the withdrawn record
 * @param feedbackReference its {@code FB…} reference
 * @param newState          {@link FeedbackState#RETRACTED} or {@link FeedbackState#SUPERSEDED}
 * @param replacedBy        the superseding record's reference, for a supersession
 * @param actor             who caused the withdrawal
 */
public record FeedbackWithdrawnEvent(Long feedbackId, String feedbackReference,
                                     FeedbackState newState, String replacedBy, String actor) {

    /** Human-readable cause, e.g. {@code FB31 was retracted by alice}. */
    public String describe() {
        return newState == FeedbackState.SUPERSEDED
                ? feedbackReference + " was replaced by " + replacedBy + " (" + actor + ")"
                : feedbackReference + " was retracted by " + actor;
    }
}
