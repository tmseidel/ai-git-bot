package org.remus.giteabot.prworkflow.agentreview.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReviewMemoryCodecTest {

    private final ReviewMemoryCodec codec = new ReviewMemoryCodec();
    static final RevisionKey REVISION = new RevisionKey("a".repeat(40), "b".repeat(40), "sha256:" + "c".repeat(64));
    private static final String GOLDEN = """
            {"schemaVersion":1,"memoryVersion":1,"purpose":"REVIEW","status":"DRAFT","completionCause":null,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","baseSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","diffHash":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","sealedAt":null,"baseReviewRunId":null,"parentMemoryRunId":null,"inspectedPaths":["src/A.java"],"review":{"summary":"Résumé 😀\\n","openQuestions":[],"findings":[]},"clarification":null,"insufficientReason":null}
            """.strip();

    @Test
    void canonicalUtf8BytesHaveAnIndependentGoldenHashAndReloadWithoutMutableAliases() {
        var paths = new ArrayList<>(List.of("src/A.java", "src/A.java"));
        var snapshot = new ReviewMemory(1, 1, ReviewMemory.Purpose.REVIEW, ReviewMemory.Status.DRAFT,
                null, REVISION, null, null, null, paths,
                new ReviewMemory.ReviewContent("Résumé 😀\n", List.of(), List.of()), null, null);
        paths.clear();

        var encoded = codec.encode(snapshot);

        assertThat(encoded.json()).isEqualTo(GOLDEN);
        assertThat(encoded.utf8()).isEqualTo(GOLDEN.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.sha256()).isEqualTo("sha256:8cd5a8e6ec3b1e19436c1a9ef00b99e5f6e815d381206b795c5fbe67deb95b5d");
        assertThat(codec.decode(encoded.utf8())).isEqualTo(snapshot);
        byte[] copy = encoded.utf8();
        copy[0] = 0;
        assertThat(encoded.json()).isEqualTo(GOLDEN);
        assertThat(snapshot.inspectedPaths()).containsExactly("src/A.java");
    }

    @Test
    void terminalClarificationHasIndependentGoldenBytesIncludingTimeNullsAndUnnormalizedUnicode() {
        String golden = """
                {"schemaVersion":1,"memoryVersion":7,"purpose":"CLARIFICATION","status":"COMPLETE","completionCause":"VOLUNTARY","headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","baseSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","diffHash":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","sealedAt":"2026-09-01T12:34:56.123456789Z","baseReviewRunId":42,"parentMemoryRunId":43,"inspectedPaths":[],"review":null,"clarification":{"question":"Why?","answer":"Use \\\"é\\\" / é 😀\\nnext\\tline"},"insufficientReason":null}
                """.strip();
        var memory = new ReviewMemory(1, 7, ReviewMemory.Purpose.CLARIFICATION, ReviewMemory.Status.COMPLETE,
                ReviewMemory.CompletionCause.VOLUNTARY, REVISION, Instant.parse("2026-09-01T12:34:56.123456789Z"),
                42L, 43L, List.of(), null, new ReviewMemory.ClarificationContent("Why?", "Use \"é\" / e\u0301 😀\nnext\tline"), null);
        var encoded = codec.encode(memory);
        assertThat(encoded.utf8()).isEqualTo(golden.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.sha256()).isEqualTo("sha256:8083be732190a4c83202159dc01762681db742cdffb4b9bc5fabe319734c04cd");
        assertThat(codec.decode(encoded.utf8())).isEqualTo(memory);
    }

    @Test
    void canonicalEscapingUsesFixedControlsAndLeavesSlashesHtmlAndUnicodeLiteral() {
        var memory = new ReviewMemory(1, 1, ReviewMemory.Purpose.REVIEW, ReviewMemory.Status.DRAFT,
                null, REVISION, null, null, null, List.of(),
                new ReviewMemory.ReviewContent("\"\\\b\t\n\f\r\u001a\u001b<>&/\u2028é e\u0301 😀", List.of(), List.of()), null, null);
        assertThat(codec.encode(memory).json()).contains("\"summary\":\"\\\"\\\\\\b\\t\\n\\f\\r\\u001A\\u001B<>&/\u2028é e\u0301 😀\"");
        assertThat(codec.decode(codec.encode(memory).utf8())).isEqualTo(memory);
    }

    @ParameterizedTest
    @MethodSource("corruptStoredValues")
    void unsupportedCorruptOrNoncanonicalStorageFailsClosed(String json) {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    static Stream<String> corruptStoredValues() {
        return Stream.of(
                GOLDEN.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                GOLDEN.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
                GOLDEN.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                GOLDEN.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                GOLDEN.replace("\"memoryVersion\":1", "\"memoryVersion\":0"),
                GOLDEN.replace("\"memoryVersion\":1", "\"memoryVersion\":1e0"),
                GOLDEN.replace("\"memoryVersion\":1", "\"memoryVersion\":9223372036854775808"),
                GOLDEN.replace("\"purpose\":\"REVIEW\"", "\"purpose\":\"CLARIFICATION\""),
                GOLDEN.replace("\"status\":\"DRAFT\"", "\"status\":\"COMPLETE\""),
                GOLDEN.replace("\"completionCause\":null", "\"completionCause\":\"FORCED\""),
                GOLDEN.replace("\"baseReviewRunId\":null,", ""),
                GOLDEN.replace("\"clarification\":null", "\"clarification\":{\"question\":\"Why?\",\"answer\":\"No\"}"),
                GOLDEN.replace("\"summary\":", "\"unknown\":null,\"summary\":"),
                GOLDEN.replace("\"summary\":", "\"summary\":\"ignored\",\"summary\":"),
                GOLDEN.replace("\"review\":", "\"extra\":0,\"review\":"),
                GOLDEN + " {}", GOLDEN + " null", " " + GOLDEN, GOLDEN + "\n", "\ufeff" + GOLDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "null", "{}", "{\"answerDraft\":true}", "{\"answerDraft\":12}",
            "{\"answerDraft\":\"first\",\"answerDraft\":\"second\"}", "{\"answerDraft\":\"ok\"} {}",
            "{\"answerDraft\":\"ok\"} null", "{/*comment*/\"answerDraft\":\"ok\"}", "{answerDraft:\"ok\"}",
            "{\"answerDraft\":\"ok\",}", "{\"answerDraft\":\"ok\",\"status\":\"COMPLETE\"}"})
    void originalWireJsonIsStrictBeforeAnyTreeNormalization(String json) {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateClarification(json));
    }

    @Test
    void parsingRejectsOversizedMalformedUtf8AndDeepInputsWithoutEchoingPayloads() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(new byte[]{(byte) 0xc3, 0x28}));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(new byte[96_001])).withMessageContaining("size");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateClarification(" ".repeat(24_001)))
                .withMessageContaining("size");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateClarification(
                "{\"answerDraft\":" + "[".repeat(13) + "0" + "]".repeat(13) + "}"))
                .withMessage("Invalid JSON");
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateClarification("{\"sentinel-secret\":\"payload\"}"))
                .withMessage("Unknown JSON field");
        assertThat(codec.readUpdateClarification(" { \"answerDraft\" : \"ok\" } ").answerDraft()).isEqualTo("ok");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.0", "1e0", "\"1\"", "true", "2147483648", "9223372036854775808", "NaN"})
    void findingLineNumbersAreNeverCoercedOrNarrowed(String line) {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateReview("""
                {"review":{"summary":"Draft","openQuestions":[],"findings":[
                  {"severity":"MEDIUM","category":"correctness","title":"Race","description":"Evidence",
                   "recommendation":"Lock","file":"src/A.java","line":%s}]},"removals":[]}
                """.formatted(line)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"result\":\"DRAFT\"}",
            "{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\" \"}",
            "{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"Need evidence\",\"review\":null}",
            "{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"Need evidence\",\"removals\":[]}",
            "{\"result\":\"COMPLETE\",\"review\":{\"summary\":\"\",\"openQuestions\":[],\"findings\":[]},\"removals\":[]}",
            "{\"result\":\"COMPLETE\",\"review\":{\"summary\":\"Good\",\"openQuestions\":[\"Unresolved?\"],\"findings\":[]},\"removals\":[]}",
            "{\"result\":\"COMPLETE\",\"review\":{\"summary\":\"Good\",\"openQuestions\":[],\"findings\":[]},\"removals\":[],\"insufficientReason\":null}"})
    void reviewSubmitBranchesCannotBeMixedOrIncomplete(String json) {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readSubmitReview(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"result\":\"DRAFT\",\"answer\":\"Answer\"}", "{\"result\":\"COMPLETE\",\"answer\":\" \"}",
            "{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"Need evidence\",\"answer\":null}",
            "{\"result\":\"INSUFFICIENT_CONTEXT\",\"insufficientReason\":\"Need evidence\",\"answerDraft\":\"Ignored?\"}",
            "{\"result\":\"COMPLETE\",\"answer\":\"Answer\",\"insufficientReason\":null}",
            "{\"result\":\"COMPLETE\",\"answer\":\"Answer\",\"formalAction\":\"APPROVE\"}"})
    void clarificationSubmitBranchesCannotBeMixedOrAuthorizeActions(String json) {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readSubmitClarification(json));
    }

    @Test
    void findingsCannotInventPersistentIdsSeverityOrUnknownFields() {
        String template = """
                {"review":{"summary":"Draft","openQuestions":[],"findings":[
                  {"severity":"MEDIUM","category":"correctness","title":"Race","description":"Evidence",
                   "recommendation":"Lock"%s}]},"removals":[]}
                """;
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateReview(template.formatted(",\"id\":\"model-id\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateReview(template.formatted(",\"severity\":\"LOW\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateReview(template.formatted(",\"extra\":null")));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.readUpdateReview(template.formatted("").replace("MEDIUM", "CRITICAL")));
        assertThat(codec.readUpdateReview(template.formatted(",\"file\":null,\"line\":null")).review().findings().getFirst().existingId()).isNull();
        var located = codec.readUpdateReview(template.formatted(",\"file\":\"src/A.java\",\"line\":2147483647,\"cwe\":\"CWE-362\",\"owasp\":\"A01:2021\""))
                .review().findings().getFirst();
        assertThat(located.line()).isEqualTo(2_147_483_647);
        assertThat(located.cwe()).isEqualTo("CWE-362");
        assertThat(located.owasp()).isEqualTo("A01:2021");
    }
}
