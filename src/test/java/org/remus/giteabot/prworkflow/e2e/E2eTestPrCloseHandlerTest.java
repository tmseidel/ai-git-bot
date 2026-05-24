package org.remus.giteabot.prworkflow.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyRegistry;
import org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService;
import org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link E2eTestPrCloseHandler} — focused on the
 * M7 promotion + cleanup branching:
 *
 * <ul>
 *   <li>ephemeral + commit-to-pr suites are deleted on PR close,</li>
 *   <li>offer-as-pr / promote-on-merge suites are kept for dashboard
 *       correlation,</li>
 *   <li>promote-on-merge only fires {@link SuitePromotionService} when
 *       the parent PR was actually merged AND the backing run is in
 *       SUCCESS.</li>
 * </ul>
 */
class E2eTestPrCloseHandlerTest {

    private InMemorySuiteRepo suiteRepo;
    private PrWorkflowRunRepository runRepo;
    private PrTestWorkspaceManager workspaceManager;
    private DeploymentStrategyRegistry strategyRegistry;
    private SuitePromotionService promotion;
    private BotRepository botRepo;
    private org.remus.giteabot.prworkflow.config.WorkflowSelectionService workflowSelectionService;

    private E2eTestPrCloseHandler handler;

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        suiteRepo = new InMemorySuiteRepo();
        runRepo = mock(PrWorkflowRunRepository.class);
        workspaceManager = PrTestWorkspaceManager.rootedAt(tmp);
        strategyRegistry = mock(DeploymentStrategyRegistry.class);
        when(strategyRegistry.all()).thenReturn(List.of());
        promotion = mock(SuitePromotionService.class);
        botRepo = mock(BotRepository.class);
        workflowSelectionService = mock(org.remus.giteabot.prworkflow.config.WorkflowSelectionService.class);
        // Default: no params → handler falls back to threshold=100 (legacy behaviour).
        when(workflowSelectionService.resolveParams(anyLong(), eq(E2ETestWorkflow.KEY)))
                .thenReturn(Map.of());
        handler = new E2eTestPrCloseHandler(runRepo, suiteRepo, workspaceManager,
                strategyRegistry, promotion, botRepo, workflowSelectionService);
    }

    @Test
    void deletesEphemeralAndCommitToPrSuites_keepsOfferAsPrAndPromoteOnMerge() {
        suiteRepo.add(suite(1L, 100L, SuiteLifecycleMode.EPHEMERAL));
        suiteRepo.add(suite(2L, 100L, SuiteLifecycleMode.COMMIT_TO_PR));
        suiteRepo.add(suite(3L, 100L, SuiteLifecycleMode.OFFER_AS_PR));
        suiteRepo.add(suite(4L, 100L, SuiteLifecycleMode.PROMOTE_ON_MERGE));
        when(runRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        handler.onPrClosed(1L, "acme", "web", 100L, /* merged = */ false, new WebhookPayload());

        assertThat(suiteRepo.entities.values())
                .extracting(PrTestSuite::getLifecycleMode)
                .containsExactlyInAnyOrder(SuiteLifecycleMode.OFFER_AS_PR,
                        SuiteLifecycleMode.PROMOTE_ON_MERGE);
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
    }

    @Test
    void promoteOnMerge_skippedWhenPrNotMerged() {
        suiteRepo.add(suite(1L, 100L, SuiteLifecycleMode.PROMOTE_ON_MERGE));
        when(runRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        handler.onPrClosed(7L, "acme", "web", 100L, /* merged = */ false, new WebhookPayload());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
    }

    @Test
    void promoteOnMerge_invokedWhenPrMergedAndBackingRunSuccess() {
        PrTestSuite suite = suite(1L, 100L, SuiteLifecycleMode.PROMOTE_ON_MERGE);
        suite.setRunId(555L);
        suiteRepo.add(suite);

        PrWorkflowRun successfulRun = new PrWorkflowRun();
        successfulRun.setId(555L);
        successfulRun.setStatus(PrWorkflowRunStatus.SUCCESS);
        when(runRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(successfulRun));

        Bot bot = new Bot();
        bot.setId(7L);
        bot.setName("bot");
        when(botRepo.findById(7L)).thenReturn(Optional.of(bot));
        when(promotion.promote(any(), any(), any(), eq("acme"), eq("web"), any()))
                .thenReturn(SuitePromotionService.Outcome
                        .promoted(987L, "ai-tests/promoted-pr-100", List.of("tests/e2e/x.spec.ts")));

        handler.onPrClosed(7L, "acme", "web", 100L, /* merged = */ true, new WebhookPayload());

        verify(promotion).promote(eq(bot), eq(successfulRun), eq(suite),
                eq("acme"), eq("web"), any());
    }

    @Test
    void promoteOnMerge_skippedWhenBackingRunNotSuccess() {
        PrTestSuite suite = suite(1L, 100L, SuiteLifecycleMode.PROMOTE_ON_MERGE);
        suite.setRunId(555L);
        suiteRepo.add(suite);

        PrWorkflowRun failedRun = new PrWorkflowRun();
        failedRun.setId(555L);
        failedRun.setStatus(PrWorkflowRunStatus.FAILED);
        when(runRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(failedRun));
        when(botRepo.findById(anyLong())).thenReturn(Optional.of(new Bot()));

        handler.onPrClosed(7L, "acme", "web", 100L, /* merged = */ true, new WebhookPayload());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
    }

    @Test
    void promoteOnMerge_failedRunButPassRateMeetsThreshold_promotesAnyway() {
        // 4 of 5 cases passed (80%), backing run ended FAILED — with threshold=80
        // the suite should still be promoted.
        PrTestSuite suite = suiteWithCases(1L, 100L, SuiteLifecycleMode.PROMOTE_ON_MERGE,
                java.util.List.of(PrTestCaseStatus.PASSED, PrTestCaseStatus.PASSED,
                        PrTestCaseStatus.PASSED, PrTestCaseStatus.PASSED,
                        PrTestCaseStatus.FAILED));
        suite.setRunId(555L);
        suiteRepo.add(suite);

        PrWorkflowRun failedRun = new PrWorkflowRun();
        failedRun.setId(555L);
        failedRun.setStatus(PrWorkflowRunStatus.FAILED);
        when(runRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(failedRun));

        Bot bot = botWithConfig(7L, /* configId = */ 99L);
        when(botRepo.findById(7L)).thenReturn(Optional.of(bot));
        when(workflowSelectionService.resolveParams(99L, E2ETestWorkflow.KEY))
                .thenReturn(Map.of("promotionThresholdPercent", 80));
        when(promotion.promote(any(), any(), any(), eq("acme"), eq("web"), any()))
                .thenReturn(SuitePromotionService.Outcome
                        .promoted(987L, "ai-tests/promoted-pr-100", List.of("tests/e2e/x.spec.ts")));

        handler.onPrClosed(7L, "acme", "web", 100L, /* merged = */ true, new WebhookPayload());

        verify(promotion).promote(eq(bot), eq(failedRun), eq(suite),
                eq("acme"), eq("web"), any());
    }

    @Test
    void promoteOnMerge_failedRunButPassRateBelowThreshold_skipsPromotion() {
        // 2 of 5 cases passed (40%) with threshold=80 — must NOT promote.
        PrTestSuite suite = suiteWithCases(1L, 100L, SuiteLifecycleMode.PROMOTE_ON_MERGE,
                java.util.List.of(PrTestCaseStatus.PASSED, PrTestCaseStatus.PASSED,
                        PrTestCaseStatus.FAILED, PrTestCaseStatus.FAILED,
                        PrTestCaseStatus.FAILED));
        suite.setRunId(555L);
        suiteRepo.add(suite);

        PrWorkflowRun failedRun = new PrWorkflowRun();
        failedRun.setId(555L);
        failedRun.setStatus(PrWorkflowRunStatus.FAILED);
        when(runRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(failedRun));

        Bot bot = botWithConfig(7L, 99L);
        when(botRepo.findById(7L)).thenReturn(Optional.of(bot));
        when(workflowSelectionService.resolveParams(99L, E2ETestWorkflow.KEY))
                .thenReturn(Map.of("promotionThresholdPercent", 80));

        handler.onPrClosed(7L, "acme", "web", 100L, /* merged = */ true, new WebhookPayload());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
    }

    @Test
    void promoteOnMerge_failedRunWithNoExecutedCases_neverPromotes() {
        PrTestSuite suite = suiteWithCases(1L, 100L, SuiteLifecycleMode.PROMOTE_ON_MERGE,
                java.util.List.of(PrTestCaseStatus.PENDING, PrTestCaseStatus.SKIPPED));
        suite.setRunId(555L);
        suiteRepo.add(suite);

        PrWorkflowRun failedRun = new PrWorkflowRun();
        failedRun.setId(555L);
        failedRun.setStatus(PrWorkflowRunStatus.FAILED);
        when(runRepo.findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(failedRun));

        Bot bot = botWithConfig(7L, 99L);
        when(botRepo.findById(7L)).thenReturn(Optional.of(bot));
        when(workflowSelectionService.resolveParams(99L, E2ETestWorkflow.KEY))
                .thenReturn(Map.of("promotionThresholdPercent", 0));

        handler.onPrClosed(7L, "acme", "web", 100L, /* merged = */ true, new WebhookPayload());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
    }

    @Test
    void noOpWhenPrIdentityIncomplete() {
        handler.onPrClosed(null, null, null, null, false, new WebhookPayload());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        verify(runRepo, never())
                .findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                        any(), any(), any(), any(), any(), any());
    }

    // ---- helpers ---------------------------------------------------------

    private static PrTestSuite suite(long id, long prNumber, SuiteLifecycleMode mode) {
        PrTestSuite s = new PrTestSuite();
        s.setId(id);
        s.setPrNumber(prNumber);
        s.setLifecycleMode(mode);
        return s;
    }

    private static PrTestSuite suiteWithCases(long id, long prNumber, SuiteLifecycleMode mode,
                                              List<PrTestCaseStatus> caseStatuses) {
        PrTestSuite s = suite(id, prNumber, mode);
        List<PrTestCase> cases = new ArrayList<>();
        for (int i = 0; i < caseStatuses.size(); i++) {
            PrTestCase c = new PrTestCase();
            c.setId((long) (i + 1));
            c.setPath("tests/case-" + i + ".spec.ts");
            c.setContent("// case " + i);
            c.setLastStatus(caseStatuses.get(i));
            c.setSuite(s);
            cases.add(c);
        }
        s.setCases(cases);
        return s;
    }

    private static Bot botWithConfig(long botId, long configId) {
        Bot bot = new Bot();
        bot.setId(botId);
        bot.setName("bot-" + botId);
        org.remus.giteabot.prworkflow.config.WorkflowConfiguration cfg =
                new org.remus.giteabot.prworkflow.config.WorkflowConfiguration();
        cfg.setId(configId);
        cfg.setName("cfg-" + configId);
        bot.setWorkflowConfiguration(cfg);
        return bot;
    }

    /**
     * Minimal in-memory {@link PrTestSuiteRepository}. Same pattern as
     * {@code E2ETestWorkflowTest.InMemoryPrTestSuiteRepository}; copied
     * locally so the two tests do not share state.
     */
    static class InMemorySuiteRepo implements PrTestSuiteRepository {
        final Map<Long, PrTestSuite> entities = new HashMap<>();

        void add(PrTestSuite s) { entities.put(s.getId(), s); }

        @Override public List<PrTestSuite> findByPrNumberAndLifecycleMode(Long prNumber,
                                                                         SuiteLifecycleMode mode) {
            return entities.values().stream()
                    .filter(s -> prNumber.equals(s.getPrNumber()) && s.getLifecycleMode() == mode)
                    .toList();
        }
        @Override public List<PrTestSuite> findByRunId(Long runId) {
            return entities.values().stream()
                    .filter(s -> runId != null && runId.equals(s.getRunId()))
                    .toList();
        }
        @Override public List<PrTestSuite> findByPrNumberOrderByIdDesc(Long prNumber) {
            return entities.values().stream()
                    .filter(s -> prNumber.equals(s.getPrNumber()))
                    .sorted(java.util.Comparator.comparingLong(PrTestSuite::getId).reversed())
                    .toList();
        }
        @Override public <S extends PrTestSuite> S save(S entity) { entities.put(entity.getId(), entity); return entity; }
        @Override public <S extends PrTestSuite> List<S> saveAll(Iterable<S> entities) {
            List<S> out = new ArrayList<>();
            for (S e : entities) { save(e); out.add(e); }
            return out;
        }
        @Override public Optional<PrTestSuite> findById(Long id) { return Optional.ofNullable(entities.get(id)); }
        @Override public Optional<PrTestSuite> findByIdWithCases(Long id) { return findById(id); }
        @Override public boolean existsById(Long id) { return entities.containsKey(id); }
        @Override public List<PrTestSuite> findAll() { return new ArrayList<>(entities.values()); }
        @Override public List<PrTestSuite> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public long count() { return entities.size(); }
        @Override public void deleteById(Long id) { entities.remove(id); }
        @Override public void delete(PrTestSuite entity) { entities.remove(entity.getId()); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(entities::remove); }
        @Override public void deleteAll(Iterable<? extends PrTestSuite> es) { es.forEach(this::delete); }
        @Override public void deleteAll() { entities.clear(); }
        @Override public List<PrTestSuite> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<PrTestSuite> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends PrTestSuite> List<S> saveAllAndFlush(Iterable<S> es) { return saveAll(es); }
        @Override public void deleteAllInBatch(Iterable<PrTestSuite> es) { deleteAll(es); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { deleteAllById(ids); }
        @Override public void deleteAllInBatch() { deleteAll(); }
        @Override public PrTestSuite getOne(Long id) { return entities.get(id); }
        @Override public PrTestSuite getById(Long id) { return entities.get(id); }
        @Override public PrTestSuite getReferenceById(Long id) { return entities.get(id); }
        @Override public <S extends PrTestSuite> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends PrTestSuite> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
        @Override public <S extends PrTestSuite> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
        @Override public void flush() {}
        @Override public <S extends PrTestSuite> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { return Optional.empty(); }
        @Override public <S extends PrTestSuite, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }
}
