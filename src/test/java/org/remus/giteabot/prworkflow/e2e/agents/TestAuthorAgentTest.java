package org.remus.giteabot.prworkflow.e2e.agents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.ToolDescriptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAuthorAgentTest {

    @TempDir Path tmp;

    private PrWorkflowToolExecutor toolExecutor;
    private TestAuthorAgent agent;
    private PrWorkflowToolContext ctx;

    @BeforeEach
    void setUp() {
        toolExecutor = mock(PrWorkflowToolExecutor.class);
        // The author agent is built with a real ToolCatalog so the test exercises
        // the actual native-descriptor surface advertised to the model.
        ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());
        agent = new TestAuthorAgent(catalog, toolExecutor);

        ctx = new PrWorkflowToolContext(
                new PrTestSuite(), tmp, E2eTestFramework.PLAYWRIGHT,
                "https://preview.example.com",
                "acme", "shop", 99L, null);
    }

    @Test
    void writesOneFilePerJourney() {
        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("login", "Login", List.of("visit /"), List.of("see logo"),
                        "tests/login.spec.ts"),
                new TestPlan.Journey("cart", "Cart", List.of("add"), List.of("count==1"),
                        "tests/cart.spec.ts")), 1);

        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("OK: wrote 100 bytes to tests/login.spec.ts (PrTestCase id=1)")
                .thenReturn("OK: wrote 100 bytes to tests/cart.spec.ts (PrTestCase id=2)");

        StubAiClient ai = new StubAiClient(true)
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-write",
                        Map.of("path", "tests/login.spec.ts", "content", "// login\n",
                               "title", "Login")))
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-write",
                        Map.of("path", "tests/cart.spec.ts", "content", "// cart\n",
                               "title", "Cart")))
                .withTextTurn("Wrote 2 tests.");

        TestAuthorAgent.Result result = agent.write(ai, ctx, plan);

        assertThat(result.filesWritten()).isEqualTo(2);
        assertThat(result.budgetExhausted()).isFalse();
        assertThat(result.finalAssistantText()).contains("Wrote 2 tests.");
        verify(toolExecutor, times(2)).execute(eq("pr-test-write"), anyMap(), any());

        // The author must advertise *only* pr-test-write to the model.
        assertThat(ai.invocations().get(0).tools())
                .extracting(ToolDescriptor::name)
                .containsExactly("pr-test-write");
    }

    @Test
    void shortCircuitsOnEmptyPlan() {
        StubAiClient ai = new StubAiClient(true);

        TestAuthorAgent.Result result = agent.write(ai, ctx, new TestPlan("playwright", List.of(), 1));

        assertThat(result.filesWritten()).isZero();
        assertThat(result.budgetExhausted()).isFalse();
        assertThat(ai.invocations()).isEmpty();
        verify(toolExecutor, never()).execute(anyString(), anyMap(), any());
    }

    @Test
    void doesNotCountFailedWritesAsSuccess() {
        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("login", "Login", List.of("a"), List.of("b"),
                        "tests/login.spec.ts")), 1);

        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("ERROR: path escapes the workspace");

        StubAiClient ai = new StubAiClient(true)
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-write",
                        Map.of("path", "../escape.spec.ts", "content", "x")))
                .withTextTurn("Done");

        TestAuthorAgent.Result result = agent.write(ai, ctx, plan);

        assertThat(result.filesWritten()).isZero();
    }

    /**
     * Regression guard for the failure mode where Claude "narrates" the
     * tool call as a ```json``` snippet instead of emitting a real
     * {@code tool_use} block. The agent must parse the narrated call and
     * execute it directly (no second LLM roundtrip).
     */
    @Test
    void recoversNarratedJsonToolCallWithoutSecondRoundtrip() {
        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("login", "Login", List.of("visit /"), List.of("see logo"),
                        "tests/login.spec.ts")), 1);

        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("OK: wrote 50 bytes to tests/login.spec.ts (PrTestCase id=1)");

        String narratedJson = "```json\n{\n  \"name\": \"pr-test-write\",\n"
                + "  \"parameters\": { \"path\": \"tests/login.spec.ts\","
                + " \"content\": \"// login\" }\n}\n```";
        StubAiClient ai = new StubAiClient(true).withTextTurn(narratedJson);

        TestAuthorAgent.Result result = agent.write(ai, ctx, plan);

        assertThat(result.filesWritten()).isOne();
        assertThat(result.budgetExhausted()).isFalse();
        // Exactly ONE LLM call — recovery must not trigger a second roundtrip.
        assertThat(ai.invocations()).hasSize(1);
        verify(toolExecutor, times(1)).execute(eq("pr-test-write"), anyMap(), any());
    }

    /**
     * Regression guard for the variant where Claude regresses to its
     * training-time XML tool-call syntax. The parser must recover both
     * the tool name and every {@code <parameter>} body (verbatim, since
     * the content is usually source code with significant whitespace).
     */
    @Test
    void recoversNarratedXmlToolCallsForMultipleJourneys() {
        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("a", "A", List.of("x"), List.of("y"), "tests/a.spec.ts"),
                new TestPlan.Journey("b", "B", List.of("x"), List.of("y"), "tests/b.spec.ts")), 1);

        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("OK: wrote tests/a.spec.ts")
                .thenReturn("OK: wrote tests/b.spec.ts");

        String narratedXml = """
                <function_calls>
                <invoke name="pr-test-write">
                <parameter name="path">tests/a.spec.ts</parameter>
                <parameter name="content">import { test } from '@playwright/test';
                test('a', async ({ page }) => {});
                </parameter>
                </invoke>
                </function_calls>
                <function_calls>
                <invoke name="pr-test-write">
                <parameter name="path">tests/b.spec.ts</parameter>
                <parameter name="content">import { test } from '@playwright/test';
                test('b', async ({ page }) => {});
                </parameter>
                </invoke>
                </function_calls>

                DONE
                """;
        StubAiClient ai = new StubAiClient(true).withTextTurn(narratedXml);

        TestAuthorAgent.Result result = agent.write(ai, ctx, plan);

        assertThat(result.filesWritten()).isEqualTo(2);
        assertThat(ai.invocations()).hasSize(1);
        verify(toolExecutor, times(2)).execute(eq("pr-test-write"), anyMap(), any());
    }

    @Test
    void heuristicRecognisesBothNarratedFormats() {        assertThat(TestAuthorAgent.looksLikeNarratedToolCall(
                "```json\n{\"name\":\"pr-test-write\",\"parameters\":{...}}\n```")).isTrue();
        assertThat(TestAuthorAgent.looksLikeNarratedToolCall(
                "<function_calls><invoke name=\"pr-test-write\">"
                        + "<parameter name=\"path\">x</parameter></invoke></function_calls>")).isTrue();
        assertThat(TestAuthorAgent.looksLikeNarratedToolCall(
                "Sorry, I cannot help with this request.")).isFalse();
        assertThat(TestAuthorAgent.looksLikeNarratedToolCall(null)).isFalse();
        assertThat(TestAuthorAgent.looksLikeNarratedToolCall("")).isFalse();
    }

    /**
     * Regression for the case where the AI client is NOT in native-tool mode
     * (or the operator toggled {@code use_legacy_tool_calling=true}). The
     * model then follows the legacy JSON-envelope contract emitted by the
     * shared {@link org.remus.giteabot.agent.shared.LegacyToolProtocolRenderer}
     * — i.e. responds with
     * <code>{"summary": "...", "runTools": [{"id": "...", "tool": "pr-test-write",
     * "args": ["&lt;path&gt;", "&lt;content&gt;", "&lt;title&gt;"]}]}</code>.
     *
     * <p>Before the legacy-dispatch path was added to {@code E2eAgentRunner},
     * this envelope was treated as a terminal text turn and zero files were
     * written even though the model's intent was crystal clear. This test
     * pins the new behaviour: the envelope is parsed, each {@code runTools}
     * entry is mapped from positional → named args via the tool's schema and
     * dispatched through the executor, and the next round receives the
     * results as a user message so the model can say DONE.</p>
     */
    @Test
    void dispatchesLegacyEnvelopeWhenAiClientIsNotInNativeMode() {
        TestPlan plan = new TestPlan("playwright", List.of(
                new TestPlan.Journey("login", "Login", List.of("visit /"), List.of("see logo"),
                        "tests/login.spec.ts"),
                new TestPlan.Journey("cart", "Cart", List.of("add"), List.of("count==1"),
                        "tests/cart.spec.ts")), 1);

        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("OK: wrote 42 bytes");

        String envelope = """
                ```json
                {
                  "summary": "Writing 2 Playwright test files",
                  "runTools": [
                    {"id": "a1b2c3d4-0001-4000-8000-000000000001", "tool": "pr-test-write",
                     "args": ["tests/login.spec.ts", "import { test } from '@playwright/test';\\n", "Login"]},
                    {"id": "a1b2c3d4-0002-4000-8000-000000000002", "tool": "pr-test-write",
                     "args": ["tests/cart.spec.ts",  "import { test } from '@playwright/test';\\n", "Cart"]}
                  ]
                }
                ```
                """;

        // nativeTools=false → E2eAgentRunner resolves to LEGACY mode.
        StubAiClient ai = new StubAiClient(false)
                .withTextTurn(envelope)
                .withTextTurn("DONE");

        TestAuthorAgent.Result result = agent.write(ai, ctx, plan);

        assertThat(result.filesWritten()).isEqualTo(2);
        assertThat(result.budgetExhausted()).isFalse();

        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(toolExecutor, times(2)).execute(eq("pr-test-write"), captor.capture(), any());

        // Positional args must have been zipped back to named params using the
        // pr-test-write schema (path, content, title — required first).
        Map<String, Object> first = captor.getAllValues().get(0);
        assertThat(first).containsEntry("path",  "tests/login.spec.ts");
        assertThat(first).containsEntry("title", "Login");
        assertThat((String) first.get("content")).contains("@playwright/test");

        Map<String, Object> second = captor.getAllValues().get(1);
        assertThat(second).containsEntry("path", "tests/cart.spec.ts");
        assertThat(second).containsEntry("title", "Cart");
    }
}
