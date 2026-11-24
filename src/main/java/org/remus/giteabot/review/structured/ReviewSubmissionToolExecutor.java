package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolCallAudit;
import org.remus.giteabot.util.UrlRedaction;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Executes the agentic-review submission protocol: the five tools that mutate only the current
 * review draft.
 *
 * <p>Everything the operation needs is trusted: the draft, its repository, bot, run and attempt
 * come from the {@link ReviewInvocation} the review service built, never from tool arguments, so
 * a call cannot reach another review or another repository. Each mutating call is idempotent per
 * {@code operationId}: an exact retry replays its stored receipt (even after the review was
 * finalized), while the same id with different arguments is refused with the current draft
 * revision so the model can re-read the draft instead of guessing.</p>
 *
 * <p>A tool batch executes in order and yields one result per call. {@code review-complete} is
 * only accepted alone: a mixed batch is refused without executing anything, so completion can
 * never run before the finding and assessment receipts of its own batch were observed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewSubmissionToolExecutor {

    public static final String UPSERT_FINDING = "review-upsert-finding";
    public static final String REMOVE_FINDING = "review-remove-finding";
    public static final String GET_DRAFT = "review-get-draft";
    public static final String SET_ASSESSMENT = "review-set-assessment";
    public static final String COMPLETE = "review-complete";

    public static final Set<String> TOOL_NAMES =
            Set.of(UPSERT_FINDING, REMOVE_FINDING, GET_DRAFT, SET_ASSESSMENT, COMPLETE);

    static final int MAX_OPERATION_ID_CHARS = 128;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REVISIT_HINT =
            "Call review-get-draft to re-read the current draft, then retry.";

    private final StructuredReviewService reviewService;
    private final ReviewToolOperationService operationService;

    public boolean isSubmissionTool(String tool) {
        return TOOL_NAMES.contains(normalize(tool));
    }

    /** A model batch: validated as a whole, then executed call by call in the given order. */
    public List<ToolResult> executeBatch(ReviewInvocation invocation, List<ReviewToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        boolean completes = calls.stream().anyMatch(call -> COMPLETE.equals(normalize(call.tool())));
        if (completes && calls.size() > 1) {
            // Nothing runs, so nothing is partially finalized: the batch is refused as a whole.
            List<ToolResult> refused = new ArrayList<>(calls.size());
            for (ReviewToolCall call : calls) {
                refused.add(audited(call, () -> failure("BATCH_REJECTED",
                        "review-complete must be the only call in its batch. Submit findings and the "
                                + "assessment in earlier batches, then complete on its own. Nothing in "
                                + "this batch was applied.", null, null)));
            }
            return refused;
        }
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (ReviewToolCall call : calls) {
            results.add(execute(invocation, call));
        }
        return results;
    }

    /** One call, recorded as a tool call of the current AI interaction ({@link ToolCallAudit}). */
    public ToolResult execute(ReviewInvocation invocation, ReviewToolCall call) {
        return audited(call, () -> dispatch(invocation, call));
    }

    private static ToolResult audited(ReviewToolCall call, Supplier<ToolResult> dispatch) {
        if (call == null) {
            return dispatch.get();
        }
        String arguments = call.arguments() == null ? "{}" : UrlRedaction.redactAll(call.arguments().toString());
        return ToolCallAudit.record(ToolCallAudit.BUILT_IN, call.tool() != null ? call.tool() : "unknown",
                arguments, dispatch, ReviewSubmissionToolExecutor::failureOf);
    }

    /** A failed call's reason for the audit log: its error code and message (see {@link #failure}). */
    private static String failureOf(ToolResult result) {
        if (result.success()) {
            return null;
        }
        try {
            JsonNode payload = JSON.readTree(result.output());
            return payload.get("error").asString() + ": " + payload.get("message").asString();
        } catch (RuntimeException e) {
            return result.failureMessage();
        }
    }

    private ToolResult dispatch(ReviewInvocation invocation, ReviewToolCall call) {
        if (invocation == null || call == null) {
            return failure("INVALID_ARGUMENT", "A tool call and its review invocation are required",
                    null, null);
        }
        String tool = normalize(call.tool());
        if (!TOOL_NAMES.contains(tool)) {
            return failure("UNKNOWN_TOOL", "Tool '" + call.tool()
                    + "' is not part of the review submission protocol. Available tools: "
                    + String.join(", ", new TreeSet<>(TOOL_NAMES)), null, null);
        }
        try {
            ReviewScope scope = reviewService.findScope(invocation.reviewId()).orElse(null);
            if (scope == null) {
                return failure("UNKNOWN_REVIEW", "Review " + invocation.reviewId()
                        + " does not exist", null, null);
            }
            String scopeProblem = scopeMismatch(invocation, scope);
            if (scopeProblem != null) {
                log.error("Review tool {} refused: {}", tool, scopeProblem);
                return failure("SCOPE", scopeProblem, null, null);
            }
            if (GET_DRAFT.equals(tool)) {
                return success(draftJson(scope));
            }
            JsonNode arguments = call.arguments() == null ? JSON.createObjectNode() : call.arguments();
            String operationId = requireText(arguments, "operationId", MAX_OPERATION_ID_CHARS);
            String argumentHash = argumentHash(tool, arguments);
            String payload = operationService.run(invocation.reviewId(), operationId, tool, argumentHash,
                    () -> apply(invocation, tool, arguments));
            log.debug("Review tool {} ({}) applied as operation {} on review {}",
                    tool, call.callId(), operationId, invocation.reviewId());
            return success(payload);
        } catch (InvalidReviewContentException e) {
            return failure("INVALID_ARGUMENT", e.getMessage(), e.getField(), null);
        } catch (ReviewDraftConflictException e) {
            return failure("CONFLICT", e.getMessage(), null, e.getCurrentDraftRevision());
        } catch (ReviewToolException e) {
            if (e.getCode() == ReviewToolException.Code.OPERATION_CONFLICT) {
                Integer currentRevision = reviewService.findDraft(invocation.reviewId())
                        .map(DraftSnapshot::draftRevision).orElse(null);
                return failure("OPERATION_CONFLICT", e.getMessage(), "operationId", currentRevision);
            }
            return failure("CANCELLED", e.getMessage(), null, null);
        } catch (RuntimeException e) {
            log.error("Review tool {} failed on review {}", tool, invocation.reviewId(), e);
            return failure("FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    null, null);
        }
    }

    // ---------------------------------------------------------------- operations

    private String apply(ReviewInvocation invocation, String tool, JsonNode arguments) {
        if (invocation.isCancelled()) {
            throw new ReviewToolException(ReviewToolException.Code.CANCELLED,
                    "The review run was cancelled, so no further changes are recorded.");
        }
        return switch (tool) {
            case UPSERT_FINDING -> applyUpsertFinding(invocation, arguments);
            case REMOVE_FINDING -> applyRemoveFinding(invocation, arguments);
            case SET_ASSESSMENT -> applySetAssessment(invocation, arguments);
            case COMPLETE -> applyComplete(invocation, arguments);
            default -> throw new IllegalStateException("Unhandled review tool: " + tool);
        };
    }

    private String applyUpsertFinding(ReviewInvocation invocation, JsonNode arguments) {
        FindingDraft draft = new FindingDraft(
                requireText(arguments, "clientKey", 128),
                requireSeverity(arguments),
                requireText(arguments, "category", 64),
                requireText(arguments, "title", 512),
                requireText(arguments, "explanation", 20_000),
                requireText(arguments, "impact", 4_000),
                requireText(arguments, "recommendation", 4_000),
                evidence(arguments));
        Integer expectedRevision = optionalInt(arguments, "expectedRevision");
        FindingHandle handle = reviewService.upsertFinding(invocation.reviewId(), expectedRevision, draft);

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("ok", true);
        receipt.put("findingId", handle.ordinal());
        receipt.put("reference", handle.reference());
        receipt.put("revision", handle.revision());
        appendDraftState(receipt, invocation.reviewId());
        return receipt.toString();
    }

    private String applyRemoveFinding(ReviewInvocation invocation, JsonNode arguments) {
        int findingId = requireInt(arguments, "findingId");
        int expectedRevision = requireInt(arguments, "expectedRevision");
        String reason = requireText(arguments, "reason", 1024);
        ReviewDraftHandle handle = reviewService.withdrawFinding(invocation.reviewId(), findingId,
                expectedRevision, reason);

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("ok", true);
        receipt.put("findingId", findingId);
        receipt.put("reference", ReviewReferences.finding(handle.outputReference(), findingId));
        receipt.put("withdrawn", true);
        appendDraftState(receipt, invocation.reviewId());
        return receipt.toString();
    }

    private String applySetAssessment(ReviewInvocation invocation, JsonNode arguments) {
        AssessmentDraft draft = new AssessmentDraft(
                requireText(arguments, "summary", 4_000),
                requireConclusion(arguments),
                optionalText(arguments, "reviewedAreas"),
                optionalText(arguments, "limitations"));
        int expectedDraftRevision = requireInt(arguments, "expectedDraftRevision");
        ReviewDraftHandle handle = reviewService.setAssessment(invocation.reviewId(),
                expectedDraftRevision, draft);

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("ok", true);
        receipt.put("draftRevision", handle.draftRevision());
        receipt.put("assessmentCurrent", true);
        return receipt.toString();
    }

    private String applyComplete(ReviewInvocation invocation, JsonNode arguments) {
        int expectedDraftRevision = requireInt(arguments, "expectedDraftRevision");
        int expectedFindingCount = requireInt(arguments, "expectedFindingCount");
        FinalizedReview finalized = reviewService.finalizeReview(invocation.reviewId(),
                expectedDraftRevision, expectedFindingCount);

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("ok", true);
        receipt.put("finalized", true);
        receipt.put("reference", finalized.reference());
        receipt.put("assessmentReference", finalized.assessmentReference());
        receipt.put("conclusion", finalized.conclusion().name());
        receipt.put("contentHash", finalized.contentHash());
        receipt.put("findingCount", finalized.findings().size());
        ObjectNode counts = receipt.putObject("counts");
        counts.put("blocker", finalized.counts().blocker());
        counts.put("medium", finalized.counts().medium());
        counts.put("low", finalized.counts().low());
        ArrayNode references = receipt.putArray("findings");
        finalized.findings().forEach(finding -> references.add(finding.reference()));
        return receipt.toString();
    }

    private String draftJson(ReviewScope scope) {
        DraftSnapshot snapshot = reviewService.findDraft(scope.reviewId())
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Unknown review " + scope.reviewId()));
        ObjectNode draft = JSON.createObjectNode();
        draft.put("ok", true);
        draft.put("reviewId", snapshot.reviewId());
        draft.put("reference", snapshot.outputReference());
        draft.put("state", snapshot.state().name());
        draft.put("draftRevision", snapshot.draftRevision());
        draft.put("assessmentCurrent", snapshot.assessmentCurrent());
        if (snapshot.conclusion() == null) {
            draft.putNull("conclusion");
        } else {
            draft.put("conclusion", snapshot.conclusion().name());
        }
        if (snapshot.summary() == null) {
            draft.putNull("summary");
        } else {
            draft.put("summary", snapshot.summary());
        }
        ArrayNode findings = draft.putArray("findings");
        for (DraftSnapshot.FindingSummary finding : snapshot.findings()) {
            ObjectNode entry = findings.addObject();
            entry.put("findingId", finding.ordinal());
            entry.put("reference", finding.reference());
            entry.put("clientKey", finding.clientKey());
            entry.put("revision", finding.revision());
            entry.put("severity", finding.severity().name());
            entry.put("title", finding.title());
        }
        draft.put("activeFindingCount", snapshot.findings().size());
        return draft.toString();
    }

    private void appendDraftState(ObjectNode receipt, long reviewId) {
        DraftSnapshot snapshot = reviewService.findDraft(reviewId)
                .orElseThrow(() -> new InvalidReviewContentException("review",
                        "Unknown review " + reviewId));
        receipt.put("draftRevision", snapshot.draftRevision());
        receipt.put("activeFindingCount", snapshot.findings().size());
        receipt.put("assessmentCurrent", snapshot.assessmentCurrent());
    }

    // ---------------------------------------------------------------- trusted scope

    /** {@code null} when the draft belongs to the invocation; otherwise why it does not. */
    private static String scopeMismatch(ReviewInvocation invocation, ReviewScope scope) {
        if (!equal(invocation.botId(), scope.botId())
                || !equal(invocation.gitIntegrationId(), scope.gitIntegrationId())
                || !equal(invocation.repoOwner(), scope.repoOwner())
                || !equal(invocation.repoName(), scope.repoName())
                || !equal(invocation.prNumber(), scope.prNumber())) {
            return "Review " + scope.reviewId() + " belongs to bot " + scope.botId() + " on "
                    + scope.repoOwner() + "/" + scope.repoName() + "#" + scope.prNumber()
                    + ", not to this invocation";
        }
        return null;
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    // ---------------------------------------------------------------- argument reading

    private static String requireText(JsonNode arguments, String field, int maxChars) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull() || !node.isString() || node.asString().isBlank()) {
            throw new InvalidReviewContentException(field, "'" + field + "' is required and must be text");
        }
        String value = node.asString().strip();
        if (value.length() > maxChars) {
            throw new InvalidReviewContentException(field,
                    "'" + field + "' allows at most " + maxChars + " characters");
        }
        return value;
    }

    private static String optionalText(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new InvalidReviewContentException(field, "'" + field + "' must be text");
        }
        return node.asString().isBlank() ? null : node.asString().strip();
    }

    private static int requireInt(JsonNode arguments, String field) {
        Integer value = optionalInt(arguments, field);
        if (value == null) {
            throw new InvalidReviewContentException(field, "'" + field + "' is required");
        }
        return value;
    }

    private static Integer optionalInt(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() || !node.canConvertToInt()) {
            throw new InvalidReviewContentException(field, "'" + field + "' must be an integer");
        }
        return node.asInt();
    }

    private static ReviewSeverity requireSeverity(JsonNode arguments) {
        String value = requireText(arguments, "severity", 16).toUpperCase();
        try {
            return ReviewSeverity.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidReviewContentException("severity",
                    "'severity' must be one of BLOCKER, MEDIUM, LOW");
        }
    }

    private static ReviewConclusion requireConclusion(JsonNode arguments) {
        String value = requireText(arguments, "conclusion", 32).toUpperCase();
        try {
            return ReviewConclusion.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidReviewContentException("conclusion", "'conclusion' must be one of "
                    + "FINDINGS_PRESENT, NO_FINDINGS, INCONCLUSIVE");
        }
    }

    private static ReviewSide requireSide(JsonNode evidence, int index) {
        JsonNode node = evidence.get("side");
        if (node == null || node.isNull() || !node.isString()) {
            throw new InvalidReviewContentException("evidence", "evidence[" + index
                    + "].side is required and must be BASE or HEAD");
        }
        String value = node.asString().strip().toUpperCase();
        try {
            return ReviewSide.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidReviewContentException("evidence", "evidence[" + index
                    + "].side must be BASE or HEAD");
        }
    }

    private static List<LocationDraft> evidence(JsonNode arguments) {
        JsonNode node = arguments.get("evidence");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InvalidReviewContentException("evidence",
                    "'evidence' must be an array of source locations");
        }
        List<LocationDraft> locations = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            if (entry == null || !entry.isObject()) {
                throw new InvalidReviewContentException("evidence",
                        "evidence[" + i + "] must be an object with path, side and description");
            }
            locations.add(new LocationDraft(
                    requireText(entry, "path", 1024),
                    requireSide(entry, i),
                    optionalInt(entry, "startLine"),
                    optionalInt(entry, "endLine"),
                    requireText(entry, "description", 4_000)));
        }
        return locations;
    }

    // ---------------------------------------------------------------- receipts

    /**
     * Canonical fingerprint of a call: object keys sorted, arrays in order. The model's key order
     * must not decide whether a retry is the same call.
     */
    static String argumentHash(String tool, JsonNode arguments) {
        StringBuilder canonical = new StringBuilder(tool).append('\n');
        canonicalize(arguments, canonical);
        return Digests.sha256Hex(canonical.toString());
    }

    private static void canonicalize(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            out.append('{');
            List<String> names = new ArrayList<>(node.propertyNames());
            Collections.sort(names);
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(names.get(i)).append(':');
                canonicalize(node.get(names.get(i)), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                canonicalize(node.get(i), out);
            }
            out.append(']');
            return;
        }
        out.append(node.toString());
    }

    private static ToolResult success(String payload) {
        return new ToolResult(true, 0, payload, "");
    }

    /**
     * A refused call carries the same machine-readable shape a successful one does, so the model
     * can act on {@code error}/{@code field}/{@code currentDraftRevision} instead of parsing prose.
     */
    private static ToolResult failure(String code, String message, String field,
                                     Integer currentDraftRevision) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("ok", false);
        payload.put("error", code);
        payload.put("message", message);
        if (field != null) {
            payload.put("field", field);
        }
        if (currentDraftRevision != null) {
            payload.put("currentDraftRevision", currentDraftRevision);
            payload.put("next", REVISIT_HINT);
        }
        return new ToolResult(false, -1, payload.toString(), "");
    }

    private static String normalize(String tool) {
        return tool == null ? "" : tool.strip().toLowerCase();
    }
}
