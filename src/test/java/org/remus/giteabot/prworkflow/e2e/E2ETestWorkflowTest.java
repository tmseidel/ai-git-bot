package org.remus.giteabot.prworkflow.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.WorkflowResultStatus;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.WorkflowConfiguration;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.deployment.DeploymentOrchestrator;
import org.remus.giteabot.prworkflow.deployment.DeploymentResult;
import org.remus.giteabot.prworkflow.e2e.runner.NoopTestSuiteRunner;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteOutcome;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteRequest;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteRunner;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteRunnerRegistry;
import org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link E2ETestWorkflow}. Uses Mockito stubs for the
 * collaborators and a real {@link PrTestWorkspaceManager} rooted at a temp
 * directory; the {@link PrTestSuiteRepository} is faked with a tiny
 * in-memory implementation so the workflow's persistence pathway is
 * exercised without bringing up Spring.
 */
@ExtendWith(MockitoExtension.class)
class E2ETestWorkflowTest {

    @Mock private DeploymentOrchestrator deploymentOrchestrator;
    @Mock private WorkflowSelectionService selectionService;
    @Mock private GiteaClientFactory giteaClientFactory;
    @Mock private RepositoryApiClient repoClient;
    @Mock private org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService suitePromotionService;
    @Mock private org.remus.giteabot.prworkflow.PrWorkflowRunRepository runRepository;

    private InMemoryPrTestSuiteRepository suiteRepository;
    private PrTestWorkspaceManager workspaceManager;
    private E2ETestWorkflow workflow;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        suiteRepository = new InMemoryPrTestSuiteRepository();
        workspaceManager = PrTestWorkspaceManager.rootedAt(tmp);
        TestSuiteRunnerRegistry runners = new TestSuiteRunnerRegistry(List.of(new NoopTestSuiteRunner()));
        workflow = new E2ETestWorkflow(deploymentOrchestrator, suiteRepository,
                workspaceManager, runners, selectionService, giteaClientFactory,
                suitePromotionService, runRepository);
        lenient().when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);
    }

    @Test
    void identifiesAsTestingCategory() {
        assertThat(workflow.key()).isEqualTo(E2ETestWorkflow.KEY);
        assertThat(workflow.displayName()).isEqualTo("E2E Tests");
        assertThat(workflow.category()).isEqualTo(PrWorkflowCategory.TESTING);
        assertThat(workflow.paramsSchema().fields())
                .extracting("name")
                .contains("framework", "maxRetries", "maxTestCases");
    }

    @Test
    void abortsWhenBotHasNoDeploymentTarget() {
        Bot bot = bot(null);
        WebhookPayload payload = payload("acme", "web", 12L, "abc12345");

        WorkflowResult result = workflow.run(ctx(bot, payload));

        assertThat(result.status()).isEqualTo(WorkflowResultStatus.SKIPPED);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repoClient).postPullRequestComment(eq("acme"), eq("web"), eq(12L), body.capture());
        assertThat(body.getValue())
                .contains("⏭️")
                .contains("no deployment target");
        verify(deploymentOrchestrator, never()).requestDeployment(any());
        assertThat(suiteRepository.entities).isEmpty();
    }

    @Test
    void postsFailureCommentWhenDeploymentDoesNotBecomeReady() {
        Bot bot = bot(new DeploymentTarget());
        WebhookPayload payload = payload("acme", "web", 13L, "head13");
        when(deploymentOrchestrator.requestDeployment(any()))
                .thenReturn(DeploymentResult.failed("preview did not respond", "{}"));
        when(selectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of());

        WorkflowResult result = workflow.run(ctx(bot, payload));

        assertThat(result.status()).isEqualTo(WorkflowResultStatus.FAILED);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repoClient, org.mockito.Mockito.times(2))
                .postPullRequestComment(eq("acme"), eq("web"), eq(13L), body.capture());
        // First comment is the "starting" opener, second is the failure summary.
        assertThat(body.getAllValues().get(0)).contains("Starting end-to-end test run");
        assertThat(body.getAllValues().get(1))
                .contains("❌")
                .contains("preview did not respond");
    }

    @Test
    void postsSummaryAndSkipsWhenNoopRunnerIsTheOnlyOption() {
        Bot bot = bot(new DeploymentTarget());
        WebhookPayload payload = payload("acme", "web", 14L, "head14");
        when(deploymentOrchestrator.requestDeployment(any()))
                .thenReturn(DeploymentResult.ready("https://preview-14.example.com", "{}"));
        when(selectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of("framework", "playwright"));

        WorkflowResult result = workflow.run(ctx(bot, payload));

        // NoopTestSuiteRunner returns SKIPPED — that propagates as the workflow result.
        assertThat(result.status()).isEqualTo(WorkflowResultStatus.SKIPPED);
        assertThat(suiteRepository.entities).hasSize(1);
        PrTestSuite saved = suiteRepository.entities.values().iterator().next();
        assertThat(saved.getRunId()).isEqualTo(42L);
        assertThat(saved.getPrNumber()).isEqualTo(14L);
        assertThat(saved.getFramework()).isEqualTo(E2eTestFramework.PLAYWRIGHT);
        assertThat(saved.getSourceTreeRef()).isEqualTo("head14");
        assertThat(saved.getLifecycleMode()).isEqualTo(SuiteLifecycleMode.EPHEMERAL);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repoClient, org.mockito.Mockito.times(1))
                .postPullRequestComment(eq("acme"), eq("web"), eq(14L), body.capture());
        verify(repoClient, org.mockito.Mockito.times(1))
                .postReviewComment(eq("acme"), eq("web"), eq(14L), body.capture());
        assertThat(body.getAllValues().get(0)).contains("Starting end-to-end test run");
        assertThat(body.getAllValues().get(1))
                .contains("## E2E Test Run for PR #14")
                .contains("https://preview-14.example.com")
                .contains("⏭️ SKIPPED");
    }

    @Test
    void recordsErrorWhenRunnerThrows() {
        TestSuiteRunner crashing = new TestSuiteRunner() {
            @Override public E2eTestFramework framework() { return E2eTestFramework.PLAYWRIGHT; }
            @Override public TestSuiteOutcome run(TestSuiteRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        E2ETestWorkflow w = new E2ETestWorkflow(deploymentOrchestrator, suiteRepository,
                workspaceManager, new TestSuiteRunnerRegistry(List.of(crashing)),
                selectionService, giteaClientFactory,
                suitePromotionService, runRepository);

        Bot bot = bot(new DeploymentTarget());
        WebhookPayload payload = payload("acme", "web", 15L, "head15");
        when(deploymentOrchestrator.requestDeployment(any()))
                .thenReturn(DeploymentResult.ready("https://x", "{}"));
        when(selectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of());

        WorkflowResult result = w.run(ctx(bot, payload));

        assertThat(result.status()).isEqualTo(WorkflowResultStatus.FAILED);
        assertThat(result.summary())
                .contains("IllegalStateException")
                .contains("boom");
    }

    @Test
    void capsMaxTestCasesAtAbsoluteUpperBound() {
        // The runner receives whatever max we resolved — assert the cap.
        AtomicReference<TestSuiteRequest> captured = new AtomicReference<>();
        TestSuiteRunner capturing = new TestSuiteRunner() {
            @Override public E2eTestFramework framework() { return E2eTestFramework.PLAYWRIGHT; }
            @Override public TestSuiteOutcome run(TestSuiteRequest request) {
                captured.set(request);
                return TestSuiteOutcome.skipped("ok");
            }
        };
        E2ETestWorkflow w = new E2ETestWorkflow(deploymentOrchestrator, suiteRepository,
                workspaceManager, new TestSuiteRunnerRegistry(List.of(capturing)),
                selectionService, giteaClientFactory,
                suitePromotionService, runRepository);

        Bot bot = bot(new DeploymentTarget());
        WebhookPayload payload = payload("acme", "web", 16L, "head16");
        when(deploymentOrchestrator.requestDeployment(any()))
                .thenReturn(DeploymentResult.ready("https://x", "{}"));
        when(selectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of("maxTestCases", 9999, "maxRetries", 99));

        w.run(ctx(bot, payload));

        TestSuiteRequest req = captured.get();
        assertThat(req).isNotNull();
        assertThat(req.maxTestCases()).isEqualTo(E2ETestWorkflow.ABSOLUTE_MAX_TEST_CASES);
        assertThat(req.maxRetries()).isLessThanOrEqualTo(5);
    }

    // ---- helpers --------------------------------------------------------

    @Test
    void suiteLifecycleParam_isPersistedOnSuite() {
        Bot bot = bot(new DeploymentTarget());
        WebhookPayload payload = payload("acme", "web", 21L, "headXX");
        when(deploymentOrchestrator.requestDeployment(any()))
                .thenReturn(DeploymentResult.ready("https://x", "{}"));
        when(selectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of("suiteLifecycle", "offer-as-pr"));

        workflow.run(ctx(bot, payload));

        assertThat(suiteRepository.entities.values()).hasSize(1);
        PrTestSuite saved = suiteRepository.entities.values().iterator().next();
        assertThat(saved.getLifecycleMode()).isEqualTo(SuiteLifecycleMode.OFFER_AS_PR);
    }

    @Test
    void offerAsPr_invokesPromotionService_andPostsPromotionComment() {
        // Custom runner that reports PASSED so the workflow advances into the
        // promotion branch (the default NoopTestSuiteRunner returns SKIPPED).
        TestSuiteRunner passing = new TestSuiteRunner() {
            @Override public E2eTestFramework framework() { return E2eTestFramework.PLAYWRIGHT; }
            @Override public TestSuiteOutcome run(TestSuiteRequest request) {
                return TestSuiteOutcome.passed("all green", 1);
            }
        };
        E2ETestWorkflow w = new E2ETestWorkflow(deploymentOrchestrator, suiteRepository,
                workspaceManager, new TestSuiteRunnerRegistry(List.of(passing)),
                selectionService, giteaClientFactory,
                suitePromotionService, runRepository);

        Bot bot = bot(new DeploymentTarget());
        WebhookPayload payload = payload("acme", "web", 22L, "headYY");
        payload.getPullRequest().getHead().setRef("feature/x");
        when(deploymentOrchestrator.requestDeployment(any()))
                .thenReturn(DeploymentResult.ready("https://x", "{}"));
        when(selectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of("suiteLifecycle", "offer-as-pr"));
        org.remus.giteabot.prworkflow.PrWorkflowRun runEntity =
                new org.remus.giteabot.prworkflow.PrWorkflowRun();
        runEntity.setId(42L);
        when(runRepository.findById(42L)).thenReturn(java.util.Optional.of(runEntity));
        when(suitePromotionService.promote(any(), any(), any(), eq("acme"), eq("web"), eq("feature/x")))
                .thenReturn(org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService.Outcome
                        .promoted(4242L, "ai-tests/pr-22", List.of("tests/e2e/pr-22/a.spec.ts")));

        w.run(ctx(bot, payload));

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(repoClient, org.mockito.Mockito.atLeast(2))
                .postPullRequestComment(eq("acme"), eq("web"), eq(22L), bodies.capture());
        assertThat(bodies.getAllValues())
                .anyMatch(b -> b.contains("Suite promotion") && b.contains("#4242"));
        verify(suitePromotionService).promote(any(), any(), any(), eq("acme"), eq("web"), eq("feature/x"));
    }

    @Test
    void ephemeralLifecycle_skipsPromotionService() {
        Bot bot = bot(new DeploymentTarget());
        WebhookPayload payload = payload("acme", "web", 23L, "headZZ");
        when(deploymentOrchestrator.requestDeployment(any()))
                .thenReturn(DeploymentResult.ready("https://x", "{}"));
        when(selectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of()); // default → EPHEMERAL

        workflow.run(ctx(bot, payload));

        verify(suitePromotionService, never()).promote(any(), any(), any(), any(), any(), any());
    }

    private static PrWorkflowContext ctx(Bot bot, WebhookPayload payload) {
        return new PrWorkflowContext(bot, payload, 42L, (n, l) -> { }, () -> false);
    }

    private static Bot bot(DeploymentTarget target) {
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("ai_bot");
        WorkflowConfiguration config = new WorkflowConfiguration();
        config.setId(100L);
        bot.setWorkflowConfiguration(config);
        bot.setDeploymentTarget(target);
        bot.setGitIntegration(new org.remus.giteabot.admin.GitIntegration());
        return bot;
    }

    private static WebhookPayload payload(String owner, String repo, long prNumber, String headSha) {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        WebhookPayload.Owner user = new WebhookPayload.Owner();
        user.setLogin(owner);
        repository.setOwner(user);
        repository.setName(repo);
        payload.setRepository(repository);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(prNumber);
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setSha(headSha);
        pr.setHead(head);
        payload.setPullRequest(pr);
        return payload;
    }

    /**
     * Minimal in-memory {@link PrTestSuiteRepository}. Mockito + JPA's
     * default-method surface is awkward to mock; a real fake keeps the test
     * focused on workflow behaviour.
     */
    static class InMemoryPrTestSuiteRepository
            extends org.mockito.Mockito implements PrTestSuiteRepository {

        final java.util.Map<Long, PrTestSuite> entities = new java.util.LinkedHashMap<>();
        private long sequence = 1;

        @Override public java.util.List<PrTestSuite> findByPrNumberAndLifecycleMode(Long prNumber, SuiteLifecycleMode m) {
            return entities.values().stream()
                    .filter(s -> prNumber.equals(s.getPrNumber()) && s.getLifecycleMode() == m)
                    .toList();
        }
        @Override public java.util.List<PrTestSuite> findByRunId(Long runId) {
            return entities.values().stream()
                    .filter(s -> runId != null && runId.equals(s.getRunId()))
                    .toList();
        }
        @Override public java.util.List<PrTestSuite> findByPrNumberOrderByIdDesc(Long prNumber) {
            return entities.values().stream()
                    .filter(s -> prNumber.equals(s.getPrNumber()))
                    .sorted(java.util.Comparator.comparingLong(PrTestSuite::getId).reversed())
                    .toList();
        }
        @Override public <S extends PrTestSuite> S save(S entity) {
            if (entity.getId() == null) entity.setId(sequence++);
            entities.put(entity.getId(), entity);
            return entity;
        }
        @Override public java.util.Optional<PrTestSuite> findById(Long id) {
            return java.util.Optional.ofNullable(entities.get(id));
        }
        @Override public java.util.Optional<PrTestSuite> findByIdWithCases(Long id) {
            // The in-memory test repo never detaches entities, so the lazy
            // collection guard is irrelevant here — just delegate.
            return findById(id);
        }
        // ---- everything else is unused by the workflow tests -----------
        @Override public java.util.List<PrTestSuite> findAll() { return List.copyOf(entities.values()); }
        @Override public java.util.List<PrTestSuite> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<PrTestSuite> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<PrTestSuite> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public long count() { return entities.size(); }
        @Override public void deleteById(Long id) { entities.remove(id); }
        @Override public void delete(PrTestSuite entity) { if (entity.getId() != null) entities.remove(entity.getId()); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(entities::remove); }
        @Override public void deleteAll(Iterable<? extends PrTestSuite> entities) { entities.forEach(this::delete); }
        @Override public void deleteAll() { entities.clear(); }
        @Override public <S extends PrTestSuite> java.util.List<S> saveAll(Iterable<S> es) {
            java.util.List<S> out = new java.util.ArrayList<>();
            for (S e : es) out.add(save(e));
            return out;
        }
        @Override public void flush() { }
        @Override public <S extends PrTestSuite> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends PrTestSuite> java.util.List<S> saveAllAndFlush(Iterable<S> es) { return saveAll(es); }
        @Override public void deleteAllInBatch(Iterable<PrTestSuite> es) { es.forEach(this::delete); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { ids.forEach(entities::remove); }
        @Override public void deleteAllInBatch() { entities.clear(); }
        @Override public PrTestSuite getOne(Long id) { return entities.get(id); }
        @Override public PrTestSuite getById(Long id) { return entities.get(id); }
        @Override public PrTestSuite getReferenceById(Long id) { return entities.get(id); }
        @Override public boolean existsById(Long id) { return entities.containsKey(id); }
        @Override public <S extends PrTestSuite> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }
}
