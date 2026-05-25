package org.remus.giteabot.prworkflow.e2e;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategy;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyRegistry;
import org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService;
import org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinator invoked by {@code BotWebhookService.handlePrClosed(...)} to
 * tear down everything the {@link E2ETestWorkflow} created for the closed
 * pull request and to honour the suite's
 * {@link SuiteLifecycleMode lifecycle mode}:
 *
 * <ol>
 *     <li>For every {@link PrWorkflowRun} of the PR with a stored
 *         deployment handle, broadcast
 *         {@link DeploymentStrategy#teardown(PrWorkflowRun)} across every
 *         registered strategy so the remote preview environment is
 *         released. Strategies that do not recognise the handle return
 *         silently (the default {@code teardown} is a no-op).</li>
 *     <li>Cleanup the on-disk sandbox workspace for those runs.</li>
 *     <li>If the parent PR was <strong>merged</strong>, trigger
 *         {@link SuitePromotionService} for every suite whose lifecycle
 *         mode is {@link SuiteLifecycleMode#PROMOTE_ON_MERGE
 *         PROMOTE_ON_MERGE}. The promotion opens a follow-up PR against
 *         the repository's default branch carrying the generated
 *         tests.</li>
 *     <li>Delete every {@link PrTestSuite} for the PR whose lifecycle
 *         mode is {@link SuiteLifecycleMode#EPHEMERAL} or
 *         {@link SuiteLifecycleMode#COMMIT_TO_PR} (these already pushed
 *         their content during the workflow run). Suites tagged
 *         {@link SuiteLifecycleMode#OFFER_AS_PR} /
 *         {@link SuiteLifecycleMode#PROMOTE_ON_MERGE} are deliberately
 *         kept so the dashboard can correlate the parent run with the
 *         follow-up PR; the nightly GC (future work) eventually removes
 *         them.</li>
 * </ol>
 *
 * <p>Best-effort: per-run / per-suite failures are logged but do not abort
 * the overall close handler.</p>
 */
@Slf4j
@Component
public class E2eTestPrCloseHandler {

    private static final Set<SuiteLifecycleMode> CLEANUP_MODES = Set.of(
            SuiteLifecycleMode.EPHEMERAL,
            SuiteLifecycleMode.COMMIT_TO_PR);

    private final PrWorkflowRunRepository runRepository;
    private final PrTestSuiteRepository suiteRepository;
    private final PrTestWorkspaceManager workspaceManager;
    private final DeploymentStrategyRegistry strategyRegistry;
    private final SuitePromotionService suitePromotionService;
    private final BotRepository botRepository;
    private final WorkflowSelectionService workflowSelectionService;

    public E2eTestPrCloseHandler(PrWorkflowRunRepository runRepository,
                                 PrTestSuiteRepository suiteRepository,
                                 PrTestWorkspaceManager workspaceManager,
                                 DeploymentStrategyRegistry strategyRegistry,
                                 SuitePromotionService suitePromotionService,
                                 BotRepository botRepository,
                                 WorkflowSelectionService workflowSelectionService) {
        this.runRepository = runRepository;
        this.suiteRepository = suiteRepository;
        this.workspaceManager = workspaceManager;
        this.strategyRegistry = strategyRegistry;
        this.suitePromotionService = suitePromotionService;
        this.botRepository = botRepository;
        this.workflowSelectionService = workflowSelectionService;
    }

    @Transactional
    public void onPrClosed(Long botId, String repoOwner, String repoName,
                           Long prNumber, boolean merged, WebhookPayload payload) {
        if (botId == null || repoOwner == null || repoName == null || prNumber == null) {
            log.debug("E2eTestPrCloseHandler skipped — incomplete PR identity ({}, {}/{}, {})",
                    botId, repoOwner, repoName, prNumber);
            return;
        }

        // 1) Teardown deployments + workspaces for every e2e-test run on this PR.
        //    Terminal SUCCESS / FAILED runs are included deliberately because
        //    the preview environment may still be alive (long-lived previews
        //    can outlive the workflow run itself).
        List<PrWorkflowRun> runs = runRepository
                .findByBotIdAndRepoOwnerAndRepoNameAndPrNumberAndWorkflowKeyAndStatusIn(
                        botId, repoOwner, repoName, prNumber, E2ETestWorkflow.KEY,
                        List.of(PrWorkflowRunStatus.RUNNING,
                                PrWorkflowRunStatus.WAITING_DEPLOY,
                                PrWorkflowRunStatus.SUCCESS,
                                PrWorkflowRunStatus.FAILED));
        for (PrWorkflowRun run : runs) {
            teardownDeployment(run);
            workspaceManager.cleanup(run.getId());
        }

        // 2) PROMOTE_ON_MERGE — open follow-up PR against the default branch
        //    for every promotable suite, but only when the parent PR was
        //    actually merged.
        if (merged) {
            promoteMergedSuites(botId, repoOwner, repoName, prNumber, runs);
        }

        // 3) Delete suites that are safe to drop. OFFER_AS_PR /
        //    PROMOTE_ON_MERGE rows are kept for dashboard correlation.
        List<PrTestSuite> drops = new ArrayList<>();
        for (SuiteLifecycleMode mode : CLEANUP_MODES) {
            drops.addAll(suiteRepository.findByPrNumberAndLifecycleMode(prNumber, mode));
        }
        if (!drops.isEmpty()) {
            log.info("E2eTestPrCloseHandler: deleting {} cleanup suite(s) for PR #{} on {}/{}",
                    drops.size(), prNumber, repoOwner, repoName);
            suiteRepository.deleteAll(drops);
        }
    }

    private void promoteMergedSuites(Long botId, String repoOwner, String repoName,
                                     Long prNumber, List<PrWorkflowRun> runs) {
        List<PrTestSuite> promotable = suiteRepository
                .findByPrNumberAndLifecycleMode(prNumber, SuiteLifecycleMode.PROMOTE_ON_MERGE);
        if (promotable.isEmpty()) {
            return;
        }
        Bot bot = botRepository.findById(botId).orElse(null);
        if (bot == null) {
            log.warn("PROMOTE_ON_MERGE: bot id {} not found — skipping promotion of {} suite(s)",
                    botId, promotable.size());
            return;
        }
        int thresholdPercent = resolvePromotionThreshold(bot);
        for (PrTestSuite suite : promotable) {
            PrWorkflowRun run = runs.stream()
                    .filter(r -> r.getId().equals(suite.getRunId()))
                    .findFirst()
                    .orElseGet(() -> runRepository.findById(suite.getRunId()).orElse(null));
            if (run == null) {
                log.info("PROMOTE_ON_MERGE: skipping suite id={} — backing run not found",
                        suite.getId());
                continue;
            }
            if (!isEligibleForPromotion(suite, run, thresholdPercent)) {
                continue;
            }
            try {
                SuitePromotionService.Outcome outcome = suitePromotionService.promote(
                        bot, run, suite, repoOwner, repoName, /* featureBranch = unused */ null);
                log.info("PROMOTE_ON_MERGE: suite id={} → {}",
                        suite.getId(), outcome.kind());
            } catch (RuntimeException e) {
                log.warn("PROMOTE_ON_MERGE: suite id={} promotion threw {} — continuing",
                        suite.getId(), e.toString());
            }
        }
    }

    /**
     * Loads the bot's {@code promotionThresholdPercent} param for the
     * {@code e2e-test} workflow, clamped to {@code [0,100]}. Bots without
     * a {@code WorkflowConfiguration} fall back to {@code 100} (only fully
     * green suites are promoted — the historical default).
     */
    private int resolvePromotionThreshold(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) {
            return E2ETestWorkflow.DEFAULT_PROMOTION_THRESHOLD_PERCENT;
        }
        try {
            Map<String, Object> params = workflowSelectionService.resolveParams(
                    bot.getWorkflowConfiguration().getId(), E2ETestWorkflow.KEY);
            Object raw = params.get(E2eTestParam.PROMOTION_THRESHOLD_PERCENT.key());
            int value;
            if (raw instanceof Number n) {
                value = n.intValue();
            } else if (raw != null) {
                value = Integer.parseInt(raw.toString().trim());
            } else {
                value = E2ETestWorkflow.DEFAULT_PROMOTION_THRESHOLD_PERCENT;
            }
            return Math.max(0, Math.min(100, value));
        } catch (RuntimeException e) {
            log.debug("PROMOTE_ON_MERGE: failed to resolve promotionThresholdPercent for bot id={} ({}) — defaulting to {}",
                    bot.getId(), e.getMessage(), E2ETestWorkflow.DEFAULT_PROMOTION_THRESHOLD_PERCENT);
            return E2ETestWorkflow.DEFAULT_PROMOTION_THRESHOLD_PERCENT;
        }
    }

    /**
     * A suite qualifies for {@code PROMOTE_ON_MERGE} when the backing run
     * succeeded outright, or when the run technically failed but the
     * suite's pass-rate (computed from its {@link PrTestCase} rows) still
     * meets the configured {@code promotionThresholdPercent}. Runs that
     * ended in any non-terminal status (RUNNING / WAITING_DEPLOY /
     * CANCELLED) are never promoted.
     */
    private boolean isEligibleForPromotion(PrTestSuite suite, PrWorkflowRun run, int thresholdPercent) {
        PrWorkflowRunStatus status = run.getStatus();
        if (status == PrWorkflowRunStatus.SUCCESS) {
            return true;
        }
        if (status != PrWorkflowRunStatus.FAILED) {
            log.info("PROMOTE_ON_MERGE: skipping suite id={} — backing run status {} is not terminal",
                    suite.getId(), status);
            return false;
        }
        // FAILED run — only promote when the per-case pass-rate clears the threshold.
        int attempted = 0;
        int passed = 0;
        List<PrTestCase> cases = suite.getCases();
        if (cases != null) {
            for (PrTestCase c : cases) {
                PrTestCaseStatus s = c.getLastStatus();
                if (s == PrTestCaseStatus.PENDING || s == PrTestCaseStatus.SKIPPED) {
                    continue;
                }
                attempted++;
                if (s == PrTestCaseStatus.PASSED || s == PrTestCaseStatus.FLAKY) {
                    passed++;
                }
            }
        }
        if (attempted == 0) {
            log.info("PROMOTE_ON_MERGE: skipping suite id={} — no executed test cases on a FAILED run",
                    suite.getId());
            return false;
        }
        int passRate = (int) Math.floor((passed * 100.0) / attempted);
        if (passRate < thresholdPercent) {
            log.info("PROMOTE_ON_MERGE: skipping suite id={} — pass-rate {}% below threshold {}%",
                    suite.getId(), passRate, thresholdPercent);
            return false;
        }
        log.info("PROMOTE_ON_MERGE: suite id={} — backing run FAILED but pass-rate {}% meets threshold {}%, promoting anyway",
                suite.getId(), passRate, thresholdPercent);
        return true;
    }

    private void teardownDeployment(PrWorkflowRun run) {
        if (run.getDeploymentHandleJson() == null || run.getDeploymentHandleJson().isBlank()) {
            return;
        }
        for (DeploymentStrategy strategy : strategyRegistry.all()) {
            try {
                strategy.teardown(run);
            } catch (RuntimeException e) {
                log.warn("E2eTestPrCloseHandler: teardown via {} for run id={} threw {} — ignoring",
                        strategy.getClass().getSimpleName(), run.getId(), e.toString());
            }
        }
    }
}
