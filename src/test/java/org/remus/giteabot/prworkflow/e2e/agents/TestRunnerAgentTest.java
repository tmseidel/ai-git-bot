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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * Regression guard: when the model narrates its tool calls as plain text
     * (leaked Claude XML syntax) instead of emitting native tool_use blocks,
     * the runner must recover the `pr-test-run` invocations and re-execute
     * them through the real executor — otherwise zero PrTestCase rows would
     * be populated and the whole suite would be reported as ERROR even though
     * the model "claimed" (in text) to have run the suite.
     */
    @Test
    void recoversNarratedPrTestRunCallsFromAssistantText() {
        when(toolExecutor.execute(eq("pr-test-run"), anyMap(), any()))
                .thenReturn("exit=0 duration=1234ms updatedCases=2");

        String narrated = """
                I'll run the suite now.

                <function_calls>
                <invoke name="pr-test-run">
                <parameter name="framework">playwright</parameter>
                <parameter name="args">--project=chromium</parameter>
                </invoke>
                </function_calls>

                <function_response>
                {"summary":{"total":2,"passed":2,"failed":0}}
                </function_response>

                All green.
                """;

        StubAiClient ai = new StubAiClient(true).withTextTurn(narrated);

        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("a", "A", List.of("s"), List.of("x"), "tests/a.spec.ts")), 0);

        TestRunnerAgent.Result result = agent.execute(ai, ctx, plan, 0);

        // The recovered call counts as a real pr-test-run invocation so the
        // PlaywrightTestSuiteRunner aggregation will pick up the rows the
        // executor wrote into the database.
        assertThat(result.prTestRunInvocations()).isEqualTo(1);
        verify(toolExecutor, atLeastOnce()).execute(eq("pr-test-run"), anyMap(), any());
        // attach-artifact must NOT be re-executed for fabricated paths.
        verify(toolExecutor, never()).execute(eq("attach-artifact"), anyMap(), any());
    }

    /**
     * Regression guard for the third observed failure mode: Claude wraps each
     * call in {@code <tool_call>{"name":..., "arguments":{...}}</tool_call>}
     * and then hallucinates {@code <tool_response>...</tool_response>} blocks
     * to keep its narrative going. We must (a) recover both pr-test-run calls
     * including their array {@code args}, (b) ignore the fake responses, and
     * (c) preserve list-shaped arguments so the executor receives a real
     * {@code List<String>} (not a stringified JSON array).
     */
    @Test
    void recoversToolCallTaggedPrTestRunCallsWithArrayArgs() {
        when(toolExecutor.execute(eq("pr-test-run"), anyMap(), any()))
                .thenReturn("exit=0 duration=1234ms updatedCases=3");

        String narrated = """
                I'll start by checking the preview.

                <tool_call>
                {"name": "preview-url", "arguments": {}}
                </tool_call>
                <tool_response>
                https://preview-deploy.example.com/app/pr-4821
                </tool_response>

                <tool_call>
                {"name": "pr-test-run", "arguments": {"framework": "playwright", "args": []}}
                </tool_call>
                <tool_response>
                {"summary":{"total":10,"passed":7,"failed":3}}
                </tool_response>

                Retrying failed specs.

                <tool_call>
                {"name": "pr-test-run", "arguments": {"framework": "playwright", "args": ["--grep", "dark-mode"]}}
                </tool_call>
                <tool_response>
                {"summary":{"total":3,"passed":2,"failed":1}}
                </tool_response>

                Done.
                """;

        StubAiClient ai = new StubAiClient(true).withTextTurn(narrated);

        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("a", "A", List.of("s"), List.of("x"), "tests/a.spec.ts")), 0);

        TestRunnerAgent.Result result = agent.execute(ai, ctx, plan, 1);

        assertThat(result.prTestRunInvocations()).isEqualTo(2);
        // The second call carries an array of strings — assert it is passed
        // through as a real List (not as the JSON-encoded string literal
        // "[\"--grep\",\"dark-mode\"]").
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor, org.mockito.Mockito.times(2))
                .execute(eq("pr-test-run"), captor.capture(), any());
        Map<String, Object> withGrep = captor.getAllValues().stream()
                .filter(m -> m.get("args") instanceof List<?> l && !l.isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(withGrep.get("args")).isInstanceOf(List.class);
        assertThat((List<?>) withGrep.get("args")).asList().containsExactly("--grep", "dark-mode");
        // preview-url and attach-artifact must NOT be auto-executed for
        // fabricated responses.
        verify(toolExecutor, never()).execute(eq("preview-url"), anyMap(), any());
        verify(toolExecutor, never()).execute(eq("attach-artifact"), anyMap(), any());
    }
}
