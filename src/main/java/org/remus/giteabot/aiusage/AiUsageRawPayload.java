package org.remus.giteabot.aiusage;

/** The raw request and response of one {@link AiUsageLog} entry; either may be {@code null}. */
public record AiUsageRawPayload(String rawRequest, String rawResponse) {
}
