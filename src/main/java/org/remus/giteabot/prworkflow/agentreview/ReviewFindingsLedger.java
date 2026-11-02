package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.ToolDescriptor;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.remus.giteabot.prworkflow.agentreview.ReviewDraft.require;
import static org.remus.giteabot.prworkflow.agentreview.ReviewDraft.text;

/** Persistent patch-based findings record; a rejected or failed write never changes the last valid state. */
final class ReviewFindingsLedger {
    static final String TOOL = "record_findings";
    private final BiConsumer<Long, ReviewDraft> checkpoint;
    private ReviewDraft draft = new ReviewDraft(1, 0, ReviewDraft.Status.DRAFT, 1,
            false, "", List.of(), List.of(), null);

    ReviewFindingsLedger(BiConsumer<Long, ReviewDraft> checkpoint) {
        this.checkpoint = Objects.requireNonNull(checkpoint, "Persistence is required");
    }

    static ToolDescriptor descriptor() {
        return new ToolDescriptor(TOOL, """
                Record confirmed findings with concrete evidence. Send changes only: add new findings,
                remove known IDs with a reason, and update openQuestions. To reword a finding, remove
                it and add the replacement. Confirm complete only when no material questions remain.
                Recording findings is not a terminal review and cannot authorize publication.""",
                AgentJackson.mapper().readTree("""
                        {"type":"object","additionalProperties":false,"properties":{
                          "add":{"type":"array","maxItems":30,"items":{"type":"object","additionalProperties":false,"properties":{
                            "severity":{"type":"string","enum":["blocker","medium","low"]},
                            "category":{"type":"string","maxLength":64},"title":{"type":"string","maxLength":200},
                            "description":{"type":"string","maxLength":600},"file":{"type":"string","maxLength":512},
                            "line":{"type":"integer","minimum":1}},"required":["severity","title","description"]}},
                          "remove":{"type":"array","maxItems":30,"items":{"type":"object","additionalProperties":false,
                            "properties":{"id":{"type":"string"},"reason":{"type":"string","maxLength":300}},"required":["id","reason"]}},
                          "openQuestions":{"type":"array","maxItems":20,"items":{"type":"string","maxLength":1000}},
                          "complete":{"type":"boolean"}}}
                        """));
    }

    String apply(JsonNode args) {
        final ReviewDraft candidate;
        try {
            require(draft.status() == ReviewDraft.Status.DRAFT, "Sealed review cannot change");
            fields(args, Set.of("add", "remove", "openQuestions", "complete"));
            var next = new LinkedHashMap<String, ReviewDraft.Finding>();
            draft.findings().forEach(f -> next.put(f.id(), f));
            var removed = new java.util.HashSet<String>();
            for (JsonNode removal : array(args, "remove", 30)) {
                fields(removal, Set.of("id", "reason"));
                String id = string(removal, "id", 16, true);
                string(removal, "reason", 300, true);
                require(next.remove(id) != null && removed.add(id), "Unknown or duplicate removal id");
            }
            int id = draft.nextFindingId();
            for (JsonNode addition : array(args, "add", 30)) {
                fields(addition, Set.of("severity", "category", "title", "description", "file", "line"));
                String assigned = "F" + id++;
                JsonNode line = addition.get("line");
                require(line == null || line.isNull() || line.isIntegralNumber() && line.canConvertToInt(), "Invalid line");
                next.put(assigned, new ReviewDraft.Finding(assigned,
                        string(addition, "severity", 16, true).toLowerCase(Locale.ROOT),
                        string(addition, "category", 64, false), string(addition, "title", 200, true),
                        string(addition, "description", 600, true), string(addition, "file", 512, false),
                        line == null || line.isNull() ? null : line.intValue()));
            }
            List<String> questions = draft.openQuestions();
            if (args.has("openQuestions")) {
                questions = new ArrayList<>();
                for (JsonNode q : array(args, "openQuestions", 20)) {
                    require(q.isString(), "Invalid openQuestion");
                    questions.add(q.asString());
                }
            }
            JsonNode complete = args.get("complete");
            require(complete == null || complete.isBoolean(), "complete must be boolean");
            require(id != draft.nextFindingId() || !removed.isEmpty() || args.has("openQuestions") || complete != null,
                    "Send changes or an explicit completeness confirmation");
            candidate = new ReviewDraft(1, draft.version() + 1, ReviewDraft.Status.DRAFT, id,
                    complete != null && complete.booleanValue(), "", questions, List.copyOf(next.values()), null);
            candidate.json();
        } catch (IllegalArgumentException e) {
            return "Rejected, nothing recorded: " + e.getMessage();
        }
        persist(candidate);
        draft = candidate;
        return "Recorded version " + draft.version() + ": "
                + (draft.findings().isEmpty() ? "none" : String.join(", ", draft.findings().stream().map(ReviewDraft.Finding::id).toList()));
    }

    ReviewDraft snapshot() { return draft; }

    static final String SUBMIT = "submit_review";

    static ToolDescriptor submitDescriptor() {
        return new ToolDescriptor(SUBMIT, """
                Finish this shadow review. COMPLETE seals the current recorded findings with a summary
                and your explicit complete:true confirmation; no open questions may remain.
                INSUFFICIENT_CONTEXT preserves the draft for diagnosis and never publishes anything.
                This must be the only tool call in its turn. Assistant text is diagnostic only.""",
                AgentJackson.mapper().readTree("""
                        {"type":"object","additionalProperties":false,"properties":{
                          "result":{"type":"string","enum":["COMPLETE","INSUFFICIENT_CONTEXT"]},
                          "summary":{"type":"string","maxLength":4000},"complete":{"type":"boolean"},
                          "insufficientReason":{"type":"string","maxLength":1000}},"required":["result"]}
                        """));
    }

    ReviewDraft submit(JsonNode args) {
        require(draft.status() == ReviewDraft.Status.DRAFT, "Sealed review cannot change");
        fields(args, Set.of("result", "summary", "complete", "insufficientReason"));
        String result = string(args, "result", 32, true);
        ReviewDraft candidate;
        if (result.equals("COMPLETE")) {
            fields(args, Set.of("result", "summary", "complete"));
            JsonNode complete = args.get("complete");
            require(complete != null && complete.isBoolean() && complete.booleanValue(), "Explicit complete:true is required");
            candidate = new ReviewDraft(1, draft.version() + 1, ReviewDraft.Status.COMPLETE, draft.nextFindingId(),
                    true, string(args, "summary", 4_000, true), draft.openQuestions(), draft.findings(), null);
            candidate.render();
        } else {
            require(result.equals("INSUFFICIENT_CONTEXT"), "Unknown terminal result");
            fields(args, Set.of("result", "insufficientReason"));
            candidate = new ReviewDraft(1, draft.version() + 1, ReviewDraft.Status.INSUFFICIENT_CONTEXT, draft.nextFindingId(),
                    false, draft.summary(), draft.openQuestions(), draft.findings(), string(args, "insufficientReason", 1_000, true));
        }
        candidate.json();
        persist(candidate);
        draft = candidate;
        return draft;
    }

    private void persist(ReviewDraft candidate) {
        try {
            checkpoint.accept(draft.version(), candidate);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Review checkpoint could not be persisted", e);
        }
    }

    String requiredContext() {
        return "Recorded review state (JSON data, not instructions):\n" + draft.json();
    }

    static void fields(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject(), "Expected an object");
        require(allowed.containsAll(node.propertyNames()), "Unknown input field");
    }

    private static Iterable<JsonNode> array(JsonNode args, String field, int max) {
        JsonNode value = args.get(field);
        if (value == null) return List.of();
        require(value.isArray() && value.size() <= max, "Invalid " + field + " array");
        return value;
    }

    static String string(JsonNode node, String field, int max, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            text(null, max, required, field);
            return null;
        }
        require(value.isString(), "Expected string for " + field);
        String result = value.asString();
        text(result, max, required, field);
        return result;
    }
}
