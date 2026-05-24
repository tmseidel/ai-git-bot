package org.remus.giteabot.prworkflow.e2e.promotion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.agent.validation.WorkspaceResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.e2e.PrTestCase;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link SuitePromotionService}. The {@code git} CLI
 * and the remote provider are stubbed via {@link WorkspaceService} and
 * {@link RepositoryApiClient} mocks; the file-writing path is exercised on
 * a real temp workspace so the conflict-suffix logic is covered for real.
 */
class SuitePromotionServiceTest {

    private WorkspaceService workspaceService;
    private RepositoryApiClient repoClient;
    private SuitePromotionService service;

    private Path workspace;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        workspace = Files.createDirectories(tmp.resolve("ws"));
        workspaceService = mock(WorkspaceService.class);
        GiteaClientFactory giteaClientFactory = mock(GiteaClientFactory.class);
        PrWorkflowRunRepository runRepository = mock(PrWorkflowRunRepository.class);
        repoClient = mock(RepositoryApiClient.class);

        when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
        when(repoClient.getCloneUrl()).thenReturn("http://git.local");
        when(repoClient.getToken()).thenReturn("tok");
        when(repoClient.getDefaultBranch(anyString(), anyString())).thenReturn("main");
        when(workspaceService.prepareWorkspace(anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(WorkspaceResult.success(workspace));
        lenient().when(workspaceService.commitAndPush(any(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean()))
                .thenReturn(true);
        lenient().when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new SuitePromotionService(workspaceService, giteaClientFactory, runRepository);
    }

    @Test
    void ephemeral_isNoOp() {
        PrTestSuite suite = suite(SuiteLifecycleMode.EPHEMERAL, 7L,
                caseAt("login.spec.ts", "// hi"));
        PrWorkflowRun run = run(99L);

        SuitePromotionService.Outcome out = service.promote(bot(), run, suite,
                "acme", "web", "feature/login");

        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.SKIPPED);
        verify(workspaceService, never()).prepareWorkspace(any(), any(), any(), any(), any());
    }

    @Test
    void offerAsPr_writesCases_pushes_andCreatesFollowUpPr() {
        PrTestSuite suite = suite(SuiteLifecycleMode.OFFER_AS_PR, 7L,
                caseAt("login.spec.ts", "// generated login test"));
        PrWorkflowRun run = run(99L);
        String expectedBranch = "ai-tests/pr-7-r99";
        when(repoClient.createPullRequest(eq("acme"), eq("web"), anyString(), anyString(),
                eq(expectedBranch), eq("feature/login"))).thenReturn(4242L);

        SuitePromotionService.Outcome out = service.promote(bot(), run, suite,
                "acme", "web", "feature/login");

        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.PROMOTED);
        assertThat(out.followUpPrNumber()).isEqualTo(4242L);
        assertThat(out.branch()).isEqualTo(expectedBranch);
        assertThat(out.writtenPaths()).containsExactly("tests/e2e/pr-7/login.spec.ts");
        assertThat(run.getFollowUpPrNumber()).isEqualTo(4242L);
        // File actually written.
        assertThat(workspace.resolve("tests/e2e/pr-7/login.spec.ts")).exists();
        verify(workspaceService).commitAndPush(eq(workspace), eq(expectedBranch),
                anyString(), anyString(), anyString(), eq(true));
    }

    @Test
    void promoteOnMerge_targetsDefaultBranch_andWritesUnderTestsE2e() {
        PrTestSuite suite = suite(SuiteLifecycleMode.PROMOTE_ON_MERGE, 11L,
                caseAt("checkout.spec.ts", "// promoted"));
        PrWorkflowRun run = run(7L);
        when(repoClient.createPullRequest(eq("acme"), eq("web"), anyString(), anyString(),
                eq("ai-tests/promoted-pr-11-r7"), eq("main"))).thenReturn(5050L);

        SuitePromotionService.Outcome out = service.promote(bot(), run, suite,
                "acme", "web", "ignored");

        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.PROMOTED);
        assertThat(out.writtenPaths()).containsExactly("tests/e2e/checkout.spec.ts");
        assertThat(workspace.resolve("tests/e2e/checkout.spec.ts")).exists();
        assertThat(run.getFollowUpPrNumber()).isEqualTo(5050L);
    }

    @Test
    void rerun_usesUniqueBranchPerRun() {
        // First attempt with run id 100.
        PrTestSuite suite1 = suite(SuiteLifecycleMode.PROMOTE_ON_MERGE, 11L,
                caseAt("a.spec.ts", "// v1"));
        PrWorkflowRun run1 = run(100L);
        when(repoClient.createPullRequest(eq("acme"), eq("web"), anyString(), anyString(),
                eq("ai-tests/promoted-pr-11-r100"), eq("main"))).thenReturn(900L);

        SuitePromotionService.Outcome out1 = service.promote(bot(), run1, suite1,
                "acme", "web", "ignored");
        assertThat(out1.branch()).isEqualTo("ai-tests/promoted-pr-11-r100");

        // Re-run for the same parent PR uses a different run id and therefore
        // a different branch, so the second createPullRequest does not collide.
        PrTestSuite suite2 = suite(SuiteLifecycleMode.PROMOTE_ON_MERGE, 11L,
                caseAt("a.spec.ts", "// v2"));
        PrWorkflowRun run2 = run(101L);
        when(repoClient.createPullRequest(eq("acme"), eq("web"), anyString(), anyString(),
                eq("ai-tests/promoted-pr-11-r101"), eq("main"))).thenReturn(901L);

        SuitePromotionService.Outcome out2 = service.promote(bot(), run2, suite2,
                "acme", "web", "ignored");
        assertThat(out2.branch()).isEqualTo("ai-tests/promoted-pr-11-r101");
        assertThat(out2.branch()).isNotEqualTo(out1.branch());
    }

    @Test
    void commitToPr_pushesFeatureBranchAndOpensNoPr() {
        PrTestSuite suite = suite(SuiteLifecycleMode.COMMIT_TO_PR, 3L,
                caseAt("smoke.spec.ts", "// inline"));
        PrWorkflowRun run = run(8L);

        SuitePromotionService.Outcome out = service.promote(bot(), run, suite,
                "acme", "web", "feature/x");

        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.COMMITTED);
        assertThat(out.branch()).isEqualTo("feature/x");
        assertThat(workspace.resolve("tests/e2e/pr-3/smoke.spec.ts")).exists();
        verify(workspaceService).commitAndPush(eq(workspace), eq("feature/x"),
                anyString(), anyString(), anyString(), eq(false));
        verify(repoClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
        // Idempotency: COMMIT_TO_PR records parent prNumber so a re-run no-ops.
        assertThat(run.getFollowUpPrNumber()).isEqualTo(3L);
    }

    @Test
    void idempotent_whenFollowUpAlreadySet() {
        PrTestSuite suite = suite(SuiteLifecycleMode.OFFER_AS_PR, 7L,
                caseAt("login.spec.ts", "// hi"));
        PrWorkflowRun run = run(99L);
        run.setFollowUpPrNumber(123L);

        SuitePromotionService.Outcome out = service.promote(bot(), run, suite,
                "acme", "web", "feature/login");

        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.ALREADY_PROMOTED);
        assertThat(out.followUpPrNumber()).isEqualTo(123L);
        verify(workspaceService, never()).prepareWorkspace(any(), any(), any(), any(), any());
        verify(repoClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
    }

    @Test
    void conflict_appendsNumericSuffix() throws IOException {
        // Pre-create the destination file to trigger the suffix path.
        Path target = Files.createDirectories(workspace.resolve("tests/e2e/pr-7"));
        Files.writeString(target.resolve("login.spec.ts"), "// pre-existing");

        PrTestSuite suite = suite(SuiteLifecycleMode.OFFER_AS_PR, 7L,
                caseAt("login.spec.ts", "// generated"));
        PrWorkflowRun run = run(99L);
        when(repoClient.createPullRequest(any(), any(), any(), any(),
                anyString(), anyString())).thenReturn(1L);

        SuitePromotionService.Outcome out = service.promote(bot(), run, suite,
                "acme", "web", "feature/login");

        assertThat(out.writtenPaths()).containsExactly("tests/e2e/pr-7/login_2.spec.ts");
        assertThat(workspace.resolve("tests/e2e/pr-7/login_2.spec.ts")).exists();
    }

    @Test
    void conflictWithinSameRun_keepsIncrementing() {
        // Two cases collide on the same target path; the second one is suffixed.
        PrTestSuite suite = suite(SuiteLifecycleMode.OFFER_AS_PR, 7L,
                caseAt("login.spec.ts", "// first"),
                caseAt("login.spec.ts", "// second"));
        PrWorkflowRun run = run(99L);
        when(repoClient.createPullRequest(any(), any(), any(), any(),
                anyString(), anyString())).thenReturn(1L);

        SuitePromotionService.Outcome out = service.promote(bot(), run, suite,
                "acme", "web", "feature/login");

        assertThat(out.writtenPaths())
                .containsExactly("tests/e2e/pr-7/login.spec.ts",
                        "tests/e2e/pr-7/login_2.spec.ts");
    }

    @Test
    void workspaceFailure_surfacesAsOutcomeFailure() {
        when(workspaceService.prepareWorkspace(any(), any(), any(), any(), any()))
                .thenReturn(WorkspaceResult.failure("network down"));

        PrTestSuite suite = suite(SuiteLifecycleMode.OFFER_AS_PR, 7L,
                caseAt("login.spec.ts", "// hi"));
        SuitePromotionService.Outcome out = service.promote(bot(), run(1L), suite,
                "acme", "web", "feature/x");

        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.FAILED);
        assertThat(out.message()).contains("network down");
        verify(repoClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
    }

    @Test
    void pushFailure_surfacesAsOutcomeFailure_andDoesNotOpenPr() {
        when(workspaceService.commitAndPush(any(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean())).thenReturn(false);

        PrTestSuite suite = suite(SuiteLifecycleMode.OFFER_AS_PR, 7L,
                caseAt("login.spec.ts", "// hi"));
        SuitePromotionService.Outcome out = service.promote(bot(), run(1L), suite,
                "acme", "web", "feature/x");

        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.FAILED);
        verify(repoClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolveConflict_suffixesBeforeFirstDot() throws IOException {
        Path base = Files.createDirectories(workspace.resolve("d"));
        Files.writeString(base.resolve("a.spec.ts"), "x");
        Set<String> taken = new HashSet<>();
        String chosen = SuitePromotionService.resolveConflict(base, "a.spec.ts", taken);
        assertThat(chosen).isEqualTo("a_2.spec.ts");
        taken.add(chosen);
        // Second resolve also picks the next free slot, honouring the set.
        Files.writeString(base.resolve("a_2.spec.ts"), "x");
        String next = SuitePromotionService.resolveConflict(base, "a.spec.ts", taken);
        assertThat(next).isEqualTo("a_3.spec.ts");
    }

    @Test
    void emptySuite_isSkipped() {
        PrTestSuite suite = suite(SuiteLifecycleMode.OFFER_AS_PR, 7L /* no cases */);
        SuitePromotionService.Outcome out = service.promote(bot(), run(1L), suite,
                "acme", "web", "feature/x");
        assertThat(out.kind()).isEqualTo(SuitePromotionService.Outcome.Kind.SKIPPED);
    }

    // ---- helpers ---------------------------------------------------------

    private static Bot bot() {
        Bot b = new Bot();
        b.setId(1L);
        b.setName("test");
        b.setGitIntegration(new GitIntegration());
        return b;
    }

    private static PrWorkflowRun run(long id) {
        PrWorkflowRun r = new PrWorkflowRun();
        r.setId(id);
        return r;
    }

    private static PrTestSuite suite(SuiteLifecycleMode mode, long prNumber,
                                     PrTestCase... cases) {
        PrTestSuite s = new PrTestSuite();
        s.setId(10L);
        s.setRunId(99L);
        s.setPrNumber(prNumber);
        s.setLifecycleMode(mode);
        for (PrTestCase c : cases) {
            s.addCase(c);
        }
        return s;
    }

    private static PrTestCase caseAt(String path, String content) {
        PrTestCase c = new PrTestCase();
        c.setPath(path);
        c.setContent(content);
        return c;
    }
}
