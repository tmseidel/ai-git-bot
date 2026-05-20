package org.remus.giteabot.prworkflow.e2e.agents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolContext;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRunnerAgentTest {

    @TempDir Path tmp;

    private PrWorkflowToolExecutor toolExecutor;
    private TestRunnerAgent agent;
    private PrWorkflowToolContext ctx;

    @BeforeEach
    void setUp() {
        toolExecutor = mock(PrWorkflowToolExecutor.class);
        ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());
        agent = new TestRunnerAgent(catalog, toolExecutor);
        ctx = new PrWorkflowToolContext(
                new PrTestSuite(), tmp, E2eTestFramework.PLAYWRIGHT,
                "https://preview.example.com",
                "acme", "shop", 99L, null);
    }

    @Test
    void exposesOnlyTheFourRunnerTools() {
        // A dummy plan triggers a single text turn; we only care about the
        // tool descriptor surface advertised to the model in this test.
        StubAiClient ai = new StubAiClient(true).withTextTurn("nothing to do");
        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("a", "A", List.of("s"), List.of("x"), "tests/a.spec.ts")), 0);

        agent.execute(ai, ctx, plan, 0);

        assertThat(ai.invocations()).hasSize(1);
        assertThat(ai.invocations().get(0).tools())
                .extracting(td -> td.name())
                .containsExactlyInAnyOrder(
                        "preview-url", "preview-status", "pr-test-run", "attach-artifact");
    }

    @Test
    void countsToolInvocations() {
        when(toolExecutor.execute(eq("preview-status"), anyMap(), any()))
                .thenReturn("{\"status\":200,\"ok\":true}");
        when(toolExecutor.execute(eq("pr-test-run"), anyMap(), any()))
                .thenReturn("exit=0 duration=5000ms updatedCases=1");
        when(toolExecutor.execute(eq("attach-artifact"), anyMap(), any()))
                .thenReturn("OK: attached 'screenshot.png' (1234 bytes)");

        StubAiClient ai = new StubAiClient(true)
                .withToolCalls(StubAiClient.ToolCallSpec.of("preview-status", Map.of()))
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-run",
                        Map.of("framework", "playwright", "args", List.of())))
                .withToolCalls(StubAiClient.ToolCallSpec.of("attach-artifact",
                        Map.of("path", "artifacts/screenshot.png")))
                .withTextTurn("Suite green; attached one screenshot.");

        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("a", "A", List.of("s"), List.of("x"), "tests/a.spec.ts")), 1);

        TestRunnerAgent.Result result = agent.execute(ai, ctx, plan, 1);

        assertThat(result.prTestRunInvocations()).isEqualTo(1);
        assertThat(result.attachedArtifacts()).isEqualTo(1);
        assertThat(result.budgetExhausted()).isFalse();
        assertThat(result.finalAssistantText()).contains("Suite green");
    }

    @Test
    void handlesNullAiClientGracefully() {
        TestRunnerAgent.Result result = agent.execute(null, ctx,
                new TestPlan("playwright", List.of(), 0), 0);

        assertThat(result.budgetExhausted()).isTrue();
        assertThat(result.prTestRunInvocations()).isZero();
    }
}
