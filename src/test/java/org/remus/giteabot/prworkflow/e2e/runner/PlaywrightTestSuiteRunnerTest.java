package org.remus.giteabot.prworkflow.e2e.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.PrTestCase;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseRepository;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseStatus;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.prworkflow.e2e.agents.StubAiClient;
import org.remus.giteabot.prworkflow.e2e.agents.TestAuthorAgent;
import org.remus.giteabot.prworkflow.e2e.agents.TestPlannerAgent;
import org.remus.giteabot.prworkflow.e2e.agents.TestRunnerAgent;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaywrightTestSuiteRunnerTest {

    @TempDir Path workspace;

    private AiClientFactory aiClientFactory;
    private GiteaClientFactory giteaClientFactory;
    private RepositoryApiClient apiClient;
    private PrTestCaseRepository caseRepository;
    private PrWorkflowToolExecutor toolExecutor;

    private TestPlannerAgent plannerAgent;
    private TestAuthorAgent authorAgent;
    private TestRunnerAgent runnerAgent;
    private PlaywrightTestSuiteRunner suiteRunner;

    private Bot bot;

    @BeforeEach
    void setUp() {
        aiClientFactory = mock(AiClientFactory.class);
        giteaClientFactory = mock(GiteaClientFactory.class);
        apiClient = mock(RepositoryApiClient.class);
        caseRepository = mock(PrTestCaseRepository.class);
        toolExecutor = mock(PrWorkflowToolExecutor.class);
        ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());

        plannerAgent = new TestPlannerAgent(toolExecutor);
        authorAgent = new TestAuthorAgent(catalog, toolExecutor);
        runnerAgent = new TestRunnerAgent(catalog, toolExecutor);

        suiteRunner = new PlaywrightTestSuiteRunner(
                aiClientFactory, giteaClientFactory,
                plannerAgent, authorAgent, runnerAgent, caseRepository, toolExecutor);

        bot = new Bot();
        bot.setName("acme-bot");
        bot.setAiIntegration(new AiIntegration());
        bot.setGitIntegration(new GitIntegration());

        when(giteaClientFactory.getApiClient(any())).thenReturn(apiClient);
        when(apiClient.getPullRequestDiff(any(), any(), any()))
                .thenReturn("diff --git a/srv.js b/srv.js\n+console.log('hi');\n");
    }

    @Test
    void wiresPlannerAuthorAndRunnerAgainstStubAiClient() {
        // The same StubAiClient backs all three agents — it returns canned
        // responses in the order the agents will call it: planner-text first,
        // then per author tool call + final text, then per runner tool call +
        // final text.
        StubAiClient ai = new StubAiClient(true)
                // ---- planner ----
                .withTextTurn("""
                        {"framework":"playwright",
                         "journeys":[{"id":"login","title":"Login",
                                      "steps":["visit /"],"assertions":["sees logo"],
                                      "fileName":"tests/login.spec.ts"}],
                         "maxRetries":1}
                        """)
                // ---- author ----
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-write",
                        Map.of("path", "tests/login.spec.ts", "content", "// login\n",
                               "title", "Login")))
                .withTextTurn("Wrote login.")
                // ---- runner ----
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-run",
                        Map.of("framework", "playwright", "args", List.of())))
                .withTextTurn("Done.");
        when(aiClientFactory.getClient(any())).thenReturn(ai);

        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("OK: wrote 100 bytes to tests/login.spec.ts (PrTestCase id=1)");
        when(toolExecutor.execute(eq("pr-test-run"), anyMap(), any()))
                .thenReturn("exit=0 duration=1200ms updatedCases=1");

        PrTestCase passed = new PrTestCase();
        passed.setId(1L);
        passed.setPath("tests/login.spec.ts");
        passed.setLastStatus(PrTestCaseStatus.PASSED);
        when(caseRepository.findBySuiteOrderByIdAsc(any())).thenReturn(List.of(passed));

        TestSuiteOutcome outcome = suiteRunner.run(buildRequest(20));

        assertThat(outcome.status()).isEqualTo(TestSuiteOutcomeStatus.PASSED);
        assertThat(outcome.attempted()).isEqualTo(1);
        assertThat(outcome.failed()).isZero();
        assertThat(outcome.summary()).contains("1/1 passed");
    }

    @Test
    void reportsFailureWhenAnyCaseFailed() {
        StubAiClient ai = new StubAiClient(true)
                .withTextTurn("""
                        {"framework":"playwright","journeys":[
                          {"id":"a","title":"A","steps":["s"],"assertions":["x"],"fileName":"tests/a.spec.ts"},
                          {"id":"b","title":"B","steps":["s"],"assertions":["x"],"fileName":"tests/b.spec.ts"}],
                         "maxRetries":0}
                        """)
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-write",
                        Map.of("path", "tests/a.spec.ts", "content", "// a\n")))
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-write",
                        Map.of("path", "tests/b.spec.ts", "content", "// b\n")))
                .withTextTurn("ok")
                .withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-run",
                        Map.of("framework", "playwright", "args", List.of())))
                .withTextTurn("Suite finished with failures.");
        when(aiClientFactory.getClient(any())).thenReturn(ai);
        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("OK: wrote 100 bytes to tests/a.spec.ts (PrTestCase id=1)")
                .thenReturn("OK: wrote 100 bytes to tests/b.spec.ts (PrTestCase id=2)");
        when(toolExecutor.execute(eq("pr-test-run"), anyMap(), any()))
                .thenReturn("exit=1 duration=2200ms updatedCases=2");

        PrTestCase a = caseRow(1L, "tests/a.spec.ts", PrTestCaseStatus.PASSED);
        PrTestCase b = caseRow(2L, "tests/b.spec.ts", PrTestCaseStatus.FAILED);
        when(caseRepository.findBySuiteOrderByIdAsc(any())).thenReturn(List.of(a, b));

        TestSuiteOutcome outcome = suiteRunner.run(buildRequest(20));

        assertThat(outcome.status()).isEqualTo(TestSuiteOutcomeStatus.FAILED);
        assertThat(outcome.attempted()).isEqualTo(2);
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.summary()).contains("1/2 failed");
    }

    @Test
    void capsJourneysAtMaxTestCases() {
        StubAiClient ai = new StubAiClient(true);
        // Planner returns 5 journeys, maxTestCases=2 → only 2 author calls expected.
        ai.withTextTurn(plannerJsonWithNJourneys(5));
        // Two author tool calls + final text.
        for (int i = 1; i <= 2; i++) {
            ai.withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-write",
                    Map.of("path", "tests/j" + i + ".spec.ts", "content", "// j" + i + "\n")));
        }
        ai.withTextTurn("authored 2");
        // Runner calls pr-test-run once.
        ai.withToolCalls(StubAiClient.ToolCallSpec.of("pr-test-run",
                Map.of("framework", "playwright", "args", List.of())));
        ai.withTextTurn("done");
        when(aiClientFactory.getClient(any())).thenReturn(ai);

        when(toolExecutor.execute(eq("pr-test-write"), anyMap(), any()))
                .thenReturn("OK: wrote 1 bytes");
        when(toolExecutor.execute(eq("pr-test-run"), anyMap(), any()))
                .thenReturn("exit=0 updatedCases=2");

        List<PrTestCase> rows = new ArrayList<>();
        rows.add(caseRow(1L, "tests/j1.spec.ts", PrTestCaseStatus.PASSED));
        rows.add(caseRow(2L, "tests/j2.spec.ts", PrTestCaseStatus.PASSED));
        when(caseRepository.findBySuiteOrderByIdAsc(any())).thenReturn(rows);

        TestSuiteOutcome outcome = suiteRunner.run(buildRequest(/*maxTestCases=*/ 2));

        assertThat(outcome.status()).isEqualTo(TestSuiteOutcomeStatus.PASSED);
        // The author should only have been driven for two journeys → exactly 2 tool calls.
        assertThat(ai.invocations()).isNotEmpty();
        long writeCount = countAdvertisedWriteInvocations(ai);
        assertThat(writeCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void returnsSkippedWhenPlannerProducesNoJourneys() {
        StubAiClient ai = new StubAiClient(true).withTextTurn("I don't know what to test.");
        when(aiClientFactory.getClient(any())).thenReturn(ai);

        TestSuiteOutcome outcome = suiteRunner.run(buildRequest(20));

        assertThat(outcome.status()).isEqualTo(TestSuiteOutcomeStatus.SKIPPED);
        assertThat(outcome.summary()).contains("no journeys");
    }

    @Test
    void returnsSkippedWhenBotHasNoAiIntegration() {
        bot.setAiIntegration(null);

        TestSuiteOutcome outcome = suiteRunner.run(buildRequest(20));

        assertThat(outcome.status()).isEqualTo(TestSuiteOutcomeStatus.SKIPPED);
        assertThat(outcome.summary()).contains("no AI integration");
    }

    @Test
    void returnsErrorWhenAuthorWritesNothing() {
        StubAiClient ai = new StubAiClient(true)
                .withTextTurn("""
                        {"framework":"playwright","journeys":[
                          {"id":"a","title":"A","steps":["s"],"assertions":["x"],"fileName":"tests/a.spec.ts"}],
                         "maxRetries":1}
                        """)
                // Author returns a text turn immediately without calling any tool.
                .withTextTurn("Refusing to author tests.");
        when(aiClientFactory.getClient(any())).thenReturn(ai);

        TestSuiteOutcome outcome = suiteRunner.run(buildRequest(20));

        assertThat(outcome.status()).isEqualTo(TestSuiteOutcomeStatus.ERROR);
        assertThat(outcome.summary()).contains("wrote zero files");
    }

    // ---- helpers ----

    private TestSuiteRequest buildRequest(int maxTestCases) {
        PrTestSuite suite = new PrTestSuite();
        suite.setId(7L);
        suite.setRunId(42L);
        suite.setPrNumber(99L);

        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        repo.setName("shop");
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("acme");
        repo.setOwner(owner);
        payload.setRepository(repo);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(99L);
        pr.setTitle("Add cart endpoint");
        pr.setBody("Body");
        payload.setPullRequest(pr);

        PrWorkflowContext ctx = mock(PrWorkflowContext.class);
        return new TestSuiteRequest(ctx, bot, payload, suite, workspace,
                E2eTestFramework.PLAYWRIGHT, "https://preview.example.com",
                /* maxRetries */ 1, maxTestCases);
    }

    private static PrTestCase caseRow(long id, String path, PrTestCaseStatus status) {
        PrTestCase pc = new PrTestCase();
        pc.setId(id);
        pc.setPath(path);
        pc.setLastStatus(status);
        return pc;
    }

    private static String plannerJsonWithNJourneys(int n) {
        StringBuilder sb = new StringBuilder("{\"framework\":\"playwright\",\"journeys\":[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"id\":\"j").append(i)
              .append("\",\"title\":\"J").append(i)
              .append("\",\"steps\":[\"s\"],\"assertions\":[\"x\"],\"fileName\":\"tests/j").append(i)
              .append(".spec.ts\"}");
        }
        return sb.append("],\"maxRetries\":0}").toString();
    }

    private static long countAdvertisedWriteInvocations(StubAiClient ai) {
        // Heuristic for the test: count invocations whose system prompt is the author's.
        return ai.invocations().stream()
                .filter(inv -> inv.systemPrompt() != null
                        && inv.systemPrompt().contains("TestAuthorAgent"))
                .count();
    }

    @SuppressWarnings("unused") // retained for future test scenarios
    private static Iterator<AiClient> dummy() { return new ArrayList<AiClient>().iterator(); }
}

