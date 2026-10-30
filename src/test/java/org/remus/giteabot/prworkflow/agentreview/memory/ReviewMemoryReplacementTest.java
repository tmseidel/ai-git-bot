package org.remus.giteabot.prworkflow.agentreview.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReviewMemoryReplacementTest {
    private final ReviewMemoryCodec codec = new ReviewMemoryCodec();
    private final ReviewMemoryOperations operations = new ReviewMemoryOperations();
    private final ReviewMemoryOperations.ApplicationMetadata metadata = new ReviewMemoryOperations.ApplicationMetadata(
            ReviewMemory.Purpose.REVIEW, ReviewMemoryCodecTest.REVISION, null, null, null, List.of("src/A.java"));
    private static final Instant SEALED = Instant.parse("2026-09-01T12:34:56.123456789Z");

    @Test
    void fullReplacementsPreserveStableIdsAndRequireExplicitRemovalsIncludingOnSubmit() {
        var first = operations.updateReview(null, metadata, 1, codec.readUpdateReview("""
                {"review":{"summary":"First","openQuestions":[],"findings":[
                  {"severity":"MEDIUM","category":"correctness","title":"Race","description":"Two workers collide",
                   "recommendation":"Lock assignment","file":"src/A.java","line":12}]},"removals":[]}
                """));
        String id = first.review().findings().getFirst().id();
        assertThat(id).matches("[0-9a-f-]{36}");

        var revised = operations.updateReview(first, metadata, 2, codec.readUpdateReview("""
                {"review":{"summary":"Revised","openQuestions":[],"findings":[
                  {"existingId":"%s","severity":"BLOCKER","category":"correctness","title":"Race confirmed",
                   "description":"Observed duplicate assignment","recommendation":"Lock assignment"}]},"removals":[]}
                """.formatted(id)));
        assertThat(revised.review().findings().getFirst().id()).isEqualTo(id);
        assertThat(first.review().findings().getFirst().severity()).isEqualTo(ReviewMemory.Severity.MEDIUM);
        var omission = codec.readSubmitReview("""
                {"result":"COMPLETE","review":{"summary":"No confirmed issues","openQuestions":[],"findings":[]},"removals":[]}
                """);
        assertThatIllegalArgumentException().isThrownBy(() -> operations.submitReview(revised, metadata, 3,
                ReviewMemory.CompletionCause.VOLUNTARY, SEALED, omission)).withMessageContaining("removal");

        var complete = operations.submitReview(revised, metadata, 3, ReviewMemory.CompletionCause.FORCED, SEALED,
                codec.readSubmitReview("""
                        {"result":"COMPLETE","review":{"summary":"No confirmed issues","openQuestions":[],"findings":[]},
                         "removals":[{"id":"%s","reason":"The shared lock was found in the caller"}]}
                        """.formatted(id)));
        assertThat(complete.status()).isEqualTo(ReviewMemory.Status.COMPLETE);
        assertThat(complete.memoryVersion()).isEqualTo(3);
        assertThat(complete.sealedAt()).isEqualTo(SEALED);
        assertThat(complete.completionCause()).isEqualTo(ReviewMemory.CompletionCause.FORCED);
        assertThat(complete.review().findings()).isEmpty();
        assertThat(codec.decode(codec.encode(complete).utf8())).isEqualTo(complete);
        assertThatIllegalArgumentException().isThrownBy(() -> operations.submitReview(complete, metadata, 4,
                ReviewMemory.CompletionCause.VOLUNTARY, SEALED.plusSeconds(1), omission));
    }

    @Test
    void clarificationReplacesOnlyTheAnswerAndInsufficientSealingRetainsLastValidContent() {
        var clarificationMetadata = new ReviewMemoryOperations.ApplicationMetadata(ReviewMemory.Purpose.CLARIFICATION,
                ReviewMemoryCodecTest.REVISION, 42L, 43L, "Why is the lock needed?", List.of());
        var draft = operations.updateClarification(null, clarificationMetadata, 1,
                codec.readUpdateClarification("{\"answerDraft\":\"The workers share a queue\"}"));
        var complete = operations.submitClarification(draft, clarificationMetadata, 2,
                ReviewMemory.CompletionCause.VOLUNTARY, SEALED,
                codec.readSubmitClarification("{\"result\":\"COMPLETE\",\"answer\":\"The lock protects worker assignment\"}"));
        assertThat(complete.clarification().question()).isEqualTo("Why is the lock needed?");
        assertThat(complete.clarification().answer()).isEqualTo("The lock protects worker assignment");
        assertThat(complete.review()).isNull();
        assertThat(complete.baseReviewRunId()).isEqualTo(42L);
        assertThat(complete.parentMemoryRunId()).isEqualTo(43L);
        assertThat(codec.decode(codec.encode(complete).utf8())).isEqualTo(complete);

        var insufficient = operations.submitClarification(draft, clarificationMetadata, 2,
                ReviewMemory.CompletionCause.FORCED, SEALED,
                codec.readSubmitClarification("{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"Need caller evidence\"}"));
        assertThat(insufficient.clarification()).isEqualTo(draft.clarification());
        assertThat(insufficient.status()).isEqualTo(ReviewMemory.Status.INSUFFICIENT_CONTEXT);
        assertThat(insufficient.insufficientReason()).isEqualTo("Need caller evidence");
        assertThat(codec.decode(codec.encode(insufficient).utf8())).isEqualTo(insufficient);

        var changedQuestion = new ReviewMemoryOperations.ApplicationMetadata(ReviewMemory.Purpose.CLARIFICATION,
                ReviewMemoryCodecTest.REVISION, 42L, 43L, "A different question", List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> operations.updateClarification(draft, changedQuestion, 2,
                codec.readUpdateClarification("{\"answerDraft\":\"Changed\"}"))).withMessageContaining("Question");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateClarification(
                "{\"answerDraft\":\"Changed\",\"question\":\"Replace original\"}"));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readSubmitClarification(
                "{\"result\":\"COMPLETE\",\"answer\":\"Changed\",\"review\":{}}"));
    }

    @Test
    void removingAStoredFindingNeverAllowsReusingItsIdentity() {
        var first = operations.updateReview(null, metadata, 1, input(null));
        String id = first.review().findings().getFirst().id();
        var removed = operations.updateReview(first, metadata, 2, new ReviewMemoryInputs.UpdateReviewMemoryInput(
                new ReviewMemoryInputs.ReviewContentInput("Rechecked", List.of(), List.of()),
                List.of(new ReviewMemoryInputs.Removal(id, "Disproved by caller evidence"))));
        var reloaded = codec.decode(codec.encode(removed).utf8());
        assertThatIllegalArgumentException().isThrownBy(() -> operations.updateReview(reloaded, metadata, 3, input(id)))
                .withMessageContaining("Unknown existing");
        var newFinding = operations.updateReview(reloaded, metadata, 3, input(null));
        assertThat(newFinding.review().findings().getFirst().id()).isNotEqualTo(id);
        assertThat(first.review().findings()).hasSize(1);
        assertThat(reloaded.review().findings()).isEmpty();
    }

    @Test
    void accidentalOmissionsDuplicateReferencesAndInvalidRemovalsCannotAlterADraft() {
        var draft = operations.updateReview(null, metadata, 1, input(null));
        String id = draft.review().findings().getFirst().id();
        var empty = new ReviewMemoryInputs.ReviewContentInput("Rechecked", List.of(), List.of());
        var existing = input(id).review();
        var duplicate = new ReviewMemoryInputs.ReviewContentInput("Rechecked", List.of(),
                List.of(existing.findings().getFirst(), existing.findings().getFirst()));
        var before = codec.encode(draft).json();
        var invalid = List.of(
                new ReviewMemoryInputs.UpdateReviewMemoryInput(empty, List.of()),
                new ReviewMemoryInputs.UpdateReviewMemoryInput(existing, List.of(new ReviewMemoryInputs.Removal(id, "Retained"))),
                new ReviewMemoryInputs.UpdateReviewMemoryInput(empty, List.of(new ReviewMemoryInputs.Removal("unknown", "Unknown"))),
                new ReviewMemoryInputs.UpdateReviewMemoryInput(empty, List.of(new ReviewMemoryInputs.Removal(id, "Reason"), new ReviewMemoryInputs.Removal(id, "Again"))),
                new ReviewMemoryInputs.UpdateReviewMemoryInput(duplicate, List.of()), input("unknown"));
        invalid.forEach(candidate -> assertThatIllegalArgumentException().isThrownBy(() -> operations.updateReview(draft, metadata, 2, candidate)));
        assertThat(codec.encode(draft).json()).isEqualTo(before);
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemoryInputs.Removal(id, " "));
    }

    @Test
    void insufficientReviewKeepsLastValidContentOrUsesEmptyContentWithoutAnyDraft() {
        var draft = operations.updateReview(null, metadata, 1, input(null));
        var submit = codec.readSubmitReview("{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"No caller evidence\"}");
        var insufficient = operations.submitReview(draft, metadata, 2, ReviewMemory.CompletionCause.FORCED, SEALED, submit);
        assertThat(insufficient.review()).isEqualTo(draft.review());
        assertThat(insufficient.insufficientReason()).isEqualTo("No caller evidence");
        assertThat(insufficient.status()).isEqualTo(ReviewMemory.Status.INSUFFICIENT_CONTEXT);
        assertThat(codec.decode(codec.encode(insufficient).utf8())).isEqualTo(insufficient);
        var empty = operations.submitReview(null, metadata, 1, ReviewMemory.CompletionCause.VOLUNTARY, SEALED, submit);
        assertThat(empty.review()).isEqualTo(new ReviewMemory.ReviewContent("", List.of(), List.of()));
        var clarificationMetadata = new ReviewMemoryOperations.ApplicationMetadata(ReviewMemory.Purpose.CLARIFICATION,
                ReviewMemoryCodecTest.REVISION, null, null, "Why?", List.of());
        var emptyClarification = operations.submitClarification(null, clarificationMetadata, 1,
                ReviewMemory.CompletionCause.FORCED, SEALED, codec.readSubmitClarification(
                        "{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"Need evidence\"}"));
        assertThat(emptyClarification.clarification()).isEqualTo(new ReviewMemory.ClarificationContent("Why?", ""));
        assertThat(emptyClarification.baseReviewRunId()).isNull();
        assertThat(emptyClarification.parentMemoryRunId()).isNull();
    }

    @Test
    void voluntaryCompleteSubmitCanBeTheFirstAndOnlySnapshot() {
        var input = codec.readSubmitReview("""
                {"result":"COMPLETE","review":{"summary":"No confirmed issues","openQuestions":[],"findings":[]},"removals":[]}
                """);
        var complete = operations.submitReview(null, metadata, 1, ReviewMemory.CompletionCause.VOLUNTARY, SEALED, input);
        assertThat(complete.status()).isEqualTo(ReviewMemory.Status.COMPLETE);
        assertThat(complete.review().summary()).isEqualTo("No confirmed issues");
        var withRemoval = new ReviewMemoryInputs.SubmitReviewInput(ReviewMemoryInputs.Result.COMPLETE, input.review(),
                List.of(new ReviewMemoryInputs.Removal("F1", "No draft")), null);
        assertThatIllegalArgumentException().isThrownBy(() -> operations.submitReview(null, metadata, 1,
                ReviewMemory.CompletionCause.VOLUNTARY, SEALED, withRemoval));
    }

    @Test
    void applicationBindingsCannotChangeAndTheCallerOwnsVersionAllocation() {
        var draft = operations.updateReview(null, metadata, 7, input(null));
        String id = draft.review().findings().getFirst().id();
        assertThatIllegalArgumentException().isThrownBy(() -> operations.updateReview(draft, metadata, 7, input(id)));
        assertThatIllegalArgumentException().isThrownBy(() -> operations.updateReview(null, metadata, 0, input(null)));
        var changedRevision = new ReviewMemoryOperations.ApplicationMetadata(ReviewMemory.Purpose.REVIEW,
                new RevisionKey("d".repeat(40), ReviewMemoryCodecTest.REVISION.baseSha(), ReviewMemoryCodecTest.REVISION.diffHash()),
                null, null, null, List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> operations.updateReview(draft, changedRevision, 8, input(id)));
        var clarificationMetadata = new ReviewMemoryOperations.ApplicationMetadata(ReviewMemory.Purpose.CLARIFICATION,
                ReviewMemoryCodecTest.REVISION, 42L, 43L, "Why?", List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> operations.updateReview(draft, clarificationMetadata, 8, input(id)));
        var clarification = operations.updateClarification(null, clarificationMetadata, 7,
                new ReviewMemoryInputs.UpdateClarificationMemoryInput("Draft"));
        var changedLineage = new ReviewMemoryOperations.ApplicationMetadata(ReviewMemory.Purpose.CLARIFICATION,
                ReviewMemoryCodecTest.REVISION, 44L, 43L, "Why?", List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> operations.updateClarification(clarification, changedLineage, 8,
                new ReviewMemoryInputs.UpdateClarificationMemoryInput("Changed")));
        assertThat(operations.updateClarification(clarification, clarificationMetadata, 8,
                new ReviewMemoryInputs.UpdateClarificationMemoryInput("Changed")).memoryVersion()).isEqualTo(8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"purpose", "status", "completionCause", "schemaVersion", "memoryVersion", "headSha", "baseSha", "diffHash",
            "sealedAt", "baseReviewRunId", "parentMemoryRunId", "inspectedPaths", "question", "findings", "formalAction"})
    void modelCannotProvideApplicationMetadataOrBaselineContent(String field) {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateClarification(
                "{\"answerDraft\":\"Answer\",\"" + field + "\":null}"));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateReview(
                "{\"review\":{\"summary\":\"Draft\",\"openQuestions\":[],\"findings\":[]},\"removals\":[],\"" + field + "\":null}"));
    }

    private static ReviewMemoryInputs.UpdateReviewMemoryInput input(String existingId) {
        var finding = new ReviewMemoryInputs.FindingInput(existingId, ReviewMemory.Severity.MEDIUM,
                "correctness", "Race", "Observed duplicate assignment", "Lock assignment", null, null, null, null);
        return new ReviewMemoryInputs.UpdateReviewMemoryInput(new ReviewMemoryInputs.ReviewContentInput("Draft", List.of(), List.of(finding)), List.of());
    }
}
