package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.AiAuditContext;
import org.remus.giteabot.ai.AuditRun;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * The submission protocol's contract: trusted scope, idempotent operations, revision conflicts,
 * cancellation and the completion-alone rule. The service under test is real (over in-memory
 * repository stubs), so a call goes through argument parsing, draft validation and the receipt
 * bookkeeping exactly as it does in production.
 */
@ExtendWith(MockitoExtension.class)
class ReviewSubmissionToolExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private WorkflowOutputRepository outputRepository;
    @Mock private ReviewRecordRepository reviewRepository;
    @Mock private ReviewToolOperationRepository operationRepository;
    @Mock private ReferenceAllocator referenceAllocator;

    private final Map<String, ReviewToolOperation> receipts = new HashMap<>();

    private ReviewSubmissionToolExecutor executor;
    private ReviewRecord review;

    @BeforeEach
    void setUp() {
        StructuredReviewService reviewService = new StructuredReviewService(outputRepository,
                reviewRepository, referenceAllocator);
        ReviewToolOperationService operationService =
                new ReviewToolOperationService(operationRepository);
        executor = new ReviewSubmissionToolExecutor(reviewService, operationService);

        review = draftReview();
        lenient().when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));
        lenient().when(operationRepository.findByReviewIdAndOperationId(anyLong(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(
                        receipts.get(invocation.getArgument(0) + "|" + invocation.getArgument(1))));
        lenient().when(operationRepository.save(any(ReviewToolOperation.class)))
                .thenAnswer(invocation -> {
                    ReviewToolOperation receipt = invocation.getArgument(0);
                    receipts.put(receipt.getReviewId() + "|" + receipt.getOperationId(), receipt);
                    return receipt;
                });
    }

    @Test
    void upsertFindingPersistsTypedEvidenceAndReturnsTheServerIdentity() {
        ToolResult result = executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        assertTrue(result.success());
        JsonNode receipt = json(result);
        assertEquals(1, receipt.get("findingId").asInt());
        assertEquals("O1234-F1", receipt.get("reference").asString());
        assertEquals(1, receipt.get("revision").asInt());
        assertEquals(1, receipt.get("draftRevision").asInt());
        assertEquals(1, receipt.get("activeFindingCount").asInt());

        ReviewFinding stored = review.getFindings().get(0);
        assertEquals("src/SettingsController.java", stored.getLocations().get(0).getPath());
        assertEquals(ReviewSide.HEAD, stored.getLocations().get(0).getSide());
        assertEquals(42, stored.getLocations().get(0).getStartLine());
        assertEquals(49, stored.getLocations().get(0).getEndLine());
    }

    @Test
    void exactRetryReplaysTheReceiptWithoutApplyingTheMutationTwice() {
        ObjectNode arguments = findingArgs("op-1", "settings-auth");

        ToolResult first = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, arguments));
        ToolResult retry = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, arguments));

        assertEquals(first.output(), retry.output());
        assertEquals(1, review.getFindings().size());
        assertEquals(1, receipts.size());
    }

    @Test
    void reusedOperationIdWithDifferentArgumentsIsRefused() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        ToolResult conflicting = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-1", "other-key")));

        assertFalse(conflicting.success());
        assertEquals("OPERATION_CONFLICT", json(conflicting).get("error").asString());
        assertEquals(1, review.getFindings().size());
    }

    @Test
    void eachOperationIdAppliesItsOwnFinding() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));
        ToolResult second = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-2", "naming")));

        assertEquals(2, json(second).get("findingId").asInt());
        assertEquals("O1234-F2", json(second).get("reference").asString());
    }

    @Test
    void correctionKeepsTheFindingIdentityAndAdvancesItsRevision() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        ObjectNode correction = findingArgs("op-2", "settings-auth");
        correction.put("expectedRevision", 1);
        correction.put("severity", "LOW");
        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, correction));

        assertEquals("O1234-F1", json(result).get("reference").asString());
        assertEquals(2, json(result).get("revision").asInt());
        assertEquals(1, json(result).get("activeFindingCount").asInt());
        assertEquals(ReviewSeverity.LOW, review.getFindings().get(0).getSeverity());
    }

    @Test
    void staleFindingRevisionIsRefusedWithTheCurrentDraftRevision() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        ObjectNode correction = findingArgs("op-2", "settings-auth");
        correction.put("expectedRevision", 7);
        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, correction));

        assertFalse(result.success());
        JsonNode payload = json(result);
        assertEquals("CONFLICT", payload.get("error").asString());
        assertEquals(1, payload.get("currentDraftRevision").asInt());
        assertTrue(payload.get("next").asString().contains("review-get-draft"));
    }

    @Test
    void requiredFieldsAndEnumerationsAreValidated() {
        assertInvalidField("severity", upsertArgs(a -> a.put("severity", "CATASTROPHIC")), "INVALID_ARGUMENT");
        assertInvalidField("operationId", upsertArgs(a -> a.remove("operationId")), "INVALID_ARGUMENT");
        assertInvalidField("clientKey", upsertArgs(a -> a.put("clientKey", "  ")), "INVALID_ARGUMENT");
        assertInvalidField("evidence", upsertArgs(a -> a.putArray("evidence").add("not-an-object")),
                "INVALID_ARGUMENT");
    }

    @Test
    void evidenceEntriesNeedAPathASideAndADescription() {
        assertInvalidField("evidence", upsertArgs(a -> {
            ObjectNode entry = a.putArray("evidence").addObject();
            entry.put("path", "src/A.java");
            entry.put("side", "SIDEWAYS");
            entry.put("description", "evidence");
        }), "INVALID_ARGUMENT");

        assertInvalidField("path", upsertArgs(a -> {
            ObjectNode entry = a.putArray("evidence").addObject();
            entry.put("side", "HEAD");
            entry.put("description", "evidence");
        }), "INVALID_ARGUMENT");
    }

    @Test
    void invalidSourceLocationsAreRejectedByTheDraftValidation() {
        assertInvalidField("path", upsertArgs(a -> evidenceEntry(a,
                entry -> entry.put("path", "../outside.java"))), "INVALID_ARGUMENT");
        assertInvalidField("line", upsertArgs(a -> evidenceEntry(a,
                entry -> entry.put("startLine", 9))), "INVALID_ARGUMENT");
    }

    @Test
    void removeFindingWithdrawsItAndKeepsItsReference() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        ToolResult removed = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.REMOVE_FINDING,
                        removeArgs("op-2", 1, 1, "Inspecting the enclosing filter showed it is covered")));

        assertTrue(removed.success());
        assertEquals("O1234-F1", json(removed).get("reference").asString());
        assertEquals(0, json(removed).get("activeFindingCount").asInt());
        assertTrue(review.getFindings().get(0).isWithdrawn());
    }

    @Test
    void removingAFindingOfAnotherReviewIsRefused() {
        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.REMOVE_FINDING, removeArgs("op-1", 99, 1, "reason")));

        assertFalse(result.success());
        assertEquals("INVALID_ARGUMENT", json(result).get("error").asString());
        assertEquals("finding", json(result).get("field").asString());
    }

    @Test
    void setAssessmentBindsToTheCurrentRevision() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.SET_ASSESSMENT, assessmentArgs("op-2", 1)));

        assertTrue(result.success());
        assertEquals(2, json(result).get("draftRevision").asInt());
        assertEquals(2, review.getAssessment().getAssessedRevision());
        assertEquals(ReviewConclusion.FINDINGS_PRESENT, review.getAssessment().getConclusion());
    }

    @Test
    void staleDraftRevisionOnTheAssessmentIsRefusedWithTheCurrentRevision() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.SET_ASSESSMENT, assessmentArgs("op-2", 0)));

        assertEquals("CONFLICT", json(result).get("error").asString());
        assertEquals(1, json(result).get("currentDraftRevision").asInt());
    }

    @Test
    void completeFinalizesAndReturnsReferenceHashAndCounts() {
        completeDraft();

        JsonNode receipt = json(executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.COMPLETE, completeArgs("op-9", 2, 1))));

        assertEquals("O1234", receipt.get("reference").asString());
        assertEquals("O1234-A", receipt.get("assessmentReference").asString());
        assertEquals(64, receipt.get("contentHash").asString().length());
        assertEquals(1, receipt.get("findingCount").asInt());
        assertEquals(1, receipt.get("counts").get("medium").asInt());
        assertEquals(ReviewState.FINALIZED, review.getState());
    }

    @Test
    void completeRejectsAMismatchedFindingCount() {
        completeDraft();

        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.COMPLETE, completeArgs("op-9", 2, 5)));

        assertEquals("CONFLICT", json(result).get("error").asString());
        assertEquals(ReviewState.DRAFT, review.getState());
    }

    @Test
    void completeCannotShareItsBatch() {
        completeDraft();

        List<ToolResult> results = executor.executeBatch(invocation(), List.of(
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-9", "late")),
                call(ReviewSubmissionToolExecutor.COMPLETE, completeArgs("op-10", 2, 1))));

        assertEquals(2, results.size());
        results.forEach(result -> {
            assertFalse(result.success());
            assertEquals("BATCH_REJECTED", json(result).get("error").asString());
        });
        assertEquals(ReviewState.DRAFT, review.getState());
        assertEquals(1, review.getFindings().size());
    }

    @Test
    void aBatchAppliesItsCallsInOrderAndReturnsOneResultPerCall() {
        List<ToolResult> results = executor.executeBatch(invocation(), List.of(
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-1", "first")),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-2", "second"))));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(ToolResult::success));
        assertEquals(1, json(results.get(0)).get("findingId").asInt());
        assertEquals(2, json(results.get(1)).get("findingId").asInt());
    }

    @Test
    void anExactCompletionRetryReplaysItsReceiptAfterFinalization() {
        completeDraft();
        ObjectNode completeArguments = completeArgs("op-9", 2, 1);

        ToolResult first = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.COMPLETE, completeArguments));
        ToolResult retry = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.COMPLETE, completeArguments));

        assertTrue(retry.success());
        assertEquals(first.output(), retry.output());
    }

    @Test
    void aNewMutationAfterFinalizationIsRefused() {
        completeDraft();
        executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.COMPLETE, completeArgs("op-9", 2, 1)));

        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-10", "late")));

        assertEquals("CONFLICT", json(result).get("error").asString());
        assertEquals(1, review.getFindings().size());
    }

    @Test
    void aCancelledRunAppliesNothingAndLeavesNoReceipt() {
        ReviewInvocation cancelled = new ReviewInvocation(1L, 3L, 7L, "acme", "web", 9L, 55L,
                () -> true);

        ToolResult result = executor.execute(cancelled,
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-1", "settings-auth")));

        assertFalse(result.success());
        assertEquals("CANCELLED", json(result).get("error").asString());
        assertTrue(review.getFindings().isEmpty());
        assertTrue(receipts.isEmpty());
    }

    @Test
    void getDraftReportsFindingsSnapshotWithoutLeavingAReceipt() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));

        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.GET_DRAFT, JSON.createObjectNode()));

        JsonNode draft = json(result);
        assertEquals("O1234", draft.get("reference").asString());
        assertEquals(1, draft.get("draftRevision").asInt());
        assertEquals(1, draft.get("activeFindingCount").asInt());
        assertEquals("O1234-F1", draft.get("findings").get(0).get("reference").asString());
        assertFalse(draft.get("assessmentCurrent").asBoolean());
        assertEquals(1, receipts.size());
    }

    @Test
    void aForeignScopeIsRefusedBeforeAnythingIsWritten() {
        ReviewInvocation foreign = new ReviewInvocation(1L, 99L, 7L, "acme", "web", 9L, 55L, () -> false);

        ToolResult result = executor.execute(foreign,
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-1", "settings-auth")));

        assertFalse(result.success());
        assertEquals("SCOPE", json(result).get("error").asString());
        assertTrue(review.getFindings().isEmpty());
    }

    @Test
    void unknownToolsAndReviewsAreRefused() {
        ToolResult unknown = executor.execute(invocation(),
                call("write-file", JSON.createObjectNode()));
        assertEquals("UNKNOWN_TOOL", json(unknown).get("error").asString());

        ReviewInvocation missing = new ReviewInvocation(42L, 3L, 7L, "acme", "web", 9L, 55L, () -> false);
        ToolResult unknownReview = executor.execute(missing,
                call(ReviewSubmissionToolExecutor.GET_DRAFT, JSON.createObjectNode()));
        assertEquals("UNKNOWN_REVIEW", json(unknownReview).get("error").asString());
    }

    // ---------------------------------------------------------------- helpers

    private void completeDraft() {
        executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                findingArgs("op-1", "settings-auth")));
        executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.SET_ASSESSMENT, assessmentArgs("op-2", 1)));
    }

    private static ReviewInvocation invocation() {
        return new ReviewInvocation(1L, 3L, 7L, "acme", "web", 9L, 55L, () -> false);
    }

    private static ReviewToolCall call(String tool, ObjectNode arguments) {
        return new ReviewToolCall(tool, "call-1", arguments);
    }

    private static ObjectNode findingArgs(String operationId, String clientKey) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", operationId);
        arguments.put("clientKey", clientKey);
        arguments.put("severity", "MEDIUM");
        arguments.put("category", "authorization");
        arguments.put("title", "Settings update may bypass authorization");
        arguments.put("explanation", "The observed update path does not show the permission check.");
        arguments.put("impact", "An unauthorized caller could change settings.");
        arguments.put("recommendation", "Verify the enclosing filter.");
        ObjectNode evidence = arguments.putArray("evidence").addObject();
        evidence.put("path", "src/SettingsController.java");
        evidence.put("side", "HEAD");
        evidence.put("startLine", 42);
        evidence.put("endLine", 49);
        evidence.put("description", "Settings update entry point.");
        return arguments;
    }

    private static ObjectNode upsertArgs(java.util.function.Consumer<ObjectNode> edit) {
        ObjectNode arguments = findingArgs("op-1", "settings-auth");
        edit.accept(arguments);
        return arguments;
    }

    private static ObjectNode evidenceEntry(ObjectNode arguments,
                                           java.util.function.Consumer<ObjectNode> edit) {
        arguments.remove("evidence");
        ObjectNode entry = arguments.putArray("evidence").addObject();
        entry.put("path", "src/A.java");
        entry.put("side", "HEAD");
        entry.put("startLine", 1);
        entry.put("endLine", 2);
        entry.put("description", "evidence");
        edit.accept(entry);
        return arguments;
    }

    private static ObjectNode removeArgs(String operationId, int findingId, int revision,
                                         String reason) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", operationId);
        arguments.put("findingId", findingId);
        arguments.put("expectedRevision", revision);
        arguments.put("reason", reason);
        return arguments;
    }

    private static ObjectNode assessmentArgs(String operationId, int draftRevision) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", operationId);
        arguments.put("expectedDraftRevision", draftRevision);
        arguments.put("summary", "One authorization concern needs attention.");
        arguments.put("conclusion", "FINDINGS_PRESENT");
        arguments.put("reviewedAreas", "settings controller and security configuration");
        arguments.putNull("limitations");
        return arguments;
    }

    private static ObjectNode completeArgs(String operationId, int draftRevision, int findingCount) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("operationId", operationId);
        arguments.put("expectedDraftRevision", draftRevision);
        arguments.put("expectedFindingCount", findingCount);
        return arguments;
    }

    private void assertInvalidField(String field, ObjectNode arguments, String errorCode) {
        // A fresh draft per case: a refused call must not have changed anything.
        review.getFindings().clear();
        review.setAssessment(null);
        review.setDraftRevision(0);
        receipts.clear();

        ToolResult result = executor.execute(invocation(),
                call(ReviewSubmissionToolExecutor.UPSERT_FINDING, arguments));

        assertFalse(result.success(), "expected a refusal for " + field);
        JsonNode payload = json(result);
        assertEquals(errorCode, payload.get("error").asString());
        assertEquals(field, payload.get("field").asString());
        assertTrue(review.getFindings().isEmpty());
    }

    private static JsonNode json(ToolResult result) {
        return JSON.readTree(result.output());
    }

    private static ReviewRecord draftReview() {
        WorkflowOutput output = new WorkflowOutput();
        output.setId(10L);
        output.setReference("O1234");
        output.setBotId(3L);
        output.setGitIntegrationId(7L);
        output.setRepoOwner("acme");
        output.setRepoName("web");
        output.setPrNumber(9L);
        output.setPrWorkflowRunId(55L);
        output.setInvocationId("inv-1");
        ReviewRecord record = new ReviewRecord();
        record.setId(1L);
        record.setOutput(output);
        record.setState(ReviewState.DRAFT);
        record.setSchemaVersion(StructuredReviewService.SCHEMA_VERSION);
        record.setDraftRevision(0);
        return record;
    }

    // ------------------------------------------------------------------ audit log

    /** Collects the tool calls reported to an open, persisted audit run. */
    private static List<AuditRun.ToolCallRecord> recordToolCalls() {
        List<AuditRun.ToolCallRecord> records = new java.util.ArrayList<>();
        AuditRun run = AiAuditContext.openRun("agentic-review", null);
        run.attach(1L, new AuditRun.Sink() {
            @Override
            public void runFinished(AuditRun finished) {
            }

            @Override
            public void toolCallFinished(AuditRun current, AuditRun.ToolCallRecord record) {
                records.add(record);
            }
        });
        run.beginAttempt();
        run.setInteractionId(10L);
        return records;
    }

    @Test
    void callsAreRecordedAsToolCallsOfTheCurrentInteraction() {
        List<AuditRun.ToolCallRecord> records = recordToolCalls();
        try {
            executor.execute(invocation(), call(ReviewSubmissionToolExecutor.UPSERT_FINDING,
                    findingArgs("op-1", "settings-auth")));
            executor.execute(invocation(), call("review-unknown", JSON.createObjectNode()));
        } finally {
            AiAuditContext.clear();
        }

        assertEquals(2, records.size());
        assertEquals(ReviewSubmissionToolExecutor.UPSERT_FINDING, records.get(0).toolName());
        assertEquals("built-in", records.get(0).source());
        assertTrue(records.get(0).success());
        assertTrue(records.get(0).arguments().contains("settings-auth"));
        assertFalse(records.get(1).success());
        assertTrue(records.get(1).errorMessage().startsWith("UNKNOWN_TOOL: "));
    }

    @Test
    void callsOfARejectedBatchAreRecordedAsFailed() {
        completeDraft();
        List<AuditRun.ToolCallRecord> records = recordToolCalls();
        try {
            executor.executeBatch(invocation(), List.of(
                    call(ReviewSubmissionToolExecutor.UPSERT_FINDING, findingArgs("op-9", "late")),
                    call(ReviewSubmissionToolExecutor.COMPLETE, completeArgs("op-10", 2, 1))));
        } finally {
            AiAuditContext.clear();
        }

        assertEquals(List.of(ReviewSubmissionToolExecutor.UPSERT_FINDING, ReviewSubmissionToolExecutor.COMPLETE),
                records.stream().map(AuditRun.ToolCallRecord::toolName).toList());
        assertTrue(records.stream().noneMatch(AuditRun.ToolCallRecord::success));
        assertTrue(records.getFirst().errorMessage().startsWith("BATCH_REJECTED: "));
    }
}
