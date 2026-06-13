package org.remus.giteabot.prworkflow.e2e.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.PrTestCase;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseRepository;
import org.remus.giteabot.prworkflow.e2e.PrTestCaseStatus;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.prworkflow.e2e.agents.TestAuthorAgent;
import org.remus.giteabot.prworkflow.e2e.agents.TestPlan;
import org.remus.giteabot.prworkflow.e2e.agents.TestPlanParser;
import org.remus.giteabot.prworkflow.e2e.agents.TestPlannerAgent;
import org.remus.giteabot.prworkflow.e2e.agents.TestRunnerAgent;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolContext;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * M4 wave 2 LLM-driven {@link TestSuiteRunner} for
 * {@link E2eTestFramework#PLAYWRIGHT}. Orchestrates the three E2E agents:
 *
 * <ol>
 *     <li>{@link TestPlannerAgent} — turns the PR diff into a {@link TestPlan}.</li>
 *     <li>{@link TestAuthorAgent} — materialises every journey via {@code pr-test-write}.</li>
 *     <li>{@link TestRunnerAgent} — executes the suite via {@code pr-test-run}
 *         and (optionally) attaches artefacts via {@code attach-artifact}.</li>
 * </ol>
 *
 * <p>Per-case statuses are written back into the database by the tool
 * executor; the rollup here just re-reads {@link PrTestCase} rows after the
 * runner agent returns and maps them onto a {@link TestSuiteOutcome}. The
 * runner intentionally does not throw — every failure path becomes a
 * structured {@link TestSuiteOutcome} so {@code E2ETestWorkflow} can post a
 * coherent PR comment.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaywrightTestSuiteRunner implements TestSuiteRunner {

    private final AiClientFactory aiClientFactory;
    private final GiteaClientFactory giteaClientFactory;
    private final TestPlannerAgent plannerAgent;
    private final TestAuthorAgent authorAgent;
    private final TestRunnerAgent runnerAgent;
    private final PrTestCaseRepository caseRepository;
    private final PrWorkflowToolExecutor toolExecutor;

    @Override
    public E2eTestFramework framework() {
        return E2eTestFramework.PLAYWRIGHT;
    }

    @Override
    public TestSuiteOutcome run(TestSuiteRequest request) {
        Bot bot = request.bot();
        if (bot == null || bot.getAiIntegration() == null) {
            return TestSuiteOutcome.skipped(
                    "PlaywrightTestSuiteRunner: bot has no AI integration configured");
        }
        AiClient aiClient;
        RepositoryApiClient apiClient;
        try {
            aiClient = aiClientFactory.getClient(bot.getAiIntegration());
            apiClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        } catch (RuntimeException e) {
            log.warn("PlaywrightTestSuiteRunner: client factory threw {}: {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return TestSuiteOutcome.error(
                    "Could not resolve AI/Repository client for bot '" + bot.getName()
                            + "': " + e.getMessage());
        }

        OwnerRepoPr addr = OwnerRepoPr.from(request.payload());

        // ── rerun-only path: restore previously generated test files, skip Planner+Author ──
        if (request.isRerunOnly()) {
            return runExistingTests(request, aiClient, apiClient, addr);
        }

        // ── full regenerate path ──
        String diff = "";
        try {
            if (addr.owner() != null && addr.repo() != null && addr.prNumber() != null) {
                diff = apiClient.getPullRequestDiff(addr.owner(), addr.repo(), addr.prNumber());
            }
        } catch (RuntimeException e) {
            log.warn("PlaywrightTestSuiteRunner: could not fetch PR diff: {}", e.getMessage());
            // Continue with empty diff — planner will simply produce no journeys.
        }

        TestPlannerAgent.PlannerInput plannerInput = new TestPlannerAgent.PlannerInput(
                request.payload().getPullRequest() == null ? "" :
                        valueOrEmpty(request.payload().getPullRequest().getTitle()),
                request.payload().getPullRequest() == null ? "" :
                        valueOrEmpty(request.payload().getPullRequest().getBody()),
                diff == null ? "" : diff,
                request.context() == null
                        ? ""
                        : valueOrEmpty(request.context().hint(PrWorkflowContext.HINT_E2E_FEEDBACK)));

        SystemPrompt systemPrompt = bot.getSystemPrompt();

        TestPlan plan = plannerAgent.plan(aiClient, request.framework(), plannerInput, systemPrompt).orElse(null);
        if (plan == null || plan.isEmpty()) {
            return TestSuiteOutcome.skipped(
                    "TestPlannerAgent returned no journeys — nothing to author or run");
        }
        // Cost guard: hard-cap journeys before authoring so we cannot exceed maxTestCases.
        if (plan.journeys().size() > request.maxTestCases()) {
            plan = TestPlanParser.capJourneys(plan, request.maxTestCases());
        }

        PrWorkflowToolContext toolContext = new PrWorkflowToolContext(
                request.suite(), request.workspace(), request.framework(),
                request.previewUrl(),
                addr.owner(), addr.repo(), addr.prNumber(),
                apiClient);

        TestAuthorAgent.Result authorResult = authorAgent.write(aiClient, toolContext, plan, systemPrompt);
        if (!authorResult.wroteAnything()) {
            return TestSuiteOutcome.error(
                    "TestAuthorAgent wrote zero files (budgetExhausted="
                            + authorResult.budgetExhausted() + ")");
        }

        int effectiveRetries = effectiveRetries(plan, request.maxRetries());
        TestRunnerAgent.Result runResult = runnerAgent.execute(aiClient, toolContext, plan, effectiveRetries, systemPrompt);

        // Aggregate per-case outcomes from the database — those are the source of truth.
        List<PrTestCase> cases = caseRepository.findBySuiteOrderByIdAsc(request.suite());
        int attempted = 0;
        int failed = 0;
        for (PrTestCase pc : cases) {
            if (pc.getLastStatus() == null || pc.getLastStatus() == PrTestCaseStatus.PENDING) {
                continue;
            }
            attempted++;
            if (pc.getLastStatus() == PrTestCaseStatus.FAILED
                    || pc.getLastStatus() == PrTestCaseStatus.ERROR) {
                failed++;
            }
        }

        if (attempted == 0) {
            return TestSuiteOutcome.error(
                    "TestRunnerAgent finished but no PrTestCase was executed (prTestRunInvocations="
                            + runResult.prTestRunInvocations() + ", budgetExhausted="
                            + runResult.budgetExhausted() + ")");
        }
        if (failed == 0) {
            String summary = attempted + "/" + cases.size() + " passed";
            if (runResult.attachedArtifacts() > 0) {
                summary += " · " + runResult.attachedArtifacts() + " artefact(s) attached";
            }
            return TestSuiteOutcome.passed(summary, attempted);
        }
        return TestSuiteOutcome.failed(
                failed + "/" + attempted + " failed", attempted, failed);
    }

    /**
     * Rerun-only path: restores all test files from the {@link PrTestSuite#getCases()
     * cases} of {@code request.previousSuite()} into the new workspace, clones the
     * {@link PrTestCase} rows into the freshly-created suite so the tool-executor's
     * post-run status matcher can find them, then invokes {@code pr-test-run}
     * directly via {@link PrWorkflowToolExecutor} — no LLM round-trip, no
     * planner / author / runner agent. The operator asked for a deterministic
     * rerun, so we run the exact same files and only refresh statuses.
     */
    private TestSuiteOutcome runExistingTests(TestSuiteRequest request,
                                              AiClient aiClient,
                                              RepositoryApiClient apiClient,
                                              OwnerRepoPr addr) {
        PrTestSuite previousSuite = request.previousSuite();
        List<PrTestCase> previousCases = caseRepository.findBySuiteOrderByIdAsc(previousSuite);
        if (previousCases.isEmpty()) {
            return TestSuiteOutcome.skipped(
                    "rerun-only: previous suite #" + previousSuite.getId()
                            + " has no persisted test cases — cannot rerun");
        }

        // 1) Restore each test file from the DB into the new workspace directory.
        // 2) Clone the PrTestCase row into the NEW suite (status=PENDING) so the
        //    pr-test-run tool's per-case status matcher (looks up by suite+path)
        //    can update them after the playwright run — otherwise the new suite
        //    has zero cases and the run is reported as "no tests executed".
        int restored = 0;
        for (PrTestCase pc : previousCases) {
            if (pc.getContent() == null || pc.getPath() == null) continue;
            try {
                Path dest = request.workspace().resolve(pc.getPath());
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, pc.getContent());
                restored++;
            } catch (IOException e) {
                log.warn("PlaywrightTestSuiteRunner rerun: could not restore '{}': {}",
                        pc.getPath(), e.getMessage());
                continue;
            }
            PrTestCase clone = new PrTestCase();
            clone.setSuite(request.suite());
            clone.setPath(pc.getPath());
            clone.setTitle(pc.getTitle());
            clone.setContent(pc.getContent());
            clone.setLastStatus(PrTestCaseStatus.PENDING);
            caseRepository.save(clone);
        }
        if (restored == 0) {
            return TestSuiteOutcome.error(
                    "rerun-only: failed to restore any test files from suite #"
                            + previousSuite.getId() + " into workspace");
        }
        log.info("PlaywrightTestSuiteRunner rerun: restored {}/{} test files from suite #{} into new suite #{} (no LLM)",
                restored, previousCases.size(), previousSuite.getId(), request.suite().getId());

        PrWorkflowToolContext toolContext = new PrWorkflowToolContext(
                request.suite(), request.workspace(), request.framework(),
                request.previewUrl(),
                addr.owner(), addr.repo(), addr.prNumber(),
                apiClient);

        // Directly invoke pr-test-run — the LLM-driven TestRunnerAgent is
        // intentionally skipped for rerun-only: nothing to discover, nothing to
        // author, nothing to "discuss". Just execute and aggregate.
        String toolResult = toolExecutor.execute(
                "pr-test-run", Map.of("framework", request.framework().key()), toolContext);
        log.debug("PlaywrightTestSuiteRunner rerun: pr-test-run result: {}", toolResult);

        List<PrTestCase> cases = caseRepository.findBySuiteOrderByIdAsc(request.suite());
        int attempted = 0;
        int failed = 0;
        for (PrTestCase pc : cases) {
            if (pc.getLastStatus() == null || pc.getLastStatus() == PrTestCaseStatus.PENDING) {
                continue;
            }
            attempted++;
            if (pc.getLastStatus() == PrTestCaseStatus.FAILED
                    || pc.getLastStatus() == PrTestCaseStatus.ERROR) {
                failed++;
            }
        }
        if (attempted == 0) {
            return TestSuiteOutcome.error(
                    "rerun: pr-test-run finished but no PrTestCase status was updated — "
                            + "tool output: " + abbreviate(toolResult, 400));
        }
        if (failed == 0) {
            return TestSuiteOutcome.passed(attempted + "/" + cases.size() + " passed (rerun)", attempted);
        }
        return TestSuiteOutcome.failed(failed + "/" + attempted + " failed (rerun)", attempted, failed);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int effectiveRetries(TestPlan plan, int workflowDefault) {        Integer fromPlan = plan.maxRetries();
        if (fromPlan == null) return workflowDefault;
        return Math.max(0, Math.min(workflowDefault, fromPlan));
    }

    private static String valueOrEmpty(String s) {
        return s == null ? "" : s;
    }

    private record OwnerRepoPr(String owner, String repo, Long prNumber) {
        static OwnerRepoPr from(WebhookPayload payload) {
            if (payload == null) return new OwnerRepoPr(null, null, null);
            String owner = payload.getRepository() == null || payload.getRepository().getOwner() == null
                    ? null : payload.getRepository().getOwner().getLogin();
            String repo  = payload.getRepository() == null ? null : payload.getRepository().getName();
            Long pr = payload.getPullRequest() == null ? null : payload.getPullRequest().getNumber();
            return new OwnerRepoPr(owner, repo, pr);
        }
    }
}



