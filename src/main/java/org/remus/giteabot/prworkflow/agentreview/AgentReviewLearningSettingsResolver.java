package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the bot's <em>current</em> agentic-review learning preferences.
 *
 * <p>The same resolution has to serve review generation, Git commands and background jobs, so it
 * lives in one place instead of being re-derived per caller. A saved historical configuration
 * snapshot never grants authority here: only the configuration the bot is assigned right now
 * counts, and anything unreadable or unselected resolves to the disabled defaults.</p>
 */
@Slf4j
@Component
public class AgentReviewLearningSettingsResolver {

    private final WorkflowSelectionService selectionService;

    /**
     * {@code @Lazy} because the selection service reaches the workflow registry, which reaches the
     * workflows: resolving it eagerly would close the cycle at startup.
     */
    public AgentReviewLearningSettingsResolver(@Lazy WorkflowSelectionService selectionService) {
        this.selectionService = selectionService;
    }

    public AgentReviewLearningSettings resolve(Bot bot) {
        return resolve(bot != null ? bot.getWorkflowConfiguration() : null);
    }

    public AgentReviewLearningSettings resolve(WorkflowConfiguration configuration) {
        if (configuration == null || configuration.getId() == null) {
            return AgentReviewLearningSettings.DISABLED;
        }
        Map<String, Object> params;
        try {
            params = selectionService.resolveParams(configuration.getId(), AgentReviewWorkflow.KEY);
        } catch (RuntimeException e) {
            log.warn("Could not resolve agentic-review settings for configuration {}: {} — "
                    + "collection stays disabled", configuration.getId(), e.getMessage());
            return AgentReviewLearningSettings.DISABLED;
        }
        return from(params);
    }

    /**
     * The same conversion for callers that already hold the resolved params of this run, so a run
     * reads its configuration once and both the review and its evidence agree.
     */
    public static AgentReviewLearningSettings from(Map<String, Object> params) {
        if (params == null) {
            return AgentReviewLearningSettings.DISABLED;
        }
        List<String> errors = AgentReviewLearningSettingsValidator.validate(params);
        if (!errors.isEmpty()) {
            // A row the admin form would refuse (hand-edited, or from an older import): learning and
            // collection stay off rather than guessing which part was meant. Reviews are unaffected.
            log.error("Invalid agentic-review learning configuration — feedback collection and "
                    + "learning are disabled until it is fixed: {}", String.join("; ", errors));
            return AgentReviewLearningSettings.DISABLED;
        }
        return new AgentReviewLearningSettings(
                flag(params.get(AgentReviewParam.FEEDBACK_ENABLED.key())),
                AgentReviewLearningMode.parse(params.get(AgentReviewParam.LEARNING_MODE.key()))
                        .orElse(AgentReviewLearningMode.OFF),
                AgentReviewLearningSettingsValidator.maintainers(
                        params.get(AgentReviewParam.LEARNING_MAINTAINERS.key())));
    }

    private static boolean flag(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        return raw != null && ("true".equalsIgnoreCase(raw.toString().trim())
                || "1".equals(raw.toString().trim()));
    }
}
