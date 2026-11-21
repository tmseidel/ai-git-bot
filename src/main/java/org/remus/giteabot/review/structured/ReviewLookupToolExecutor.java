package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.ToolResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Serves {@code review-lookup}: read-only access to the reviews this bot published on the run's pull
 * request.
 *
 * <p>It exists because a follow-up starts with no conversation history. Handing every follow-up a
 * digest of the newest reviews would cost tokens on each run and still miss older ones; instead the
 * agent asks for exactly what the user cited — a reference, or a text search — and gets only that
 * back. The scope comes from {@link ReviewLookupInvocation}, so arguments can select a reference but
 * never a repository.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewLookupToolExecutor {

    public static final String LOOKUP = "review-lookup";

    private final PublishedReviewLookup lookup;

    public boolean isLookupTool(String tool) {
        return LOOKUP.equals(tool == null ? null : tool.trim());
    }

    public ToolResult execute(ReviewLookupInvocation invocation, ReviewToolCall call) {
        if (!isLookupTool(call.tool())) {
            return failed("Tool '" + call.tool() + "' is not a review lookup");
        }
        if (invocation == null) {
            return failed("Review lookup is not available in this run");
        }
        if (invocation.isCancelled()) {
            return failed("The run was cancelled; no further lookups are served");
        }
        String reference = text(call.arguments(), "reference");
        String query = text(call.arguments(), "query");
        try {
            String result;
            if (reference != null) {
                result = lookup.byReference(invocation.gitIntegrationId(), invocation.botId(),
                        invocation.repoOwner(), invocation.repoName(), invocation.prNumber(),
                        reference);
            } else if (query != null) {
                result = lookup.search(invocation.gitIntegrationId(), invocation.botId(),
                        invocation.repoOwner(), invocation.repoName(), invocation.prNumber(), query);
            } else {
                result = lookup.list(invocation.gitIntegrationId(), invocation.botId(),
                        invocation.repoOwner(), invocation.repoName(), invocation.prNumber());
            }
            log.debug("Review lookup served: reference={} query={}", reference, query);
            return new ToolResult(true, 0, result, "");
        } catch (RuntimeException e) {
            log.warn("Review lookup failed: {}", e.getMessage());
            return failed("The stored reviews could not be read: " + e.getMessage());
        }
    }

    private static ToolResult failed(String message) {
        return new ToolResult(false, -1, "", message);
    }

    private static String text(JsonNode arguments, String field) {
        if (arguments == null) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString("").trim();
        return value.isEmpty() ? null : value;
    }
}
