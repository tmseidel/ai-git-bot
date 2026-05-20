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

import java.io.IOException;
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
}
