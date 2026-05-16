package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 7.2 — sanity-checks for {@link AgentConfigProperties.BudgetConfig}
 * defaults and {@link AgentConfigProperties.CriticConfig} defaults.
 *
 * <p>The previous legacy-migration tests (covering the long-since-removed
 * {@code agent.max-tokens} / {@code agent.validation.max-retries} fields
 * and the {@code applyLegacyBudgetDefaults()} @PostConstruct hook) have
 * been dropped together with the migration itself.</p>
 */
class AgentConfigPropertiesBudgetTest {

    @Test
    void budgetDefaultsMatchSpec() {
        AgentConfigProperties.BudgetConfig b = new AgentConfigProperties.BudgetConfig();
        assertThat(b.getMaxRounds()).isEqualTo(10);
        assertThat(b.getMaxContextRounds()).isEqualTo(3);
        assertThat(b.getMaxValidationRetries()).isEqualTo(3);
        assertThat(b.getMaxContextToolRequestsPerRound()).isEqualTo(5);
        assertThat(b.getMaxTokensPerCall()).isEqualTo(16384);
    }


    @Test
    void criticDefaultsAreOff() {
        AgentConfigProperties.CriticConfig c = new AgentConfigProperties.CriticConfig();
        assertThat(c.isEnabled()).isFalse();
        assertThat(c.getMaxIterations()).isEqualTo(1);
        assertThat(c.getRequireApprovalFor()).isEmpty();
    }
}

