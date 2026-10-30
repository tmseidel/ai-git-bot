package org.remus.giteabot.prworkflow.agentreview.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewMemoryValidationTest {
    private final ReviewMemoryCodec codec = new ReviewMemoryCodec();

    private record TextLimit(String name, int limit, Consumer<String> construct) {
        @Override public String toString() { return name; }
    }

    static Stream<TextLimit> textLimits() {
        return Stream.of(
                new TextLimit("summary", 4_000, s -> new ReviewMemory.ReviewContent(s, List.of(), List.of())),
                new TextLimit("open question", 1_000, s -> new ReviewMemory.ReviewContent("", List.of(s), List.of())),
                new TextLimit("clarification question", 8_000, s -> new ReviewMemory.ClarificationContent(s, "")),
                new TextLimit("answer", 16_000, s -> new ReviewMemory.ClarificationContent("Why?", s)),
                new TextLimit("answer draft", 16_000, ReviewMemoryInputs.UpdateClarificationMemoryInput::new),
                new TextLimit("title", 256, s -> finding("F1", s, "Evidence", "Fix", null, null)),
                new TextLimit("description", 2_000, s -> finding("F1", "Title", s, "Fix", null, null)),
                new TextLimit("recommendation", 2_000, s -> finding("F1", "Title", "Evidence", s, null, null)),
                new TextLimit("path", 1_024, s -> finding("F1", "Title", "Evidence", "Fix", s, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textLimits")
    void fieldLimitsAcceptExactlyTheNormativeBoundAndRejectOneMore(TextLimit field) {
        assertThatCode(() -> field.construct().accept("x".repeat(field.limit()))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> field.construct().accept("x".repeat(field.limit() + 1)));
    }

    @Test
    void serializedLimitCountsMetadataAndJsonEscapingNotOnlyRawText() {
        var exact = clarification("q".repeat(8_000), "a".repeat(15_538));
        assertThat(codec.encode(exact).json()).hasSize(24_000);
        assertThat(codec.decode(codec.encode(exact).utf8())).isEqualTo(exact);
        assertThatIllegalArgumentException().isThrownBy(() -> codec.validate(clarification("q".repeat(8_000), "a".repeat(15_539))))
                .withMessageContaining("serialized");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.validate(clarification("Why?", "\"".repeat(12_000))))
                .withMessageContaining("serialized");
    }

    @Test
    void countLimitsAndAllExposedListsAreBoundedImmutableAndAliasFree() {
        var findings = new ArrayList<>(IntStream.range(0, 50).mapToObj(i -> finding("F" + i, "Title", "Evidence", "Fix", null, null)).toList());
        var questions = new ArrayList<>(Collections.nCopies(20, "Unresolved?"));
        var paths = new ArrayList<>(IntStream.range(0, 100).mapToObj(i -> "src/" + i).toList());
        var content = new ReviewMemory.ReviewContent("Draft", questions, findings);
        var snapshot = review(content, paths, ReviewMemory.Status.DRAFT, null, null, null);
        codec.validate(snapshot);
        findings.clear();
        questions.clear();
        paths.clear();
        assertThat(snapshot.review().findings()).hasSize(50);
        assertThat(snapshot.review().openQuestions()).hasSize(20);
        assertThat(snapshot.inspectedPaths()).hasSize(100);
        assertThatThrownBy(() -> snapshot.review().findings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.review().openQuestions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.inspectedPaths().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory.ReviewContent("", List.of(),
                Collections.nCopies(51, finding("F1", "T", "D", "", null, null)))).withMessageContaining("count");
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory.ReviewContent("", Collections.nCopies(21, "?"), List.of()))
                .withMessageContaining("count");
        assertThatIllegalArgumentException().isThrownBy(() -> review(new ReviewMemory.ReviewContent("", List.of(), List.of()),
                IntStream.range(0, 101).mapToObj(i -> "src/" + i).toList(), ReviewMemory.Status.DRAFT, null, null, null))
                .withMessageContaining("count");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/absolute", "C:/drive", "C:relative", "a\\b", "../escape", "a/../b", "./a", "a/./b",
            "a//b", "a/", "a\nb", "a\u0000b"})
    void pathsCannotEscapeOrHaveAmbiguousComponents(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> finding("F1", "Title", "Evidence", "Fix", path, 1));
    }

    @Test
    void unknownLocationsAreOmittedAndKnownLinesMustBePositiveExactIntegers() {
        var unknown = finding("F1", "Title", "Evidence", "", null, null);
        var memory = review(new ReviewMemory.ReviewContent("Good", List.of(), List.of(unknown)), List.of("z", "a", "z"),
                ReviewMemory.Status.COMPLETE, ReviewMemory.CompletionCause.VOLUNTARY, Instant.EPOCH, null);
        var encoded = codec.encode(memory);
        assertThat(encoded.json()).contains("\"id\":\"F1\",\"severity\":\"LOW\",\"category\":\"correctness\",\"title\":\"Title\",\"description\":\"Evidence\",\"recommendation\":\"\",\"cwe\":null,\"owasp\":null");
        assertThat(encoded.json()).doesNotContain("\"file\":", "\"line\":");
        assertThat(memory.inspectedPaths()).containsExactly("a", "z");
        assertThat(codec.decode(encoded.utf8())).isEqualTo(memory);
        assertThatIllegalArgumentException().isThrownBy(() -> finding("F1", "Title", "Evidence", "", null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> finding("F1", "Title", "Evidence", "", "file", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> finding("F1", "Title", "Evidence", "", "file", -1));
    }

    @Test
    void purposeLifecycleAndCompletenessAreOneConsistentUnion() {
        var draft = new ReviewMemory.ReviewContent("", List.of("Question?"), List.of());
        var ready = new ReviewMemory.ReviewContent("Good", List.of(), List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> review(draft, List.of(), ReviewMemory.Status.COMPLETE,
                ReviewMemory.CompletionCause.VOLUNTARY, Instant.EPOCH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> review(new ReviewMemory.ReviewContent("Summary", List.of("?"), List.of()),
                List.of(), ReviewMemory.Status.COMPLETE, ReviewMemory.CompletionCause.VOLUNTARY, Instant.EPOCH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> review(ready, List.of(), ReviewMemory.Status.COMPLETE, null, Instant.EPOCH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> review(ready, List.of(), ReviewMemory.Status.COMPLETE,
                ReviewMemory.CompletionCause.VOLUNTARY, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> review(ready, List.of(), ReviewMemory.Status.COMPLETE,
                ReviewMemory.CompletionCause.VOLUNTARY, Instant.EPOCH, "Reason"));
        assertThatIllegalArgumentException().isThrownBy(() -> review(ready, List.of(), ReviewMemory.Status.DRAFT,
                ReviewMemory.CompletionCause.FORCED, Instant.EPOCH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> review(ready, List.of(), ReviewMemory.Status.DRAFT, null, null, "Reason"));
        assertThatIllegalArgumentException().isThrownBy(() -> review(draft, List.of(), ReviewMemory.Status.INSUFFICIENT_CONTEXT,
                ReviewMemory.CompletionCause.FORCED, Instant.EPOCH, " "));
        assertThatCode(() -> review(draft, List.of(), ReviewMemory.Status.INSUFFICIENT_CONTEXT,
                ReviewMemory.CompletionCause.FORCED, Instant.EPOCH, "Missing evidence")).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory(1, 1, ReviewMemory.Purpose.REVIEW,
                ReviewMemory.Status.DRAFT, null, ReviewMemoryCodecTest.REVISION, null, null, null, List.of(), ready,
                new ReviewMemory.ClarificationContent("Why?", ""), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory(1, 1, ReviewMemory.Purpose.CLARIFICATION,
                ReviewMemory.Status.DRAFT, null, ReviewMemoryCodecTest.REVISION, null, null, null, List.of(), ready, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory(1, 1, ReviewMemory.Purpose.CLARIFICATION,
                ReviewMemory.Status.COMPLETE, ReviewMemory.CompletionCause.VOLUNTARY, ReviewMemoryCodecTest.REVISION,
                Instant.EPOCH, null, null, List.of(), null, new ReviewMemory.ClarificationContent("Why?", ""), null));
    }

    @Test
    void invalidUnicodeDuplicateIdsAndIncompleteRevisionKeysAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory.ReviewContent("\ud800", List.of(), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory.ClarificationContent("Why?", "\udc00"));
        var f = finding("F1", "Title", "Evidence", "", null, null);
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewMemory.ReviewContent("", List.of(), List.of(f, f)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RevisionKey("abc123", "b".repeat(40), "sha256:" + "c".repeat(64)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RevisionKey("a".repeat(40), "b".repeat(40), "sha256:123"));
        assertThatCode(() -> new RevisionKey("a".repeat(64), "b".repeat(64), "sha256:" + "c".repeat(64))).doesNotThrowAnyException();
    }

    private static ReviewMemory.Finding finding(String id, String title, String description, String recommendation, String file, Integer line) {
        return new ReviewMemory.Finding(id, ReviewMemory.Severity.LOW, "correctness", title, description, recommendation, file, line, null, null);
    }

    private static ReviewMemory clarification(String question, String answer) {
        return new ReviewMemory(1, 1, ReviewMemory.Purpose.CLARIFICATION, ReviewMemory.Status.DRAFT, null,
                ReviewMemoryCodecTest.REVISION, null, null, null, List.of(), null, new ReviewMemory.ClarificationContent(question, answer), null);
    }

    private static ReviewMemory review(ReviewMemory.ReviewContent content, List<String> paths, ReviewMemory.Status status,
                                       ReviewMemory.CompletionCause cause, Instant sealedAt, String reason) {
        return new ReviewMemory(1, 1, ReviewMemory.Purpose.REVIEW, status, cause, ReviewMemoryCodecTest.REVISION,
                sealedAt, null, null, paths, content, null, reason);
    }
}
