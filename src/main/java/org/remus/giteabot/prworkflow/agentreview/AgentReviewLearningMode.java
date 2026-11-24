package org.remus.giteabot.prworkflow.agentreview;

import java.util.Locale;
import java.util.Optional;

/**
 * How an agentic review learns from feedback, chosen per workflow configuration.
 *
 * <p>Modes ship with the steps that implement them: {@code ASSISTED} (AI proposes lessons) and
 * {@code EVALUATED_AUTO} are not offered until their machinery exists, so a configuration can never
 * select a behaviour the bot would silently ignore.</p>
 */
public enum AgentReviewLearningMode {

    /** Feedback may be recorded; lessons are neither created nor used. Stored data is kept. */
    OFF,

    /** Learning maintainers write and activate lessons through Git comments; no AI extraction. */
    CURATED;

    /** The mode for a persisted or imported value, or empty when it names no shipped mode. */
    public static Optional<AgentReviewLearningMode> parse(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.toString().trim().toUpperCase(Locale.ROOT);
        for (AgentReviewLearningMode mode : values()) {
            if (mode.name().equals(text)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
