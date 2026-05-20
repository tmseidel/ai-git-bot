package org.remus.giteabot.prworkflow.e2e;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.PrWorkflowRunStatus;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategy;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyRegistry;
import org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService;
import org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
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

    public E2eTestPrCloseHandler(PrWorkflowRunRepository runRepository,
                                 PrTestSuiteRepository suiteRepository,
                                 PrTestWorkspaceManager workspaceManager,
                                 DeploymentStrategyRegistry strategyRegistry,
                                 SuitePromotionService suitePromotionService,
                                 BotRepository botRepository) {
        this.runRepository = runRepository;
        this.suiteRepository = suiteRepository;
        this.workspaceManager = workspaceManager;
        this.strategyRegistry = strategyRegistry;
        this.suitePromotionService = suitePromotionService;
        this.botRepository = botRepository;
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
        for (PrTestSuite suite : promotable) {
            PrWorkflowRun run = runs.stream()
                    .filter(r -> r.getId().equals(suite.getRunId()))
                    .findFirst()
                    .orElseGet(() -> runRepository.findById(suite.getRunId()).orElse(null));
            if (run == null || run.getStatus() != PrWorkflowRunStatus.SUCCESS) {
                log.info("PROMOTE_ON_MERGE: skipping suite id={} — backing run missing or not SUCCESS",
                        suite.getId());
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
