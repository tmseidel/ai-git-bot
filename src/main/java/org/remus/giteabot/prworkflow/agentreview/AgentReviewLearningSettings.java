package org.remus.giteabot.prworkflow.agentreview;

/**
 * Effective learning preferences of one bot's agentic review, resolved from its current PR
 * workflow configuration.
 *
 * <p>Absent configuration or no {@code agentic-review} selection means the documented defaults:
 * collection disabled, learning {@code OFF}, no learning maintainers. The mode and maintainer
 * preferences ship with the steps that implement them and sit beside this flag; resolving them here
 * keeps one place that answers "what does this bot do right now", rather than one path per caller.</p>
 */
public record AgentReviewLearningSettings(boolean feedbackEnabled) {

    public static final AgentReviewLearningSettings DISABLED = new AgentReviewLearningSettings(false);

    /** Stable description of the effective settings, hashed into the review's snapshot. */
    public String fingerprint() {
        return "feedbackEnabled=" + feedbackEnabled;
    }
}
