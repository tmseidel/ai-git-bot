package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the AI usage audit log.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-usage")
public class AiUsageProperties {

    /**
     * Whether to capture and store raw AI request/response payloads in the
     * usage audit log. Disabled by default because the payloads can be large
     * and may contain sensitive prompt content.
     */
    private boolean rawPayloadsEnabled = false;

    /**
     * Maximum length of a raw request/response payload stored in the audit log.
     * Longer payloads are truncated to this length. {@code 0} or a negative value
     * stores payloads in full. The columns {@code ai_usage_log.raw_request} and
     * {@code ai_usage_log.raw_response} are {@code TEXT}, so any length fits.
     */
    private int maxRawPayloadLength = 65535;

    /**
     * Returns the effective maximum raw payload length: {@link Integer#MAX_VALUE}
     * (no truncation) when {@link #maxRawPayloadLength} is {@code 0} or negative.
     */
    public int getEffectiveMaxRawPayloadLength() {
        return maxRawPayloadLength <= 0 ? Integer.MAX_VALUE : maxRawPayloadLength;
    }
}
