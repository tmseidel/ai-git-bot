package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-field checks of the agentic-review learning preferences. The same checks run on admin saves,
 * configuration imports and runtime resolution, so each case is pinned once here.
 */
class AgentReviewLearningSettingsValidatorTest {

    @Test
    void theDefaultsAreValid() {
        assertThat(AgentReviewLearningSettingsValidator.validate(Map.of())).isEmpty();
        assertThat(AgentReviewLearningSettingsValidator.validate(null)).isEmpty();
    }

    @Test
    void curatedLearningMayRunWithoutCollection() {
        assertThat(AgentReviewLearningSettingsValidator.validate(Map.of(
                "feedbackEnabled", "false", "learningMode", "CURATED",
                "learningMaintainers", "alex"))).isEmpty();
    }

    @Test
    void anUnknownLearningModeIsRejected() {
        assertThat(AgentReviewLearningSettingsValidator.validate(Map.of("learningMode", "ASSISTED")))
                .singleElement().asString().contains("learningMode").contains("OFF, CURATED");
    }

    @Test
    void maintainerAccountsMustBeSingleAccountNames() {
        assertThat(AgentReviewLearningSettingsValidator.validate(Map.of(
                "learningMaintainers", "alex\nmaria smith\n<script>")))
                .hasSize(2)
                .anySatisfy(error -> assertThat(error).contains("maria smith"))
                .anySatisfy(error -> assertThat(error).contains("<script>"));
    }

    @Test
    void tooManyMaintainersAreRejected() {
        StringBuilder accounts = new StringBuilder();
        for (int i = 0; i <= AgentReviewLearningSettingsValidator.MAX_MAINTAINERS; i++) {
            accounts.append("user").append(i).append('\n');
        }
        Map<String, Object> params = new HashMap<>();
        params.put("learningMaintainers", accounts.toString());

        assertThat(AgentReviewLearningSettingsValidator.validate(params))
                .singleElement().asString().contains("at most");
    }
}
