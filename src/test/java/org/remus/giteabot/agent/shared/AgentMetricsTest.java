package org.remus.giteabot.agent.shared;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    private SimpleMeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
    }

    @Test
    void recordToolCallMode_incrementsTaggedCounter() {
        metrics.recordToolCallMode("native", "openaiclient");
        metrics.recordToolCallMode("native", "openaiclient");
        metrics.recordToolCallMode("legacy", "ollamaclient");

        Counter native_ = registry.find("agent.tool_call.mode_total")
                .tag("mode", "native").tag("provider", "openaiclient").counter();
        Counter legacy = registry.find("agent.tool_call.mode_total")
                .tag("mode", "legacy").tag("provider", "ollamaclient").counter();

        assertThat(native_).isNotNull();
        assertThat(native_.count()).isEqualTo(2.0);
        assertThat(legacy).isNotNull();
        assertThat(legacy.count()).isEqualTo(1.0);
    }

    @Test
    void recordParseFailure_incrementsTaggedCounter() {
        metrics.recordParseFailure("anthropicaiclient");
        Counter c = registry.find("agent.tool_call.parse_failures_total")
                .tag("provider", "anthropicaiclient").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void recordLatency_publishesTimerSample() {
        metrics.recordLatency("native", "openaiclient", Duration.ofMillis(125));
        Timer timer = registry.find("agent.tool_call.latency_seconds")
                .tag("mode", "native").tag("provider", "openaiclient").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(125.0);
    }

    @Test
    void normalisation_handlesNullsAndCase() {
        metrics.recordToolCallMode(null, null);
        Counter unknown = registry.find("agent.tool_call.mode_total")
                .tag("mode", "unknown").tag("provider", "unknown").counter();
        assertThat(unknown).isNotNull();
        assertThat(unknown.count()).isEqualTo(1.0);

        metrics.recordToolCallMode("NATIVE", "OpenAiClient");
        Counter mixed = registry.find("agent.tool_call.mode_total")
                .tag("mode", "native").tag("provider", "openaiclient").counter();
        assertThat(mixed).isNotNull();
        assertThat(mixed.count()).isEqualTo(1.0);
    }

    @Test
    void holder_returnsRegisteredInstance_andStaticHelpersDelegate() {
        metrics.publishHolder();
        assertThat(AgentMetricsHolder.get()).isSameAs(metrics);

        AgentMetricsHolder.recordToolCallMode("legacy", "googleaiclient");
        Counter c = registry.find("agent.tool_call.mode_total")
                .tag("mode", "legacy").tag("provider", "googleaiclient").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void recordToolCall_incrementsTaggedCounter() {
        metrics.recordToolCall("anthropicaiclient");
        metrics.recordToolCall("anthropicaiclient");
        Counter c = registry.find("agent.tool_calls_total")
                .tag("provider", "anthropicaiclient").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    void recordToolCall_normalisesNullProvider() {
        metrics.recordToolCall(null);
        Counter c = registry.find("agent.tool_calls_total")
                .tag("provider", "unknown").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }
}

