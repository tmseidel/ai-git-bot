package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The resolver is the one answer to "what does this bot collect right now": absent configuration or
 * selection (and an unreadable selection) all mean the disabled defaults, never an inherited grant.
 */
class AgentReviewLearningSettingsResolverTest {

    private final WorkflowSelectionService selections = mock(WorkflowSelectionService.class);
    private final AgentReviewLearningSettingsResolver resolver =
            new AgentReviewLearningSettingsResolver(selections);

    @Test
    void absentConfigurationOrSelectionMeansDisabled() {
        assertThat(resolver.resolve((WorkflowConfiguration) null)).isEqualTo(AgentReviewLearningSettings.DISABLED);
        assertThat(resolver.resolve(new WorkflowConfiguration())).isEqualTo(AgentReviewLearningSettings.DISABLED);
        assertThat(resolver.resolve((Bot) null)).isEqualTo(AgentReviewLearningSettings.DISABLED);

        WorkflowConfiguration configuration = configuration(7L);
        when(selections.resolveParams(7L, "agentic-review")).thenReturn(Map.of());
        AgentReviewLearningSettings settings = resolver.resolve(configuration);
        assertThat(settings.feedbackEnabled()).isFalse();
        assertThat(settings.learningMode()).isEqualTo(AgentReviewLearningMode.OFF);
        assertThat(settings.learningMaintainers()).isEmpty();
    }

    @Test
    void readsTheCurrentConfigurationOnly() {
        WorkflowConfiguration configuration = configuration(7L);
        when(selections.resolveParams(7L, "agentic-review")).thenReturn(Map.of("feedbackEnabled", true));

        AgentReviewLearningSettings settings = resolver.resolve(configuration);

        assertThat(settings.feedbackEnabled()).isTrue();
        assertThat(settings.fingerprint())
                .isEqualTo("feedbackEnabled=true;learningMode=OFF;learningMaintainers=");
    }

    @Test
    void readsTheLearningModeAndNormalizesMaintainers() {
        WorkflowConfiguration configuration = configuration(7L);
        when(selections.resolveParams(7L, "agentic-review")).thenReturn(Map.of(
                "feedbackEnabled", true,
                "learningMode", "curated",
                "learningMaintainers", " @Alex\nmaria, alex \n\n"));

        AgentReviewLearningSettings settings = resolver.resolve(configuration);

        assertThat(settings.learningMode()).isEqualTo(AgentReviewLearningMode.CURATED);
        assertThat(settings.learningMaintainers()).containsExactly("alex", "maria");
        assertThat(settings.isLearningMaintainer("ALEX")).isTrue();
        assertThat(settings.isLearningMaintainer("@maria")).isTrue();
        assertThat(settings.isLearningMaintainer("mallory")).isFalse();
        assertThat(settings.fingerprint())
                .isEqualTo("feedbackEnabled=true;learningMode=CURATED;learningMaintainers=alex,maria");
    }

    @Test
    void anEmptyMaintainerListGrantsNobody() {
        AgentReviewLearningSettings settings = new AgentReviewLearningSettings(true,
                AgentReviewLearningMode.CURATED, Set.of());

        assertThat(settings.isLearningMaintainer("alex")).isFalse();
        assertThat(settings.isLearningMaintainer("")).isFalse();
        assertThat(settings.isLearningMaintainer(null)).isFalse();
    }

    @Test
    void anInvalidLearningConfigurationDisablesCollectionAndLearning() {
        WorkflowConfiguration configuration = configuration(7L);
        when(selections.resolveParams(7L, "agentic-review")).thenReturn(Map.of(
                "feedbackEnabled", true,
                "learningMode", "EVERYTHING",
                "learningMaintainers", "alex"));

        assertThat(resolver.resolve(configuration)).isEqualTo(AgentReviewLearningSettings.DISABLED);
    }

    @Test
    void anUnreadableSelectionStaysDisabled() {
        WorkflowConfiguration configuration = configuration(7L);
        when(selections.resolveParams(7L, "agentic-review"))
                .thenThrow(new IllegalStateException("selection storage unreachable"));

        assertThat(resolver.resolve(configuration).feedbackEnabled()).isFalse();
    }

    private static WorkflowConfiguration configuration(Long id) {
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(id);
        return configuration;
    }
}
