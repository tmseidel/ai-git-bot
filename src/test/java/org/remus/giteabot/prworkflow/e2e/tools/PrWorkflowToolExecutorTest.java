package org.remus.giteabot.prworkflow.e2e.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.PrTestCase;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseRepository;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseStatus;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.Review;
import org.remus.giteabot.repository.model.ReviewComment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrWorkflowToolExecutorTest {

    @TempDir Path tmpRoot;

    private PrTestCaseRepository caseRepository;
    private WorkspaceProcessRunner processRunner;
    private PreviewHttpProbe httpProbe;
    private PrTestWorkspaceManager workspaceManager;

    private PrWorkflowToolExecutor executor;
    private PrTestSuite suite;
    private Path workspace;
    private final Map<String, PrTestCase> stored = new HashMap<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() throws IOException {
        workspaceManager = PrTestWorkspaceManager.rootedAt(tmpRoot);
        workspace = workspaceManager.allocate(42L, E2eTestFramework.PLAYWRIGHT);

        suite = new PrTestSuite();
        suite.setId(7L);
        suite.setPrNumber(99L);

        caseRepository = mock(PrTestCaseRepository.class);
        when(caseRepository.findBySuiteAndPath(eq(suite), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(1, String.class))));
        when(caseRepository.save(any(PrTestCase.class))).thenAnswer(inv -> {
            PrTestCase pc = inv.getArgument(0);
            if (pc.getId() == null) pc.setId(nextId++);
            stored.put(pc.getPath(), pc);
            return pc;
        });

        processRunner = mock(WorkspaceProcessRunner.class);
        httpProbe = mock(PreviewHttpProbe.class);

        executor = new PrWorkflowToolExecutor(caseRepository, workspaceManager, processRunner, httpProbe);
    }

    private PrWorkflowToolContext ctx(RepositoryApiClient apiClient, String previewUrl) {
        return new PrWorkflowToolContext(
                suite, workspace, E2eTestFramework.PLAYWRIGHT,
                previewUrl, "acme", "shop", 99L, apiClient);
    }

    // ---------------------------------------------------------------- pr-test-write

    @Test
    void prTestWriteCreatesFileAndUpsertsCase() {
        String result = executor.execute("pr-test-write",
                Map.of("path", "tests/login.spec.ts",
                       "content", "import { test } from '@playwright/test';\n",
                       "title", "Login happy path"),
                ctx(null, "https://preview.example.com"));

        assertThat(result).startsWith("OK: wrote ");
        assertThat(workspace.resolve("tests/login.spec.ts")).exists();
        PrTestCase saved = stored.get("tests/login.spec.ts");
        assertThat(saved).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("Login happy path");
        assertThat(saved.getLastStatus()).isEqualTo(PrTestCaseStatus.PENDING);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void prTestWriteUpdatesExistingCaseAndResetsRunMetadata() {
        // First write creates the row.
        executor.execute("pr-test-write",
                Map.of("path", "tests/checkout.spec.ts", "content", "v1"),
                ctx(null, "https://preview.example.com"));
        PrTestCase first = stored.get("tests/checkout.spec.ts");
        first.setLastStatus(PrTestCaseStatus.PASSED);
        first.setLastLog("previous log");

        // Second write must overwrite content and reset the status.
        String result = executor.execute("pr-test-write",
                Map.of("path", "tests/checkout.spec.ts", "content", "v2"),
                ctx(null, "https://preview.example.com"));

        assertThat(result).startsWith("OK: wrote ");
        PrTestCase updated = stored.get("tests/checkout.spec.ts");
        assertThat(updated.getContent()).isEqualTo("v2");
        assertThat(updated.getLastStatus()).isEqualTo(PrTestCaseStatus.PENDING);
        assertThat(updated.getLastLog()).isNull();
    }

    @Test
    void prTestWriteRejectsPathTraversal() {
        String result = executor.execute("pr-test-write",
                Map.of("path", "../escape.spec.ts", "content", "x"),
                ctx(null, "https://preview.example.com"));

        assertThat(result).startsWith("ERROR:")
                .contains("escapes the workspace");
        assertThat(stored).isEmpty();
    }

    @Test
    void prTestWriteRejectsMissingArgs() {
        String result = executor.execute("pr-test-write",
                Map.of("path", "tests/foo.spec.ts"),
                ctx(null, "https://preview.example.com"));

        assertThat(result).startsWith("ERROR:").contains("content");
    }

    // ---------------------------------------------------------------- pr-test-run

    @Test
    void prTestRunInvokesPlaywrightAndUpdatesCases() throws Exception {
        // Seed two cases the JSON reporter output will reference.
        seedCase("tests/login.spec.ts", "Login");
        seedCase("tests/checkout.spec.ts", "Checkout");

        String json = """
                {"suites":[
                  {"file":"tests/login.spec.ts","specs":[
                    {"tests":[{"results":[{"status":"passed","duration":120}]}]}
                  ]},
                  {"file":"tests/checkout.spec.ts","specs":[
                    {"tests":[{"results":[
                       {"status":"failed","duration":4500,"error":{"message":"Boom"}}
                    ]}]}
                  ]}
                ]}
                """;
        when(processRunner.run(eq(workspace), anyList(), anyLong(), anyInt()))
                .thenReturn(new WorkspaceProcessRunner.ProcessResult(1, json, 5_000L, false));

        String result = executor.execute("pr-test-run",
                Map.of("framework", "playwright", "args", List.of()),
                ctx(null, "https://preview.example.com"));

        assertThat(result).contains("exit=1").contains("updatedCases=2");
        assertThat(stored.get("tests/login.spec.ts").getLastStatus()).isEqualTo(PrTestCaseStatus.PASSED);
        assertThat(stored.get("tests/login.spec.ts").getLastDurationMs()).isEqualTo(120L);
        assertThat(stored.get("tests/checkout.spec.ts").getLastStatus()).isEqualTo(PrTestCaseStatus.FAILED);
        assertThat(stored.get("tests/checkout.spec.ts").getLastLog()).contains("Boom");
    }

    @Test
    void prTestRunRejectsFrameworkMismatch() {
        String result = executor.execute("pr-test-run",
                Map.of("framework", "pytest", "args", List.of()),
                ctx(null, "https://preview.example.com"));

        assertThat(result).startsWith("ERROR:").contains("mismatch");
    }

    @Test
    void prTestRunHandlesUnknownFramework() {
        String result = executor.execute("pr-test-run",
                Map.of("framework", "junit", "args", List.of()),
                ctx(null, "https://preview.example.com"));

        assertThat(result).startsWith("ERROR:").contains("Unknown framework");
    }

    // ---------------------------------------------------------------- preview-url / preview-status

    @Test
    void previewUrlReturnsContextUrl() {
        String result = executor.execute("preview-url",
                Map.of(),
                ctx(null, "https://preview.example.com"));

        assertThat(result).isEqualTo("https://preview.example.com");
    }

    @Test
    void previewUrlReportsErrorWhenMissing() {
        String result = executor.execute("preview-url",
                Map.of(),
                ctx(null, null));

        assertThat(result).startsWith("ERROR:");
    }

    @Test
    void previewStatusProbesAndReportsJson() {
        when(httpProbe.probe("https://preview.example.com/health"))
                .thenReturn(new PreviewHttpProbe.ProbeResult(200, 42L, "ok"));

        String result = executor.execute("preview-status",
                Map.of("path", "/health"),
                ctx(null, "https://preview.example.com"));

        assertThat(result).contains("\"url\":\"https://preview.example.com/health\"")
                .contains("\"status\":200")
                .contains("\"expected\":200")
                .contains("\"ok\":true");
    }

    @Test
    void previewStatusFlagsMismatchAsNotOk() {
        when(httpProbe.probe(anyString()))
                .thenReturn(new PreviewHttpProbe.ProbeResult(503, 12L, "down"));

        String result = executor.execute("preview-status",
                Map.of("expectedStatus", 200),
                ctx(null, "https://preview.example.com"));

        assertThat(result).contains("\"status\":503").contains("\"ok\":false");
    }

    // ---------------------------------------------------------------- attach-artifact

    @Test
    void attachArtifactDelegatesToRepositoryApiClient() throws IOException {
        Path screenshot = workspace.resolve("artifacts/login.png");
        Files.createDirectories(screenshot.getParent());
        Files.write(screenshot, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});

        RecordingApiClient api = new RecordingApiClient();

        String result = executor.execute("attach-artifact",
                Map.of("path", "artifacts/login.png", "title", "Login screenshot"),
                ctx(api, "https://preview.example.com"));

        assertThat(result).startsWith("OK: attached 'Login screenshot'");
        assertThat(api.calls).hasSize(1);
        assertThat(api.calls.get(0).fileName).isEqualTo("Login screenshot");
        assertThat(api.calls.get(0).payload).hasSize(8);
    }

    @Test
    void attachArtifactRejectsTraversal() {
        RecordingApiClient api = new RecordingApiClient();

        String result = executor.execute("attach-artifact",
                Map.of("path", "../outside.png"),
                ctx(api, "https://preview.example.com"));

        assertThat(result).startsWith("ERROR:");
        assertThat(api.calls).isEmpty();
    }

    @Test
    void attachArtifactRejectsMissingFile() {
        RecordingApiClient api = new RecordingApiClient();

        String result = executor.execute("attach-artifact",
                Map.of("path", "artifacts/missing.png"),
                ctx(api, "https://preview.example.com"));

        assertThat(result).startsWith("ERROR:").contains("does not exist");
        assertThat(api.calls).isEmpty();
    }

    // ---------------------------------------------------------------- meta

    @Test
    void unknownToolReturnsError() {
        String result = executor.execute("does-not-exist",
                Map.of(),
                ctx(null, "https://preview.example.com"));

        assertThat(result).startsWith("ERROR: unknown PR-workflow tool");
        verifyNoInteractions(processRunner, httpProbe);
    }

    // ---------------------------------------------------------------- helpers

    private void seedCase(String path, String title) {
        executor.execute("pr-test-write",
                Map.of("path", path, "content", "// " + title + "\n", "title", title),
                ctx(null, "https://preview.example.com"));
    }

    /**
     * Minimal stub capturing {@link RepositoryApiClient#attachPullRequestArtifact}
     * invocations without going through the default Markdown rendering path —
     * this isolates the executor test from {@code ArtifactCommentRenderer},
     * which has its own dedicated test.
     */
    private static final class RecordingApiClient implements RepositoryApiClient {
        record Call(String owner, String repo, Long pr, String fileName, String contentType, byte[] payload) { }
        final List<Call> calls = new ArrayList<>();

        @Override
        public void attachPullRequestArtifact(String owner, String repo, Long pullNumber,
                                              String fileName, String contentType, byte[] payload) {
            calls.add(new Call(owner, repo, pullNumber, fileName, contentType, payload));
        }

        // ---- unused contract members ----
        @Override public RepositoryCredentials getCredentials() {
            return new RepositoryCredentials("http://x", "http://x", "u", "t");
        }
        @Override public String getPullRequestDiff(String o, String r, Long p) { return ""; }
        @Override public void postReviewComment(String o, String r, Long p, String b) { }
        @Override public void postPullRequestComment(String o, String r, Long p, String b) { }
        @Override public void postIssueComment(String o, String r, Long i, String b) { }
        @Override public void addReaction(String o, String r, Long c, String x) { }
        @Override public void postInlineReviewComment(String o, String r, Long p, String f, int l, String b) { }
        @Override public List<Review> getReviews(String o, String r, Long p) { return List.of(); }
        @Override public List<ReviewComment> getReviewComments(String o, String r, Long p, Long rid) { return List.of(); }
        @Override public String getDefaultBranch(String o, String r) { return "main"; }
        @Override public List<Map<String, Object>> getRepositoryTree(String o, String r, String ref) { return List.of(); }
        @Override public String getFileContent(String o, String r, String p, String ref) { return ""; }
        @Override public void createOrUpdateFile(String o, String r, String p, String c, String m, String b, String s) { }
        @Override public Long createPullRequest(String o, String r, String t, String b, String h, String base) { return 0L; }
    }
}
