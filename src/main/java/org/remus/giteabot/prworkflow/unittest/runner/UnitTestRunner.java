package org.remus.giteabot.prworkflow.unittest.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.prworkflow.unittest.UnitTestCase;
import org.remus.giteabot.prworkflow.unittest.UnitTestCaseRepository;
import org.remus.giteabot.prworkflow.unittest.UnitTestCaseStatus;
import org.remus.giteabot.prworkflow.unittest.UnitTestFramework;
import org.remus.giteabot.prworkflow.unittest.coverage.CoverageParser;
import org.remus.giteabot.prworkflow.unittest.coverage.CoverageResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Executes one generated unit-test suite with the project's own test runner and
 * reports a structured {@link UnitTestOutcome}.
 *
 * <p>This is a single framework-agnostic runner: the {@link UnitTestFramework}
 * carried by the {@link UnitTestRunRequest} supplies the command line, so adding
 * support for a new toolchain is just a new {@code UnitTestFramework} enum
 * value — no extra runner class. The command is executed inside the repository
 * checkout through the agent's existing {@link ToolExecutionService}, i.e. the
 * very same mechanism the coding agent ({@code IssueImplementationService}) uses
 * to invoke build/test tools. That keeps tool allow-listing
 * ({@code validation.available-tools}), timeout
 * ({@code validation.tool-timeout-seconds}) and output handling in one place.</p>
 *
 * <p>An aggregate pass/fail is derived from the tool exit code, the per-case
 * statuses are refreshed and a best-effort coverage snapshot is attached.
 * Per-case granularity is intentionally coarse for the MVP: a green run marks
 * every generated case {@code PASSED}; a red run marks them {@code FAILED}. The
 * detailed runner log is preserved on each case and in the PR comment.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnitTestRunner {

    private final ToolExecutionService toolExecutionService;
    private final UnitTestCaseRepository caseRepository;
    private final CoverageParser coverageParser;

    public UnitTestOutcome run(UnitTestRunRequest request) {
        UnitTestFramework framework = request.framework();
        List<UnitTestCase> cases = caseRepository.findBySuiteOrderByIdAsc(request.suite());
        if (cases.isEmpty()) {
            return UnitTestOutcome.skipped("No generated unit-test cases to run");
        }

        List<String> command = framework.defaultRunCommand();
        String tool = command.getFirst();
        List<String> args = command.subList(1, command.size());

        long start = System.nanoTime();
        ToolResult result = toolExecutionService.executeTool(request.workspace(), tool, args);

        // Optional single retry of the whole suite when it failed and a retry
        // budget is configured — flaky environments (port clashes, first-run
        // dependency downloads) often go green on a second attempt.
        if (!result.success() && request.maxRetries() > 0) {
            log.info("{} runner failed (exit={}), retrying once", framework.key(), result.exitCode());
            ToolResult retry = toolExecutionService.executeTool(request.workspace(), tool, args);
            if (retry.success()) {
                result = retry;
            }
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000L;

        boolean green = result.success();
        int attempted = cases.size();
        UnitTestCaseStatus status = green ? UnitTestCaseStatus.PASSED : UnitTestCaseStatus.FAILED;
        String combinedOutput = combine(result);
        persistCaseStatuses(cases, status, combinedOutput, durationMs);

        CoverageResult coverage = coverageParser.parse(request.workspace(), framework, combinedOutput);

        String summary = green
                ? attempted + "/" + attempted + " passed"
                : "runner exited with code " + result.exitCode();

        return green
                ? UnitTestOutcome.passed(summary, attempted, coverage, combinedOutput)
                : UnitTestOutcome.failed(summary, attempted, attempted, coverage, combinedOutput);
    }

    private static String combine(ToolResult result) {
        String output = result.output() != null ? result.output() : "";
        String error = result.error() != null ? result.error() : "";
        if (error.isBlank()) {
            return output;
        }
        return output.isBlank() ? error : output + "\n" + error;
    }

    private void persistCaseStatuses(List<UnitTestCase> cases, UnitTestCaseStatus status,
                                     String combinedOutput, long durationMs) {
        Instant now = Instant.now();
        String log = truncate(combinedOutput, 8 * 1024);
        for (UnitTestCase c : cases) {
            c.setLastStatus(status);
            c.setLastRunAt(now);
            c.setLastDurationMs(durationMs);
            c.setLastLog(log);
            caseRepository.save(c);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)";
    }
}

