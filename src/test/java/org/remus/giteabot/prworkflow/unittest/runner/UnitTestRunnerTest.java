package org.remus.giteabot.prworkflow.unittest.runner;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.prworkflow.unittest.UnitTestCase;
import org.remus.giteabot.prworkflow.unittest.UnitTestCaseRepository;
import org.remus.giteabot.prworkflow.unittest.UnitTestFramework;
import org.remus.giteabot.prworkflow.unittest.UnitTestSuite;
import org.remus.giteabot.prworkflow.unittest.coverage.CoverageParser;
import org.remus.giteabot.prworkflow.unittest.coverage.CoverageResult;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnitTestRunnerTest {

    private final ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
    private final UnitTestCaseRepository caseRepository = mock(UnitTestCaseRepository.class);
    private final CoverageParser coverageParser = mock(CoverageParser.class);

    private final UnitTestRunner runner =
            new UnitTestRunner(toolExecutionService, caseRepository, coverageParser);

    private UnitTestRunRequest request(UnitTestFramework framework) {
        UnitTestSuite suite = new UnitTestSuite();
        suite.setFramework(framework);
        return new UnitTestRunRequest(suite, Workspace.unmanaged(Path.of(".")), framework, 0, null);
    }

    private UnitTestCase aCase() {
        UnitTestCase c = new UnitTestCase();
        c.setPath("src/test/java/FooTest.java");
        c.setContent("x");
        return c;
    }

    @Test
    void skipsWhenNoCases() {
        when(caseRepository.findBySuiteOrderByIdAsc(any())).thenReturn(List.of());
        UnitTestOutcome outcome = runner.run(request(UnitTestFramework.MAVEN));
        assertThat(outcome.status()).isEqualTo(UnitTestOutcomeStatus.SKIPPED);
    }

    @Test
    void passesWhenExitZero() {
        when(caseRepository.findBySuiteOrderByIdAsc(any())).thenReturn(List.of(aCase()));
        when(toolExecutionService.executeTool(any(), any(), any()))
                .thenReturn(new ToolResult(true, 0, "BUILD SUCCESS", ""));
        when(coverageParser.parse(any(), any(), any())).thenReturn(CoverageResult.of(80, 100));

        UnitTestOutcome outcome = runner.run(request(UnitTestFramework.MAVEN));

        assertThat(outcome.status()).isEqualTo(UnitTestOutcomeStatus.PASSED);
        assertThat(outcome.attempted()).isEqualTo(1);
        assertThat(outcome.failed()).isZero();
        assertThat(outcome.coverage().known()).isTrue();
    }

    @Test
    void failsWhenExitNonZero() {
        when(caseRepository.findBySuiteOrderByIdAsc(any())).thenReturn(List.of(aCase()));
        when(toolExecutionService.executeTool(any(), any(), any()))
                .thenReturn(new ToolResult(false, 1, "BUILD FAILURE", ""));
        when(coverageParser.parse(any(), any(), any())).thenReturn(CoverageResult.unknown());

        UnitTestOutcome outcome = runner.run(request(UnitTestFramework.MAVEN));

        assertThat(outcome.status()).isEqualTo(UnitTestOutcomeStatus.FAILED);
        assertThat(outcome.failed()).isEqualTo(1);
    }

    @Test
    void usesFrameworkSpecificCommand() {
        when(caseRepository.findBySuiteOrderByIdAsc(any())).thenReturn(List.of(aCase()));
        when(toolExecutionService.executeTool(any(), eq("cargo"), eq(List.of("test", "--quiet"))))
                .thenReturn(new ToolResult(true, 0, "ok", ""));
        when(coverageParser.parse(any(), any(), any())).thenReturn(CoverageResult.unknown());

        UnitTestOutcome outcome = runner.run(request(UnitTestFramework.CARGO));

        assertThat(outcome.status()).isEqualTo(UnitTestOutcomeStatus.PASSED);
    }
}

