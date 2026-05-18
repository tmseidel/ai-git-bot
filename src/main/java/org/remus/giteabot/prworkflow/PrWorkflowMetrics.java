package org.remus.giteabot.prworkflow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Micrometer metrics for the {@link PrWorkflowOrchestrator}, exposed at
 * {@code /actuator/prometheus} alongside the existing agent telemetry.
 *
 * <ul>
 *     <li>{@code prworkflow.run_total{workflow,status}} — one increment per
 *     completed run (terminal status).</li>
 *     <li>{@code prworkflow.run_duration_seconds{workflow}} — wall-clock
 *     duration for terminal runs.</li>
 * </ul>
 *
 * <p>Modelled after {@link org.remus.giteabot.agent.shared.AgentMetrics}.</p>
 */
@Component
public class PrWorkflowMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> runCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> runTimers = new ConcurrentHashMap<>();

    @Autowired
    public PrWorkflowMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRun(String workflowKey, PrWorkflowRunStatus status, Duration duration) {
        String safeKey = normalise(workflowKey);
        String safeStatus = normalise(status == null ? null : status.name());
        runCounters.computeIfAbsent(safeKey + "|" + safeStatus, ignored ->
                Counter.builder("prworkflow.run_total")
                        .description("Number of completed PR-workflow runs per workflow and status")
                        .tag("workflow", safeKey)
                        .tag("status", safeStatus)
                        .register(meterRegistry)
        ).increment();
        if (duration != null) {
            runTimers.computeIfAbsent(safeKey, ignored ->
                    Timer.builder("prworkflow.run_duration_seconds")
                            .description("Wall-clock duration of a PR-workflow run")
                            .tag("workflow", safeKey)
                            .register(meterRegistry)
            ).record(duration);
        }
    }

    private String normalise(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

