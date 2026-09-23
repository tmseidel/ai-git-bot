package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Retry behaviour for AI provider calls that fail because the provider itself
 * is overloaded — HTTP 503/529, {@code "status": "UNAVAILABLE"},
 * {@code overloaded_error} or "high demand" messages. These spikes are
 * temporary, so the call is repeated with exponential backoff instead of
 * failing the whole review.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-retry")
public class AiRetryProperties {

    /** Master switch for the provider-overload retry. */
    private boolean enabled = true;

    /** Attempt budget per AI call, including the first attempt. */
    private int maxAttempts = 5;

    /** Wait before the second attempt. */
    private Duration initialDelay = Duration.ofSeconds(10);

    /** Factor applied to the wait after every further failed attempt. */
    private double multiplier = 2.0;

    /** Upper bound for a single wait. */
    private Duration maxDelay = Duration.ofSeconds(60);

    /**
     * Relative random spread applied to each wait (0.2 = ±20%), so a fleet of
     * bots does not hit a recovering provider in lockstep.
     */
    private double jitter = 0.2;

    /**
     * Minimum gap between two "retry scheduled" comments within one workflow
     * run. Prevents a long outage from filling the PR/issue with one comment
     * per failed AI call.
     */
    private Duration noticeCooldown = Duration.ofMinutes(5);
}
