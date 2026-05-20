package org.remus.giteabot.prworkflow.e2e.runner;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteRunnerRegistryTest {

    @Test
    void indexesByFrameworkAndIsLookupable() {
        TestSuiteRunner playwright = stub(E2eTestFramework.PLAYWRIGHT, "pw");
        TestSuiteRunner pytest = stub(E2eTestFramework.PYTEST, "py");
        TestSuiteRunnerRegistry registry = new TestSuiteRunnerRegistry(List.of(playwright, pytest));

        assertThat(registry.find(E2eTestFramework.PLAYWRIGHT)).contains(playwright);
        assertThat(registry.find(E2eTestFramework.PYTEST)).contains(pytest);
        assertThat(registry.find(E2eTestFramework.K6)).isEmpty();
        assertThat(registry.describe()).contains("PLAYWRIGHT=").contains("PYTEST=");
    }

    @Test
    void rejectsDuplicateFrameworkRegistration() {
        TestSuiteRunner a = stub(E2eTestFramework.PLAYWRIGHT, "a");
        TestSuiteRunner b = stub(E2eTestFramework.PLAYWRIGHT, "b");
        assertThatThrownBy(() -> new TestSuiteRunnerRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate TestSuiteRunner");
    }

    @Test
    void rejectsNullFramework() {
        TestSuiteRunner bad = stub(null, "bad");
        assertThatThrownBy(() -> new TestSuiteRunnerRegistry(List.of(bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null framework");
    }

    private static TestSuiteRunner stub(E2eTestFramework f, String summary) {
        return new TestSuiteRunner() {
            @Override public E2eTestFramework framework() { return f; }
            @Override public TestSuiteOutcome run(TestSuiteRequest request) {
                return TestSuiteOutcome.skipped(summary);
            }
        };
    }
}
