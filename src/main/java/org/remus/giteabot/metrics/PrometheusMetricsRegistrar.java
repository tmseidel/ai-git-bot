package org.remus.giteabot.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.aiusage.AiErrorLogRepository;
import org.remus.giteabot.aiusage.AiUsageLogRepository;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Registers Prometheus gauges derived from persisted audit data.
 *
 * <p>These gauges are read-only, never throw, and aggregate away all
 * high-cardinality dimensions (repository, PR number, session id, etc.).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "management.endpoint.prometheus.enabled", havingValue = "true")
public class PrometheusMetricsRegistrar {

    private static final String UNKNOWN_INTEGRATION = "unknown";

    private final MeterRegistry meterRegistry;
    private final PrAuditEventRepository auditRepository;
    private final AiUsageLogRepository usageRepository;
    private final AiErrorLogRepository errorRepository;

    @PostConstruct
    void registerGauges() {
        registerReviewGauges();
        registerAiUsageGauges();
        registerAiErrorGauge();
        registerAuditToolCallGauge();
        log.info("Registered Prometheus DB-derived gauges");
    }

    private void registerReviewGauges() {
        Gauge.builder("giteabot.reviews", auditRepository,
                        r -> r.countByEventType(AuditEventType.REVIEW_COMPLETED))
                .description("Total completed reviews")
                .register(meterRegistry);

        Gauge.builder("giteabot.findings", auditRepository,
                        r -> r.countByEventType(AuditEventType.FINDING_POSTED))
                .description("Total findings posted (v1: one finding event per posted review)")
                .register(meterRegistry);
    }

    private void registerAiUsageGauges() {
        List<String> integrations = usageRepository.findDistinctAiIntegrationNames();
        if (integrations.isEmpty()) {
            // Register a zero-value gauge for the unknown integration so the metric
            // name is discoverable before the first usage record is written.
            registerTokenGauges(UNKNOWN_INTEGRATION, UNKNOWN_INTEGRATION);
        }
        for (String integration : integrations) {
            String tagValue = normalise(integration);
            registerTokenGauges(integration, tagValue);
        }
    }

    private void registerTokenGauges(String integrationName, String tagValue) {
        Gauge.builder("giteabot.ai_usage.input_tokens", usageRepository,
                        r -> safeLong(() -> r.sumInputTokensByAiIntegrationName(integrationName)))
                .description("Total input tokens consumed per AI integration")
                .tag("integration", tagValue)
                .register(meterRegistry);

        Gauge.builder("giteabot.ai_usage.output_tokens", usageRepository,
                        r -> safeLong(() -> r.sumOutputTokensByAiIntegrationName(integrationName)))
                .description("Total output tokens consumed per AI integration")
                .tag("integration", tagValue)
                .register(meterRegistry);
    }

    private void registerAiErrorGauge() {
        Gauge.builder("giteabot.ai_errors", errorRepository, AiErrorLogRepository::count)
                .description("Total AI provider errors")
                .register(meterRegistry);
    }

    private void registerAuditToolCallGauge() {
        Gauge.builder("giteabot.audit.tool_calls", auditRepository,
                        r -> r.countByEventType(AuditEventType.TOOL_CALL_EXECUTED))
                .description("Total tool calls recorded in the audit trail")
                .register(meterRegistry);
    }

    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN_INTEGRATION;
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static long safeLong(java.util.function.Supplier<Long> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Failed to read persisted metric value: {}", e.getMessage());
            return 0L;
        }
    }
}
