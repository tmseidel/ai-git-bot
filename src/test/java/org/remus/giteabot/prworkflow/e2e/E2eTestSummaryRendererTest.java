package org.remus.giteabot.prworkflow.e2e;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.e2e.runner.TestSuiteOutcome;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class E2eTestSummaryRendererTest {

    @Test
    void rendersFailedRunWithCaseTable() {
        PrTestSuite suite = baseSuite();
        suite.addCase(passingCase("tests/login.spec.ts", "Sign-in happy path", 1234L));
        suite.addCase(failingCase("tests/checkout.spec.ts", "Add to cart and pay", 4567L));

        String md = E2eTestSummaryRenderer.render(
                suite,
                TestSuiteOutcome.failed("1 of 2 failed", 2, 1),
                "https://preview-42.example.com");

        assertThat(md)
                .contains("## E2E Test Run for PR #42")
                .contains("`playwright`")
                .contains("https://preview-42.example.com")
                .contains("`abc12345`")
                .contains("❌ FAILED")
                .contains("(1/2 passed)")
                .contains("| `tests/login.spec.ts`")
                .contains("Sign-in happy path")
                .contains("| ✅ PASSED")
                .contains("1.23s")
                .contains("| `tests/checkout.spec.ts`")
                .contains("| ❌ FAILED")
                .contains("4.57s")
                .contains("1 of 2 failed");
    }

    @Test
    void rendersSkippedWhenNoCases() {
        PrTestSuite suite = baseSuite();
        String md = E2eTestSummaryRenderer.render(suite,
                TestSuiteOutcome.skipped("agentic runner not enabled"),
                "https://preview.example.com");
        assertThat(md)
                .contains("⏭️ SKIPPED")
                .contains("No test cases were generated")
                .contains("agentic runner not enabled");
    }

    @Test
    void renderSkippedAndFailedHelpers() {
        assertThat(E2eTestSummaryRenderer.renderSkipped(7L, "no target"))
                .startsWith("## E2E Test Run for PR #7")
                .contains("⏭️")
                .contains("no target");
        assertThat(E2eTestSummaryRenderer.renderFailed(8L, "boom"))
                .contains("❌")
                .contains("boom");
    }

    @Test
    void escapesPipeCharactersInCasePaths() {
        PrTestSuite suite = baseSuite();
        suite.addCase(passingCase("tests/weird|name.spec.ts", null, 50L));

        String md = E2eTestSummaryRenderer.render(suite,
                TestSuiteOutcome.passed("ok", 1), "https://x");

        assertThat(md).contains("tests/weird\\|name.spec.ts");
    }

    private PrTestSuite baseSuite() {
        PrTestSuite s = new PrTestSuite();
        s.setPrNumber(42L);
        s.setFramework(E2eTestFramework.PLAYWRIGHT);
        s.setSourceTreeRef("abc12345deadbeef");
        s.setCreatedAt(Instant.parse("2026-05-19T10:00:00Z"));
        return s;
    }

    private PrTestCase passingCase(String path, String title, long durationMs) {
        PrTestCase c = new PrTestCase();
        c.setPath(path);
        c.setTitle(title);
        c.setContent("// generated");
        c.setLastStatus(PrTestCaseStatus.PASSED);
        c.setLastDurationMs(durationMs);
        return c;
    }

    private PrTestCase failingCase(String path, String title, long durationMs) {
        PrTestCase c = passingCase(path, title, durationMs);
        c.setLastStatus(PrTestCaseStatus.FAILED);
        return c;
    }
}
