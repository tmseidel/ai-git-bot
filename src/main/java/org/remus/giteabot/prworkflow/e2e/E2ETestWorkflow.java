package org.remus.giteabot.prworkflow.e2e;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowRunRepository;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.config.DeploymentTarget;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.remus.giteabot.prworkflow.deployment.DeploymentOrchestrator;
import org.remus.giteabot.prworkflow.deployment.DeploymentResult;
import org.remus.giteabot.prworkflow.deployment.DeploymentStatus;
import org.remus.giteabot.prworkflow.e2e.promotion.SuitePromotionService;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteOutcome;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteOutcomeStatus;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteRequest;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteRunner;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteRunnerRegistry;
import org.remus.giteabot.prworkflow.e2e.workspace.PrTestWorkspaceManager;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Second {@link PrWorkflow} implementation (after {@code ReviewWorkflow}).
 * Drives the full E2E test loop:
 *
 * <ol>
 *     <li>Resolve the configured params and look up the bot's
 *         {@link DeploymentTarget}; abort cleanly (with a PR comment) when
 *         no target is configured.</li>
 *     <li>Persist a draft {@link PrTestSuite} row for this run.</li>
 *     <li>Hand off to {@link DeploymentOrchestrator#requestDeployment} —
 *         the workflow blocks on the deployment callback or fails with a
 *         clear comment when the strategy reports {@code FAILED} /
 *         {@code REJECTED}.</li>
 *     <li>Allocate a sandboxed workspace and dispatch to the registered
 *         {@link TestSuiteRunner} for the chosen framework; persist its
 *         {@link PrTestCase} updates.</li>
 *     <li>Post a Markdown summary comment to the PR via the bot's
 *         {@link RepositoryApiClient}.</li>
 * </ol>
 *
 * <p>Category {@link PrWorkflowCategory#TESTING} — disabled by default on
 * the seeded {@code Default} configuration; operators opt in explicitly via
 * the workflow-configuration UI.</p>
 *
 * <p>M4 wave 1 ships only the {@code NoopTestSuiteRunner}: the suite is
 * created and the PR comment is posted, but no tests are generated. Wave 2
 * plugs in the LLM-driven {@code PlaywrightTestSuiteRunner} without
 * touching this class.</p>
 */
@Slf4j
@Component
public class E2ETestWorkflow implements PrWorkflow {

    public static final String KEY = "e2e-test";

    /** Hard upper bound the workflow never crosses, regardless of params. */
    static final int ABSOLUTE_MAX_TEST_CASES = 100;
    static final int DEFAULT_MAX_RETRIES = 1;
    static final int DEFAULT_MAX_TEST_CASES = 20;
    static final E2eTestFramework DEFAULT_FRAMEWORK = E2eTestFramework.PLAYWRIGHT;

    private final DeploymentOrchestrator deploymentOrchestrator;
    private final PrTestSuiteRepository suiteRepository;
    private final PrTestWorkspaceManager workspaceManager;
    private final TestSuiteRunnerRegistry runnerRegistry;
    private final WorkflowSelectionService selectionService;
    private final GiteaClientFactory giteaClientFactory;
    private final SuitePromotionService suitePromotionService;
    private final PrWorkflowRunRepository runRepository;

    /**
     * {@code selectionService} is {@link Lazy} because
     * {@link WorkflowSelectionService} depends transitively on
     * {@code PrWorkflowRegistry}, which itself enumerates every
     * {@link PrWorkflow} bean — including this one. The lazy proxy breaks
     * the construction cycle without disturbing runtime semantics.
     */
    public E2ETestWorkflow(DeploymentOrchestrator deploymentOrchestrator,
                           PrTestSuiteRepository suiteRepository,
                           PrTestWorkspaceManager workspaceManager,
                           TestSuiteRunnerRegistry runnerRegistry,
                           @Lazy WorkflowSelectionService selectionService,
                           GiteaClientFactory giteaClientFactory,
                           SuitePromotionService suitePromotionService,
                           PrWorkflowRunRepository runRepository) {
        this.deploymentOrchestrator = deploymentOrchestrator;
        this.suiteRepository = suiteRepository;
        this.workspaceManager = workspaceManager;
        this.runnerRegistry = runnerRegistry;
        this.selectionService = selectionService;
        this.giteaClientFactory = giteaClientFactory;
        this.suitePromotionService = suitePromotionService;
        this.runRepository = runRepository;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "E2E Tests";
    }

    @Override
    public PrWorkflowCategory category() {
        return PrWorkflowCategory.TESTING;
    }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField("framework", "Test framework",
                        WorkflowParamField.ParamType.STRING, false,
                        DEFAULT_FRAMEWORK.key(),
                        "playwright (default, well-tested) — pytest, k6, cypress are experimental and only smoke-tested."),
                new WorkflowParamField("maxRetries", "Max retries per test",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(DEFAULT_MAX_RETRIES),
                        "Per-test retry budget. A test that passes after retries is tagged FLAKY."),
                new WorkflowParamField("maxTestCases", "Max test cases per suite",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(DEFAULT_MAX_TEST_CASES),
                        "Cost guard. Capped at " + ABSOLUTE_MAX_TEST_CASES
                                + " regardless of the configured value."),
                new WorkflowParamField("suiteLifecycle", "Suite lifecycle",
                        WorkflowParamField.ParamType.STRING, false,
                        SuiteLifecycleMode.EPHEMERAL.key(),
                        "What happens to the generated suite. One of: "
                                + "ephemeral (delete on PR close, default), "
                                + "offer-as-pr (open a follow-up PR with the tests), "
                                + "promote-on-merge (open follow-up PR against the default branch once the parent PR merges), "
                                + "commit-to-pr (commit the tests directly onto the feature branch).")
        );
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        Bot bot = context.bot();
        WebhookPayload payload = context.payload();
        long prNumber = resolvePrNumber(payload);

        Map<String, Object> params = bot.getWorkflowConfiguration() == null
                ? Map.of()
                : selectionService.resolveParams(bot.getWorkflowConfiguration().getId(), KEY);
        E2eTestFramework framework = resolveFramework(params);
        int maxRetries = clamp(intParam(params, "maxRetries", DEFAULT_MAX_RETRIES), 0, 5);
        int maxTestCases = clamp(intParam(params, "maxTestCases", DEFAULT_MAX_TEST_CASES),
                1, ABSOLUTE_MAX_TEST_CASES);
        SuiteLifecycleMode lifecycleMode = resolveLifecycle(params);

        if (bot.getDeploymentTarget() == null) {
            String comment = E2eTestSummaryRenderer.renderSkipped(prNumber,
                    "no deployment target is configured on bot '" + bot.getName()
                            + "'. Configure one under **System settings → Deployment targets** "
                            + "and assign it to the bot.");
            postPrComment(bot, payload, prNumber, comment);
            context.appendStep("e2e-precheck",
                    "Skipped — bot has no deployment target");
            return WorkflowResult.skipped("No deployment target on bot");
        }

        boolean rerunOnly = "true".equalsIgnoreCase(context.hint(PrWorkflowContext.HINT_RERUN_ONLY));

        // Locate the most-recent previous suite that actually has test cases attached
        // when we are in rerun-only mode. If none exists we fall back to a full run.
        PrTestSuite previousSuite = null;
        if (rerunOnly) {
            // NB: findByPrNumberOrderByIdDesc returns detached entities with a lazy
            // `cases` bag - accessing it here would throw LazyInitializationException
            // because the async workflow runs outside the repository's transaction.
            // Re-fetch each candidate via the fetch-join query until we find one with
            // actual test cases attached.
            previousSuite = suiteRepository.findByPrNumberOrderByIdDesc(prNumber).stream()
                    .map(s -> suiteRepository.findByIdWithCases(s.getId()).orElse(null))
                    .filter(s -> s != null && s.getCases() != null && !s.getCases().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (previousSuite == null) {
                log.info("[Workflow '{}'] rerun-only requested for PR #{} but no previous suite with "
                        + "cases found — falling back to full regenerate run", KEY, prNumber);
                rerunOnly = false;
            } else {
                log.info("[Workflow '{}'] rerun-only for PR #{}: reusing suite #{} ({} cases)",
                        KEY, prNumber, previousSuite.getId(),
                        previousSuite.getCases().size());
            }
        }

        // Tell the operator the run has started — generation, deployment and
        // execution can take several minutes, so an immediate ack avoids the
        // "is it stuck?" question and matches the 👀 reaction posted by
        // E2eTestSlashCommandHandler for re-run / regenerate slash commands.
        postPrComment(bot, payload, prNumber,
                rerunOnly
                        ? E2eTestSummaryRenderer.renderRerunStarting(prNumber, framework, lifecycleMode)
                        : E2eTestSummaryRenderer.renderStarting(prNumber, framework, lifecycleMode));
        context.appendStep("e2e-start",
                (rerunOnly ? "Re-running existing tests" : "Starting E2E run")
                        + " (framework=" + framework.key()
                        + ", lifecycle=" + lifecycleMode.key() + ")");

        context.requireActive("before persisting PrTestSuite");
        PrTestSuite suite = new PrTestSuite();
        suite.setRunId(context.runId());
        suite.setPrNumber(prNumber);
        suite.setFramework(framework);
        suite.setSourceTreeRef(headSha(payload));
        suite.setLifecycleMode(lifecycleMode);
        suite.setCreatedAt(Instant.now());
        suite = suiteRepository.save(suite);

        DeploymentResult deployment;
        try {
            deployment = deploymentOrchestrator.requestDeployment(context);
        } catch (RuntimeException e) {
            log.warn("[Workflow '{}'] Deployment failed: {}", KEY, e.getMessage(), e);
            String comment = E2eTestSummaryRenderer.renderFailed(prNumber,
                    "deployment orchestration threw: " + e.getMessage());
            postPrComment(bot, payload, prNumber, comment);
            return WorkflowResult.failed("Deployment orchestration error: " + e.getMessage());
        }

        if (deployment.status() != DeploymentStatus.READY) {
            String reason = deployment.errorMessage() == null
                    ? ("deployment status " + deployment.status())
                    : deployment.errorMessage();
            String comment = E2eTestSummaryRenderer.renderFailed(prNumber,
                    "preview deployment did not become ready (" + reason + ").");
            postPrComment(bot, payload, prNumber, comment);
            return WorkflowResult.failed("Deployment " + deployment.status() + ": " + reason);
        }

        context.requireActive("before allocating workspace");
        Path workspace;
        try {
            workspace = workspaceManager.allocate(context.runId(), framework);
        } catch (IOException e) {
            log.warn("[Workflow '{}'] Workspace allocation failed: {}", KEY, e.getMessage(), e);
            String comment = E2eTestSummaryRenderer.renderFailed(prNumber,
                    "failed to allocate test workspace: " + e.getMessage());
            postPrComment(bot, payload, prNumber, comment);
            return WorkflowResult.failed("Workspace allocation failed: " + e.getMessage());
        }

        TestSuiteOutcome outcome;
        TestSuiteRunner runner = runnerRegistry.find(framework).orElse(null);
        if (runner == null) {
            outcome = TestSuiteOutcome.skipped(
                    "No TestSuiteRunner registered for framework " + framework.key()
                            + " (available: " + runnerRegistry.describe() + ")");
        } else {
            TestSuiteRequest request = new TestSuiteRequest(
                    context, bot, payload, suite, workspace,
                    framework, deployment.previewUrl(), maxRetries, maxTestCases,
                    previousSuite);
            try {
                outcome = runner.run(request);
            } catch (RuntimeException e) {
                log.warn("[Workflow '{}'] Runner '{}' threw: {}",
                        KEY, runner.getClass().getSimpleName(), e.getMessage(), e);
                outcome = TestSuiteOutcome.error(
                        "Runner " + runner.getClass().getSimpleName()
                                + " threw " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
            }
            // Re-fetch with the agent's persisted updates so the comment
            // reflects the final per-case statuses. Must use the JOIN
            // FETCH variant: we are on the async orchestrator thread,
            // outside any active Hibernate session, and the renderer
            // below touches the lazy `cases` collection — a plain
            // findById would yield a detached entity and throw
            // LazyInitializationException.
            suite = suiteRepository.findByIdWithCases(suite.getId()).orElse(suite);
        }

        context.appendStep("e2e-runner", outcome.status() + " — " + outcome.summary());
        String comment = E2eTestSummaryRenderer.render(suite, outcome, deployment.previewUrl());
        postPrComment(bot, payload, prNumber, comment);

        // M7 — offer/commit modes promote immediately after a successful run.
        // PROMOTE_ON_MERGE waits for the parent PR to merge (handled by
        // E2eTestPrCloseHandler).
        if (outcome.status() == TestSuiteOutcomeStatus.PASSED
                && (lifecycleMode == SuiteLifecycleMode.OFFER_AS_PR
                    || lifecycleMode == SuiteLifecycleMode.COMMIT_TO_PR)) {
            promoteIfRequested(bot, payload, suite, context, lifecycleMode);
        }

        return mapOutcome(outcome);
    }

    /**
     * M7 entry point invoked immediately after a successful run for the
     * "promote now" lifecycle modes. Best-effort: failures are logged +
     * surfaced as a PR comment but never alter the workflow's terminal
     * status.
     */
    private void promoteIfRequested(Bot bot, WebhookPayload payload, PrTestSuite suite,
                                    PrWorkflowContext context, SuiteLifecycleMode mode) {
        if (payload.getRepository() == null
                || payload.getRepository().getOwner() == null
                || payload.getPullRequest() == null
                || payload.getPullRequest().getHead() == null) {
            log.warn("[Workflow '{}'] Cannot promote — payload missing repo / PR head", KEY);
            return;
        }
        String owner = payload.getRepository().getOwner().getLogin();
        String repoName = payload.getRepository().getName();
        String featureBranch = payload.getPullRequest().getHead().getRef();
        PrWorkflowRun run = runRepository.findById(context.runId()).orElse(null);
        if (run == null) {
            log.warn("[Workflow '{}'] Cannot promote — run id {} not found", KEY, context.runId());
            return;
        }
        SuitePromotionService.Outcome out = suitePromotionService.promote(
                bot, run, suite, owner, repoName, featureBranch);
        context.appendStep("e2e-promotion", mode.key() + " — " + out.kind() + (
                out.message() == null ? "" : (": " + out.message())));
        postPrComment(bot, payload, suite.getPrNumber(),
                E2eTestSummaryRenderer.renderPromotion(mode, out));
    }

    private WorkflowResult mapOutcome(TestSuiteOutcome outcome) {
        return switch (outcome.status()) {
            case PASSED -> WorkflowResult.success(outcome.summary());
            case SKIPPED -> WorkflowResult.skipped(outcome.summary());
            case FAILED, ERROR -> WorkflowResult.failed(outcome.summary());
        };
    }

    private void postPrComment(Bot bot, WebhookPayload payload, long prNumber, String body) {
        if (payload.getRepository() == null || prNumber <= 0) {
            log.warn("[Workflow '{}'] Cannot post comment — payload lacks repository or pr number", KEY);
            return;
        }
        try {
            RepositoryApiClient client = giteaClientFactory.getApiClient(bot.getGitIntegration());
            String owner = payload.getRepository().getOwner() == null
                    ? null : payload.getRepository().getOwner().getLogin();
            String repoName = payload.getRepository().getName();
            client.postPullRequestComment(owner, repoName, prNumber, body);
        } catch (RuntimeException e) {
            log.warn("[Workflow '{}'] Failed to post PR comment: {}", KEY, e.getMessage(), e);
        }
    }

    private E2eTestFramework resolveFramework(Map<String, Object> params) {
        Object raw = params.get("framework");
        if (raw == null || raw.toString().isBlank()) {
            return DEFAULT_FRAMEWORK;
        }
        try {
            return E2eTestFramework.fromKey(raw.toString());
        } catch (IllegalArgumentException e) {
            log.warn("[Workflow '{}'] Unknown framework '{}' — falling back to {}",
                    KEY, raw, DEFAULT_FRAMEWORK);
            return DEFAULT_FRAMEWORK;
        }
    }

    private SuiteLifecycleMode resolveLifecycle(Map<String, Object> params) {
        Object raw = params.get("suiteLifecycle");
        if (raw == null || raw.toString().isBlank()) {
            return SuiteLifecycleMode.EPHEMERAL;
        }
        try {
            return SuiteLifecycleMode.fromKey(raw.toString());
        } catch (IllegalArgumentException e) {
            log.warn("[Workflow '{}'] Unknown suiteLifecycle '{}' — falling back to EPHEMERAL",
                    KEY, raw);
            return SuiteLifecycleMode.EPHEMERAL;
        }
    }

    private int intParam(Map<String, Object> params, String key, int fallback) {
        Object raw = params.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String headSha(WebhookPayload payload) {
        if (payload.getPullRequest() == null || payload.getPullRequest().getHead() == null) {
            return null;
        }
        return payload.getPullRequest().getHead().getSha();
    }

    /**
     * Resolves the pull-request number using the same fallback chain as
     * {@code PrWorkflowOrchestrator.resolvePrNumber}: PR object first,
     * then issue (for GitHub {@code issue_comment} events that lack the
     * pull-request block), then the top-level {@code number} field.
     */
    private long resolvePrNumber(WebhookPayload payload) {
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        if (payload.getNumber() != null) {
            return payload.getNumber();
        }
        return 0L;
    }
}




