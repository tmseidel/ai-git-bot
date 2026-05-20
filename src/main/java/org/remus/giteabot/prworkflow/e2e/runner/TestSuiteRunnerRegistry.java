package org.remus.giteabot.prworkflow.e2e.runner;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Looks up the {@link TestSuiteRunner} responsible for a given
 * {@link E2eTestFramework}. Auto-discovered Spring beans are validated for
 * uniqueness at construction time — two runners advertising the same
 * framework are a hard startup failure, mirroring
 * {@code DeploymentStrategyRegistry}.
 */
@Slf4j
@Component
public class TestSuiteRunnerRegistry {

    private final java.util.Map<E2eTestFramework, TestSuiteRunner> runnersByFramework;

    public TestSuiteRunnerRegistry(List<TestSuiteRunner> runners) {
        java.util.Map<E2eTestFramework, TestSuiteRunner> index = new java.util.EnumMap<>(E2eTestFramework.class);
        for (TestSuiteRunner runner : runners) {
            E2eTestFramework framework = runner.framework();
            if (framework == null) {
                throw new IllegalStateException(
                        "TestSuiteRunner " + runner.getClass().getName() + " returned null framework()");
            }
            TestSuiteRunner existing = index.putIfAbsent(framework, runner);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate TestSuiteRunner for framework " + framework
                                + ": " + existing.getClass().getName()
                                + " vs " + runner.getClass().getName());
            }
            log.info("Registered TestSuiteRunner for {} → {}", framework, runner.getClass().getSimpleName());
        }
        this.runnersByFramework = java.util.Collections.unmodifiableMap(index);
    }

    public Optional<TestSuiteRunner> find(E2eTestFramework framework) {
        return Optional.ofNullable(runnersByFramework.get(framework));
    }

    /** Diagnostic: which frameworks have a registered runner right now. */
    public String describe() {
        return runnersByFramework.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().getClass().getSimpleName())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
