package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.learning.context.LearningContextProvider;

import java.util.function.Supplier;

/**
 * Explicit lesson-retrieval collaborators of one bot's agentic review, handed in by
 * {@link AgentReviewServiceFactory}. Only the agentic review is ever given these; the normal review
 * path has no way to reach them.
 *
 * @param provider        selects and records lessons, and checks whether supplied ones still hold
 * @param currentSettings the bot's learning settings as configured right now — rechecked during a
 *                        run, so switching learning off stops a run that uses lessons
 */
public record AgentReviewLessons(LearningContextProvider provider,
                                 Supplier<AgentReviewLearningSettings> currentSettings) {

    /** No lesson retrieval at all. */
    public static final AgentReviewLessons NONE = new AgentReviewLessons(null, null);

    public boolean available() {
        return provider != null;
    }

    public AgentReviewLearningSettings current() {
        return currentSettings == null ? AgentReviewLearningSettings.DISABLED : currentSettings.get();
    }
}
