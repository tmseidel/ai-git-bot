package org.remus.giteabot.prworkflow.agentreview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Review-local reader for the legacy JSON tool envelope.
 *
 * <p>It exists because the submission tools need their typed {@code arguments} object, while the
 * generic plan parser reduces every call to a positional string vector and drops fields it does not
 * know — evidence and assessment objects would arrive flattened and unusable. Exploration
 * {@code runTools} entries keep going through {@link org.remus.giteabot.agent.issueimpl.AiResponseParser};
 * this reader only decides whether a turn carries submission calls.</p>
 */
final class ReviewLegacyProtocol {

    private static final Logger log = LoggerFactory.getLogger(ReviewLegacyProtocol.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One legacy tool entry, read from the raw JSON envelope. */
    record Entry(String id, String tool, JsonNode arguments, boolean ambiguous) {
    }

    private ReviewLegacyProtocol() {
    }

    /**
     * Tool entries of the response's JSON envelope, in model order. An entry carrying both
     * {@code args} and {@code arguments} is ambiguous: the two conventions disagree about what the
     * call means, so it is reported rather than guessed at.
     */
    static List<Entry> entries(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return List.of();
        }
        int start = aiResponse.indexOf('{');
        if (start < 0) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(aiResponse.substring(start, endOfJson(aiResponse, start)));
        } catch (RuntimeException e) {
            log.debug("Legacy review envelope could not be parsed: {}", e.getMessage());
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        JsonNode runTools = root.get("runTools");
        if (runTools != null && runTools.isArray()) {
            runTools.forEach(node -> addEntry(entries, node));
        }
        JsonNode runTool = root.get("runTool");
        if (runTool != null && runTool.isObject()) {
            addEntry(entries, runTool);
        }
        return List.copyOf(entries);
    }

    private static void addEntry(List<Entry> entries, JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode tool = node.get("tool");
        if (tool == null || !tool.isString() || tool.asString().isBlank()) {
            return;
        }
        JsonNode id = node.get("id");
        JsonNode arguments = node.get("arguments");
        boolean hasArguments = arguments != null && arguments.isObject();
        JsonNode args = node.get("args");
        boolean hasArgs = args != null && args.isArray() && !args.isEmpty();
        entries.add(new Entry(id != null && id.isString() ? id.asString() : null,
                tool.asString(), hasArguments ? arguments : null,
                hasArguments && hasArgs));
    }

    /** Index just past the JSON object starting at {@code start}, or the input length. */
    static int endOfJson(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }
}
