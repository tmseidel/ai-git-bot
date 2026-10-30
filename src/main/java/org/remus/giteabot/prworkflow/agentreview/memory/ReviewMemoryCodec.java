package org.remus.giteabot.prworkflow.agentreview.memory;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.remus.giteabot.prworkflow.agentreview.memory.ReviewMemoryInputs.*;

/**
 * Strict, bounded schema-1 reader and canonical UTF-8 writer. See doc/REVIEW_MEMORY.md
 * for ordering, null, Unicode, time and supported-reader policy. No injected application
 * mapper can weaken the trust seam. Invalid data throws IllegalArgumentException.
 */
public final class ReviewMemoryCodec {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                            .maxDocumentLength(ReviewMemory.MAX_SERIALIZED_CHARS * 4L)
                            .maxTokenCount(12_000).maxStringLength(ReviewMemory.MAX_ANSWER)
                            .maxNameLength(64).maxNumberLength(19).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(JsonWriteFeature.QUOTE_PROPERTY_NAMES, JsonWriteFeature.WRITE_HEX_UPPER_CASE)
                    .disable(JsonWriteFeature.ESCAPE_NON_ASCII, JsonWriteFeature.ESCAPE_FORWARD_SLASHES,
                            JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Immutable encoded result; byte access always returns a new array. */
    public static final class Encoded {
        private final String json;
        private final String sha256;

        private Encoded(String json, byte[] bytes) {
            this.json = json;
            try {
                sha256 = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required by the Java platform", e);
            }
        }

        /** Canonical JSON for a TEXT column or ephemeral data context. */
        public String json() { return json; }
        /** Lowercase SHA-256 of exactly utf8(), prefixed with sha256:. */
        public String sha256() { return sha256; }
        /** Canonical UTF-8 without BOM or newline; never exposes a mutable internal buffer. */
        public byte[] utf8() { return json.getBytes(StandardCharsets.UTF_8); }
    }

    /** Checks all domain and serialized-size invariants without changing the value. */
    public void validate(ReviewMemory memory) {
        encode(memory);
    }

    /** Canonical bytes and their exact SHA-256. Never truncates. */
    public Encoded encode(ReviewMemory memory) {
        MemoryChecks.require(memory != null, "Missing review memory");
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", memory.schemaVersion());
        root.put("memoryVersion", memory.memoryVersion());
        root.put("purpose", memory.purpose().name());
        root.put("status", memory.status().name());
        root.put("completionCause", memory.completionCause() == null ? null : memory.completionCause().name());
        root.put("headSha", memory.revisionKey().headSha());
        root.put("baseSha", memory.revisionKey().baseSha());
        root.put("diffHash", memory.revisionKey().diffHash());
        root.put("sealedAt", memory.sealedAt() == null ? null : memory.sealedAt().toString());
        root.put("baseReviewRunId", memory.baseReviewRunId());
        root.put("parentMemoryRunId", memory.parentMemoryRunId());
        var paths = root.putArray("inspectedPaths");
        memory.inspectedPaths().forEach(paths::add);
        if (memory.review() == null) {
            root.putNull("review");
        } else {
            var review = root.putObject("review");
            review.put("summary", memory.review().summary());
            var questions = review.putArray("openQuestions");
            memory.review().openQuestions().forEach(questions::add);
            var findings = review.putArray("findings");
            for (var f : memory.review().findings()) {
                var node = findings.addObject();
                node.put("id", f.id());
                node.put("severity", f.severity().name());
                node.put("category", f.category());
                node.put("title", f.title());
                node.put("description", f.description());
                node.put("recommendation", f.recommendation());
                if (f.file() != null) {
                    node.put("file", f.file());
                }
                if (f.line() != null) {
                    node.put("line", f.line());
                }
                node.put("cwe", f.cwe());
                node.put("owasp", f.owasp());
            }
        }
        if (memory.clarification() == null) {
            root.putNull("clarification");
        } else {
            var clarification = root.putObject("clarification");
            clarification.put("question", memory.clarification().question());
            clarification.put("answer", memory.clarification().answer());
        }
        root.put("insufficientReason", memory.insufficientReason());
        String json = JSON.writeValueAsString(root);
        MemoryChecks.require(json.length() <= ReviewMemory.MAX_SERIALIZED_CHARS, "Review memory exceeds serialized limit");
        return new Encoded(json, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Loads only supported, canonical stored bytes; no silent normalization or upcasting. */
    public ReviewMemory decode(byte[] utf8) {
        MemoryChecks.require(utf8 != null && utf8.length <= ReviewMemory.MAX_SERIALIZED_CHARS * 4, "Invalid UTF-8 size");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8");
        }
        var root = parse(text);
        fields(root, "schemaVersion", "memoryVersion", "purpose", "status", "completionCause", "headSha", "baseSha",
                "diffHash", "sealedAt", "baseReviewRunId", "parentMemoryRunId", "inspectedPaths", "review",
                "clarification", "insufficientReason");
        int version = integer(root, "schemaVersion");
        MemoryChecks.require(version == ReviewMemory.SCHEMA_VERSION, "Unsupported review memory schemaVersion");
        var memory = new ReviewMemory(version, number(root, "memoryVersion"), enumeration(root, "purpose", ReviewMemory.Purpose.class),
                enumeration(root, "status", ReviewMemory.Status.class), nullableEnum(root, "completionCause", ReviewMemory.CompletionCause.class),
                new RevisionKey(string(root, "headSha"), string(root, "baseSha"), string(root, "diffHash")),
                instant(root, "sealedAt"), optionalNumber(root, "baseReviewRunId"), optionalNumber(root, "parentMemoryRunId"),
                list(root, "inspectedPaths", ReviewMemory.MAX_PATHS, ReviewMemoryCodec::stringValue),
                absent(root, "review") ? null : review(root.get("review")),
                absent(root, "clarification") ? null : clarification(root.get("clarification")), nullableString(root, "insufficientReason"));
        MemoryChecks.require(Arrays.equals(utf8, encode(memory).utf8()), "Stored review memory is not canonical");
        return memory;
    }

    /** Reads original, untrusted update JSON, not a provider-normalized JSON tree. */
    public UpdateReviewMemoryInput readUpdateReview(String json) {
        var node = parse(json);
        fields(node, "review", "removals");
        return new UpdateReviewMemoryInput(reviewInput(node.get("review")), removals(node));
    }

    /** Reads the exclusive COMPLETE/INSUFFICIENT_CONTEXT review submit union. */
    public SubmitReviewInput readSubmitReview(String json) {
        var node = parse(json);
        Result result = enumeration(node, "result", Result.class);
        if (result == Result.INSUFFICIENT_CONTEXT) {
            fields(node, "result", "insufficientReason");
            return new SubmitReviewInput(result, null, List.of(), string(node, "insufficientReason"));
        }
        fields(node, "result", "review", "removals");
        return new SubmitReviewInput(result, reviewInput(node.get("review")), removals(node), null);
    }

    /** Reads an answer-draft-only update; the question and baseline are never model-owned. */
    public UpdateClarificationMemoryInput readUpdateClarification(String json) {
        var node = parse(json);
        fields(node, "answerDraft");
        return new UpdateClarificationMemoryInput(string(node, "answerDraft"));
    }

    /** Reads the exclusive COMPLETE/INSUFFICIENT_CONTEXT clarification submit union. */
    public SubmitClarificationInput readSubmitClarification(String json) {
        var node = parse(json);
        Result result = enumeration(node, "result", Result.class);
        if (result == Result.INSUFFICIENT_CONTEXT) {
            fields(node, "result", "insufficientReason");
            return new SubmitClarificationInput(result, null, string(node, "insufficientReason"));
        }
        fields(node, "result", "answer");
        return new SubmitClarificationInput(result, string(node, "answer"), null);
    }

    private static ReviewContentInput reviewInput(JsonNode node) {
        fields(node, "summary", "openQuestions", "findings");
        return new ReviewContentInput(string(node, "summary"),
                list(node, "openQuestions", ReviewMemory.MAX_QUESTIONS, ReviewMemoryCodec::stringValue),
                list(node, "findings", ReviewMemory.MAX_FINDINGS, f -> {
                    fields(f, "existingId", "severity", "category", "title", "description", "recommendation", "file", "line", "cwe", "owasp");
                    return new FindingInput(nullableString(f, "existingId"), enumeration(f, "severity", ReviewMemory.Severity.class),
                            string(f, "category"), string(f, "title"), string(f, "description"), string(f, "recommendation"),
                            nullableString(f, "file"), absent(f, "line") ? null : integer(f, "line"), nullableString(f, "cwe"), nullableString(f, "owasp"));
                }));
    }

    private static List<Removal> removals(JsonNode node) {
        return list(node, "removals", ReviewMemory.MAX_FINDINGS, r -> {
            fields(r, "id", "reason");
            return new Removal(string(r, "id"), string(r, "reason"));
        });
    }

    private static ReviewMemory.ReviewContent review(JsonNode node) {
        fields(node, "summary", "openQuestions", "findings");
        return new ReviewMemory.ReviewContent(string(node, "summary"),
                list(node, "openQuestions", ReviewMemory.MAX_QUESTIONS, ReviewMemoryCodec::stringValue),
                list(node, "findings", ReviewMemory.MAX_FINDINGS, ReviewMemoryCodec::finding));
    }

    private static ReviewMemory.Finding finding(JsonNode node) {
        fields(node, "id", "severity", "category", "title", "description", "recommendation", "file", "line", "cwe", "owasp");
        return new ReviewMemory.Finding(string(node, "id"), enumeration(node, "severity", ReviewMemory.Severity.class),
                string(node, "category"), string(node, "title"), string(node, "description"), string(node, "recommendation"),
                nullableString(node, "file"), absent(node, "line") ? null : integer(node, "line"), nullableString(node, "cwe"), nullableString(node, "owasp"));
    }

    private static ReviewMemory.ClarificationContent clarification(JsonNode node) {
        fields(node, "question", "answer");
        return new ReviewMemory.ClarificationContent(string(node, "question"), string(node, "answer"));
    }

    private static JsonNode parse(String text) {
        MemoryChecks.require(text != null && text.length() <= ReviewMemory.MAX_SERIALIZED_CHARS, "Invalid JSON size");
        try {
            JsonNode root = JSON.readTree(text);
            MemoryChecks.require(root != null && root.isObject(), "Expected JSON object");
            return root;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    private static void fields(JsonNode node, String... allowed) {
        MemoryChecks.require(node != null && node.isObject(), "Expected JSON object");
        Set<String> names = Set.of(allowed);
        MemoryChecks.require(names.containsAll(node.propertyNames()), "Unknown JSON field");
    }

    private static boolean absent(JsonNode node, String field) {
        return node.get(field) == null || node.get(field).isNull();
    }

    private static String string(JsonNode node, String field) {
        return stringValue(node.get(field));
    }

    private static String stringValue(JsonNode node) {
        MemoryChecks.require(node != null && node.isString(), "Expected string");
        return node.asString();
    }

    private static String nullableString(JsonNode node, String field) {
        return absent(node, field) ? null : string(node, field);
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        MemoryChecks.require(value != null && value.isIntegralNumber() && value.canConvertToLong(), "Expected exact integer");
        return value.longValue();
    }

    private static int integer(JsonNode node, String field) {
        long value = number(node, field);
        MemoryChecks.require(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE, "Integer overflow");
        return (int) value;
    }

    private static Long optionalNumber(JsonNode node, String field) {
        return absent(node, field) ? null : number(node, field);
    }

    private static <E extends Enum<E>> E enumeration(JsonNode node, String field, Class<E> type) {
        try {
            return Enum.valueOf(type, string(node, field));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static <E extends Enum<E>> E nullableEnum(JsonNode node, String field, Class<E> type) {
        return absent(node, field) ? null : enumeration(node, field, type);
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return absent(node, field) ? null : Instant.parse(string(node, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + field);
        }
    }

    private static <T> List<T> list(JsonNode node, String field, int max, Function<JsonNode, T> read) {
        JsonNode array = node.get(field);
        MemoryChecks.require(array != null && array.isArray() && array.size() <= max, "Invalid " + field + " array");
        List<T> result = new ArrayList<>();
        array.forEach(value -> result.add(read.apply(value)));
        return List.copyOf(result);
    }
}
