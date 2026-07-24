package org.remus.giteabot.agent.shared;

import java.time.Duration;

/**
 * Process-wide singleton holder for {@link AgentMetrics}.
 *
 * <p>Mirrors the {@link AgentSchemaValidatorHolder} pattern so that
 * {@link org.remus.giteabot.agent.loop.AgentLoop} (constructed with
 * {@code new}) can publish metrics without a Spring dependency. When the
 * holder is empty (unit tests, non-Spring callers) the helper methods are
 * no-ops.</p>
 */
public final class AgentMetricsHolder {

    private static volatile AgentMetrics instance;

    private AgentMetricsHolder() {
    }

    static void set(AgentMetrics metrics) {
        instance = metrics;
    }

    public static AgentMetrics get() {
        return instance;
    }

    public static void recordToolCallMode(String mode, String provider) {
        AgentMetrics m = instance;
        if (m != null) {
            m.recordToolCallMode(mode, provider);
        }
    }


    public static void recordLatency(String mode, String provider, Duration duration) {
        AgentMetrics m = instance;
        if (m != null) {
            m.recordLatency(mode, provider, duration);
        }
    }

    public static void recordCriticOutcome(String outcome) {
        AgentMetrics m = instance;
        if (m != null) {
            m.recordCriticOutcome(outcome);
        }
    }

    public static void recordToolCall(String provider) {
        AgentMetrics m = instance;
        if (m != null) {
            m.recordToolCall(provider);
        }
    }
}

