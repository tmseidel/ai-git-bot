package org.remus.giteabot.prworkflow.agentreview;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned, bounded review state. Storage bytes are ordinary JSON, not a canonical encoding contract. */
public record ReviewDraft(int schemaVersion, long version, Status status, int nextFindingId,
                          boolean confirmedComplete, String summary, List<String> openQuestions,
                          List<Finding> findings, String insufficientReason) {
    public static final int MAX_SERIALIZED_CHARS = 16_000;
    public static final int MAX_FINDINGS = 30;
    public enum Status { DRAFT, COMPLETE, INSUFFICIENT_CONTEXT }
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                            .maxDocumentLength(MAX_SERIALIZED_CHARS).maxStringLength(4_000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    /** A confirmed finding with an application-assigned identity and concrete evidence. */
    public record Finding(String id, String severity, String category, String title, String description,
                          String file, Integer line) {
        public Finding {
            text(id, 16, true, "id");
            require(id.matches("F[1-9][0-9]*"), "Invalid finding id");
            require(severity != null && Set.of("blocker", "medium", "low").contains(severity), "Invalid severity");
            text(category, 64, false, "category");
            text(title, 200, true, "title");
            text(description, 600, true, "description");
            text(file, 512, false, "file");
            if (file != null) {
                require(!file.startsWith("/") && !file.contains("\\") && !file.contains(":")
                        && file.chars().noneMatch(Character::isISOControl), "Invalid finding file");
                for (String part : file.split("/", -1)) {
                    require(!part.isBlank() && !part.equals(".") && !part.equals(".."), "Invalid finding file");
                }
            }
            require(line == null || line > 0 && file != null, "line requires file and a positive integer");
        }
    }

    public ReviewDraft {
        require(schemaVersion == 1 && version >= 0 && status != null && nextFindingId > 0, "Invalid draft metadata");
        text(summary, 4_000, status == Status.COMPLETE, "summary");
        require(openQuestions != null && openQuestions.size() <= 20, "Invalid openQuestions");
        openQuestions = List.copyOf(openQuestions);
        openQuestions.forEach(q -> text(q, 1_000, true, "openQuestion"));
        require(findings != null && findings.size() <= MAX_FINDINGS, "Invalid findings count");
        findings = List.copyOf(findings);
        var ids = new HashSet<String>();
        for (Finding finding : findings) {
            require(ids.add(finding.id()), "Duplicate finding id");
            require(Long.parseLong(finding.id().substring(1)) < nextFindingId, "Invalid nextFindingId");
        }
        require(!confirmedComplete || openQuestions.isEmpty(), "Complete record has open questions");
        require(status != Status.COMPLETE || confirmedComplete && openQuestions.isEmpty(), "Incomplete review");
        text(insufficientReason, 1_000, status == Status.INSUFFICIENT_CONTEXT, "insufficientReason");
        require(status == Status.INSUFFICIENT_CONTEXT || insufficientReason == null, "Unexpected insufficientReason");
    }

    /** Serializes the validated state and enforces the complete JSON size limit. */
    public String json() {
        String json = JSON.writeValueAsString(this);
        require(json.length() <= MAX_SERIALIZED_CHARS, "record would be " + json.length() + " > "
                + MAX_SERIALIZED_CHARS + " chars; shorten descriptions or merge related findings");
        return json;
    }

    /** Strict supported-schema reader; harmless whitespace/order differences are not corruption. */
    public static ReviewDraft read(String json) {
        require(json != null && json.length() <= MAX_SERIALIZED_CHARS, "Invalid stored draft size");
        try {
            ReviewDraft draft = JSON.readValue(json, ReviewDraft.class);
            require(draft != null, "Missing draft");
            draft.json();
            return draft;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid stored review draft", e);
        }
    }

    /** Renders only a sealed structured result; assistant prose is never merged into it. */
    public String render() {
        require(status == Status.COMPLETE, "Only COMPLETE reviews can be rendered");
        var out = new StringBuilder("## Structured code review\n\n").append(markdown(summary));
        for (Finding finding : findings) {
            out.append("\n\n### ").append(finding.id()).append(" [")
                    .append(finding.severity().toUpperCase(java.util.Locale.ROOT)).append("] ")
                    .append(markdown(finding.title()));
            if (finding.file() != null) {
                out.append("\n\nFile: ").append(markdown(finding.file()));
                if (finding.line() != null) out.append(':').append(finding.line());
            }
            out.append("\n\n").append(markdown(finding.description()));
        }
        require(out.length() <= 24_000, "Rendered review exceeds 24000 chars");
        return out.toString();
    }

    private static String markdown(String text) {
        return text.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("`", "\\`").replace("*", "\\*").replace("[", "\\[").replace("]", "\\]");
    }

    static tools.jackson.databind.JsonNode readInput(String json) {
        require(json != null && json.length() <= MAX_SERIALIZED_CHARS, "Invalid input size");
        try {
            var node = JSON.readTree(json);
            require(node != null && node.isObject(), "Expected an input object");
            return node;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid structured review input", e);
        }
    }

    static void text(String value, int max, boolean required, String field) {
        require(value != null || !required, "\"" + field + "\" is required");
        if (value == null) return;
        require(value.length() <= max && (!required || !value.isBlank()), "Invalid \"" + field + "\" (max " + max + " chars)");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(++i < value.length() && Character.isLowSurrogate(value.charAt(i)), "Invalid Unicode in " + field);
            } else {
                require(!Character.isLowSurrogate(c), "Invalid Unicode in " + field);
            }
        }
    }

    static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
