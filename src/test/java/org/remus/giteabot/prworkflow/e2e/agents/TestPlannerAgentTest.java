package org.remus.giteabot.prworkflow.e2e.agents;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestPlannerAgentTest {

    private final PrWorkflowToolExecutor toolExecutor = mock(PrWorkflowToolExecutor.class);
    private final TestPlannerAgent agent = new TestPlannerAgent(toolExecutor);

    @Test
    void returnsParsedPlanWhenModelEmitsJson() {
        StubAiClient ai = new StubAiClient(true).withTextTurn("""
                {
                  "framework": "playwright",
                  "journeys": [
                    {"id":"login","title":"Login","steps":["a"],"assertions":["b"],"fileName":"tests/login.spec.ts"},
                    {"id":"cart","title":"Cart","steps":["a"],"assertions":["b"]}
                  ],
                  "maxRetries": 1
                }
                """);

        Optional<TestPlan> plan = agent.plan(ai, E2eTestFramework.PLAYWRIGHT,
                new TestPlannerAgent.PlannerInput("Add cart endpoint", "Body",
                        "diff --git a/srv/cart.js b/srv/cart.js"));

        assertThat(plan).isPresent();
        assertThat(plan.get().journeys()).hasSize(2);
        // The planner runner must not advertise any tools.
        assertThat(ai.invocations()).hasSize(1);
        assertThat(ai.invocations().get(0).tools()).isEmpty();
        assertThat(ai.invocations().get(0).systemPrompt())
                .contains("TestPlannerAgent")
                .contains("playwright");
        assertThat(ai.invocations().get(0).newUserMessage())
                .contains("Add cart endpoint")
                .contains("```diff");
    }

    @Test
    void returnsEmptyWhenModelReturnsProse() {
        StubAiClient ai = new StubAiClient(true).withTextTurn("I cannot produce a plan.");

        Optional<TestPlan> plan = agent.plan(ai, E2eTestFramework.PLAYWRIGHT,
                new TestPlannerAgent.PlannerInput("t", "b", "d"));

        assertThat(plan).isEmpty();
    }

    @Test
    void returnsEmptyWhenAiClientIsNull() {
        Optional<TestPlan> plan = agent.plan(null, E2eTestFramework.PLAYWRIGHT,
                new TestPlannerAgent.PlannerInput("t", "b", "d"));

        assertThat(plan).isEmpty();
    }
}

