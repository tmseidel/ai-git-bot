package org.remus.giteabot.prworkflow.agentreview;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Effective learning preferences of one bot's agentic review, resolved from its current PR
 * workflow configuration.
 *
 * <p>Absent configuration or no {@code agentic-review} selection means the documented defaults:
 * collection disabled, learning {@code OFF}, no learning maintainers. Resolving all of them here
 * keeps one place that answers "what does this bot do right now", rather than one path per caller.</p>
 *
 * @param feedbackEnabled     accept {@code @bot feedback …} commands and keep learning evidence
 * @param learningMode        how lessons are created and used
 * @param learningMaintainers accounts allowed to propose, activate and disable lessons, normalized
 *                            (lower case, no leading {@code @}); empty grants nobody
 */
public record AgentReviewLearningSettings(boolean feedbackEnabled,
                                          AgentReviewLearningMode learningMode,
                                          Set<String> learningMaintainers) {

    public static final AgentReviewLearningSettings DISABLED = new AgentReviewLearningSettings(false);

    public AgentReviewLearningSettings {
        learningMode = learningMode == null ? AgentReviewLearningMode.OFF : learningMode;
        Set<String> normalized = new LinkedHashSet<>();
        if (learningMaintainers != null) {
            for (String account : learningMaintainers) {
                String value = AgentReviewLearningSettingsValidator.normalizeAccount(account);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        learningMaintainers = java.util.Collections.unmodifiableSet(normalized);
    }

    /** Collection preference only: learning off, no maintainers. */
    public AgentReviewLearningSettings(boolean feedbackEnabled) {
        this(feedbackEnabled, AgentReviewLearningMode.OFF, Set.of());
    }

    /**
     * Whether {@code actor} may manage lessons. An empty maintainer list grants nobody — unlike the
     * user whitelist, where empty means "everyone" for collecting opinions.
     */
    public boolean isLearningMaintainer(String actor) {
        String normalized = AgentReviewLearningSettingsValidator.normalizeAccount(actor);
        return normalized != null && learningMaintainers.contains(normalized);
    }

    /** Whether maintainers may create and activate lessons right now. */
    public boolean curatedLearning() {
        return learningMode == AgentReviewLearningMode.CURATED;
    }

    /** Stable description of the effective settings, hashed into the review's snapshot. */
    public String fingerprint() {
        return "feedbackEnabled=" + feedbackEnabled
                + ";learningMode=" + learningMode.name()
                + ";learningMaintainers=" + String.join(",", new TreeSet<>(learningMaintainers));
    }
}
