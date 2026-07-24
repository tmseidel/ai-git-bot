package org.remus.giteabot.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.aiusage.AiErrorLogRepository;
import org.remus.giteabot.aiusage.AiUsageLogRepository;
import org.remus.giteabot.audit.AuditEventType;
import org.remus.giteabot.audit.PrAuditEventRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrometheusMetricsRegistrarTest {

    private MeterRegistry registry;
    private PrAuditEventRepository auditRepository;
    private AiUsageLogRepository usageRepository;
    private AiErrorLogRepository errorRepository;
    private PrometheusMetricsRegistrar registrar;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        auditRepository = mock(PrAuditEventRepository.class);
        usageRepository = mock(AiUsageLogRepository.class);
        errorRepository = mock(AiErrorLogRepository.class);
        registrar = new PrometheusMetricsRegistrar(registry, auditRepository, usageRepository, errorRepository);
    }

    @Test
    void registersReviewAndFindingGaugesFromAuditCounts() {
        when(auditRepository.countByEventType(AuditEventType.REVIEW_COMPLETED)).thenReturn(7L);
        when(auditRepository.countByEventType(AuditEventType.FINDING_POSTED)).thenReturn(5L);
        when(usageRepository.findDistinctAiIntegrationNames()).thenReturn(List.of());
        when(errorRepository.count()).thenReturn(0L);

        registrar.registerGauges();

        assertThat(gaugeValue("giteabot.reviews")).isEqualTo(7.0);
        assertThat(gaugeValue("giteabot.findings")).isEqualTo(5.0);
    }

    @Test
    void registersAiUsageGaugesPerIntegration() {
        when(auditRepository.countByEventType(AuditEventType.REVIEW_COMPLETED)).thenReturn(0L);
        when(auditRepository.countByEventType(AuditEventType.FINDING_POSTED)).thenReturn(0L);
        when(usageRepository.findDistinctAiIntegrationNames()).thenReturn(List.of("OpenAI", "anthropic"));
        when(usageRepository.sumInputTokensByAiIntegrationName("OpenAI")).thenReturn(1200L);
        when(usageRepository.sumOutputTokensByAiIntegrationName("OpenAI")).thenReturn(400L);
        when(usageRepository.sumInputTokensByAiIntegrationName("anthropic")).thenReturn(800L);
        when(usageRepository.sumOutputTokensByAiIntegrationName("anthropic")).thenReturn(200L);
        when(errorRepository.count()).thenReturn(0L);

        registrar.registerGauges();

        assertThat(gaugeValue("giteabot.ai_usage.input_tokens", "integration", "openai")).isEqualTo(1200.0);
        assertThat(gaugeValue("giteabot.ai_usage.output_tokens", "integration", "openai")).isEqualTo(400.0);
        assertThat(gaugeValue("giteabot.ai_usage.input_tokens", "integration", "anthropic")).isEqualTo(800.0);
        assertThat(gaugeValue("giteabot.ai_usage.output_tokens", "integration", "anthropic")).isEqualTo(200.0);
    }

    @Test
    void registersFallbackGaugeWhenNoUsageRecordsExist() {
        when(auditRepository.countByEventType(AuditEventType.REVIEW_COMPLETED)).thenReturn(0L);
        when(auditRepository.countByEventType(AuditEventType.FINDING_POSTED)).thenReturn(0L);
        when(usageRepository.findDistinctAiIntegrationNames()).thenReturn(List.of());
        when(errorRepository.count()).thenReturn(0L);

        registrar.registerGauges();

        assertThat(gaugeValue("giteabot.ai_usage.input_tokens", "integration", "unknown")).isEqualTo(0.0);
        assertThat(gaugeValue("giteabot.ai_usage.output_tokens", "integration", "unknown")).isEqualTo(0.0);
    }

    @Test
    void registersAiErrorGauge() {
        when(auditRepository.countByEventType(AuditEventType.REVIEW_COMPLETED)).thenReturn(0L);
        when(auditRepository.countByEventType(AuditEventType.FINDING_POSTED)).thenReturn(0L);
        when(usageRepository.findDistinctAiIntegrationNames()).thenReturn(List.of());
        when(errorRepository.count()).thenReturn(11L);

        registrar.registerGauges();

        assertThat(gaugeValue("giteabot.ai_errors")).isEqualTo(11.0);
    }

    @Test
    void registersAuditToolCallGauge() {
        when(auditRepository.countByEventType(AuditEventType.REVIEW_COMPLETED)).thenReturn(0L);
        when(auditRepository.countByEventType(AuditEventType.FINDING_POSTED)).thenReturn(0L);
        when(auditRepository.countByEventType(AuditEventType.TOOL_CALL_EXECUTED)).thenReturn(42L);
        when(usageRepository.findDistinctAiIntegrationNames()).thenReturn(List.of());
        when(errorRepository.count()).thenReturn(0L);

        registrar.registerGauges();

        assertThat(gaugeValue("giteabot.audit.tool_calls")).isEqualTo(42.0);
    }

    private double gaugeValue(String name, String... tags) {
        Gauge gauge = registry.find(name).tags(tags).gauge();
        assertThat(gauge).as("gauge %s with tags %s", name, List.of(tags)).isNotNull();
        return gauge.value();
    }
}
