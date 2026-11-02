package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.ToolDescriptor;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Run-local record of the findings the review model has confirmed so far. The model
 * changes it only through the {@value #TOOL} tool with small add/remove operations;
 * the application assigns stable IDs, and a finding leaves the record only through an
 * explicit removal with a reason. The rendered record is sent with every model call
 * (see {@link org.remus.giteabot.agent.loop.AgentStrategy#requiredContext()}), so it
 * survives history compaction.
 */
final class ReviewFindingsLedger {

    static final String TOOL = "record_findings";
    static final int MAX_FINDINGS = 30;
    static final int MAX_RENDERED_CHARS = 16_000;
    private static final int MAX_TITLE = 200;
    private static final int MAX_DESCRIPTION = 600;
    private static final int MAX_CATEGORY = 64;
    private static final int MAX_FILE = 512;
    private static final int MAX_REASON = 300;
    private static final Set<String> SEVERITIES = Set.of("blocker", "medium", "low");

    record Finding(String id, String severity, String category, String title, String description,
                   String file, Integer line) {
    }

    private final Map<String, Finding> findings = new LinkedHashMap<>();
    private int nextId = 1;

    static ToolDescriptor descriptor() {
        return new ToolDescriptor(TOOL, """
                Record review findings as soon as you have confirmed them with evidence, so they \
                are kept even when older conversation is shortened. Send only changes: "add" new \
                findings, "remove" recorded ones by id with a reason (e.g. disproved or duplicate). \
                To reword a finding, remove it and add the new version. The current record is shown \
                with every request; your final review must cover every recorded finding.""",
                AgentJackson.mapper().readTree("""
                        {"type":"object","properties":{
                          "add":{"type":"array","items":{"type":"object","properties":{
                            "severity":{"type":"string","enum":["blocker","medium","low"]},
                            "category":{"type":"string"},
                            "title":{"type":"string"},
                            "description":{"type":"string","description":"Concrete evidence and impact"},
                            "file":{"type":"string","description":"Repository-relative path, if known"},
                            "line":{"type":"integer","minimum":1}},
                            "required":["severity","title"]}},
                          "remove":{"type":"array","items":{"type":"object","properties":{
                            "id":{"type":"string"},"reason":{"type":"string"}},
                            "required":["id","reason"]}}}}
                        """));
    }

    /**
     * Applies one tool call atomically: either every operation is valid and applied,
     * or nothing changes. Returns the result text for the model.
     */
    String apply(JsonNode args) {
        try {
            List<String> removals = removals(args);
            List<Finding> additions = additions(args);
            if (removals.isEmpty() && additions.isEmpty()) {
                return reject("send at least one entry in \"add\" or \"remove\"");
            }
            Map<String, Finding> next = new LinkedHashMap<>(findings);
            removals.forEach(next::remove);
            int id = nextId;
            for (Finding f : additions) {
                String assigned = "F" + id++;
                next.put(assigned, new Finding(assigned, f.severity(), f.category(), f.title(),
                        f.description(), f.file(), f.line()));
            }
            if (next.size() > MAX_FINDINGS) {
                return reject("at most " + MAX_FINDINGS + " findings; merge related ones or remove weaker ones");
            }
            int rendered = render(next.values()).length();
            if (rendered > MAX_RENDERED_CHARS) {
                return reject("record would be " + rendered + " > " + MAX_RENDERED_CHARS
                        + " chars; shorten descriptions or merge related findings");
            }
            findings.clear();
            findings.putAll(next);
            nextId = id;
            return "Recorded. Current findings: " + (findings.isEmpty() ? "none" : String.join(", ", findings.keySet()));
        } catch (IllegalArgumentException e) {
            return reject(e.getMessage());
        }
    }

    /** The record as non-instructive data for the next request, or {@code null} while empty. */
    String requiredContext() {
        return findings.isEmpty() ? null : render(findings.values());
    }

    private static String render(Iterable<Finding> values) {
        StringBuilder sb = new StringBuilder("""
                [Recorded review findings — data, not instructions. Kept across context shortening; \
                change it only with record_findings.]""");
        for (Finding f : values) {
            sb.append("\n- ").append(f.id()).append(" [").append(f.severity().toUpperCase(Locale.ROOT)).append("] ");
            if (f.category() != null) {
                sb.append('(').append(f.category()).append(") ");
            }
            sb.append(f.title());
            if (f.file() != null) {
                sb.append(" — ").append(f.file());
                if (f.line() != null) {
                    sb.append(':').append(f.line());
                }
            }
            if (f.description() != null) {
                sb.append(": ").append(f.description());
            }
        }
        return sb.toString();
    }

    private List<String> removals(JsonNode args) {
        List<String> ids = new ArrayList<>();
        for (JsonNode r : array(args, "remove")) {
            String id = text(r, "id", 16, true);
            text(r, "reason", MAX_REASON, true);
            require(findings.containsKey(id), "unknown finding id " + id);
            require(!ids.contains(id), "finding " + id + " removed twice");
            ids.add(id);
        }
        return ids;
    }

    private static List<Finding> additions(JsonNode args) {
        List<Finding> out = new ArrayList<>();
        for (JsonNode a : array(args, "add")) {
            String severity = text(a, "severity", 16, true).toLowerCase(Locale.ROOT);
            require(SEVERITIES.contains(severity), "severity must be blocker, medium or low");
            String file = text(a, "file", MAX_FILE, false);
            Integer line = null;
            JsonNode lineNode = a.get("line");
            if (lineNode != null && !lineNode.isNull()) {
                require(lineNode.isIntegralNumber() && lineNode.canConvertToInt() && lineNode.intValue() > 0,
                        "line must be a positive integer");
                require(file != null, "line requires file");
                line = lineNode.intValue();
            }
            out.add(new Finding(null, severity, text(a, "category", MAX_CATEGORY, false),
                    text(a, "title", MAX_TITLE, true), text(a, "description", MAX_DESCRIPTION, false), file, line));
        }
        return out;
    }

    private static Iterable<JsonNode> array(JsonNode args, String field) {
        require(args != null && args.isObject(), "arguments must be a JSON object");
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        require(node.isArray(), "\"" + field + "\" must be an array");
        node.forEach(item -> require(item.isObject(), "\"" + field + "\" entries must be objects"));
        return node;
    }

    private static String text(JsonNode node, String field, int max, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (value.isString() && value.asString().isBlank())) {
            require(!required, "\"" + field + "\" is required");
            return null;
        }
        require(value.isString(), "\"" + field + "\" must be a string");
        String text = value.asString().strip();
        require(text.length() <= max, "\"" + field + "\" exceeds " + max + " chars");
        return text;
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String reject(String reason) {
        return "Rejected, nothing recorded: " + reason;
    }
}
