package org.remus.giteabot.review.structured;

import tools.jackson.databind.JsonNode;

/**
 * One submission tool call as the model made it. {@code callId} is the provider's call
 * identifier, used for logging only — receipts are keyed by the explicit {@code operationId}
 * inside the arguments, which also covers a retry the provider issues under a new call id.
 */
public record ReviewToolCall(String tool, String callId, JsonNode arguments) {

    public static ReviewToolCall of(String tool, JsonNode arguments) {
        return new ReviewToolCall(tool, null, arguments);
    }
}
