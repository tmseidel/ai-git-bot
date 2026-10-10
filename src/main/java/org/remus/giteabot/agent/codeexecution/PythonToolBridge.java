package org.remus.giteabot.agent.codeexecution;

import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.util.TextSupport;
import org.remus.giteabot.ai.ToolDescriptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves the {@code execute-code} control channel: one JSON request per line in, one JSON response
 * per line out.
 *
 * <p>Dispatch is the surface's own ({@link PythonToolExecutor}), so a program's call is authorised
 * and executed exactly like the model's own call — same executor, same whitelist, same refusal
 * wording. The bridge contributes only what is specific to being a sandbox: the per-execution
 * budget, the cap on a single nested result, and the protocol itself.</p>
 *
 * <p>Protocol (newline-delimited JSON, one request per line):
 * <pre>
 * → {"type":"tools_list","id":"1"}
 * → {"type":"tools_describe","id":"2","name":"rg"}
 * → {"type":"tool_call","id":"3","name":"mcp:github:search_issues","arguments":{"query":"x"}}
 * ← {"type":"tool_result","id":"3","result":{"success":true,"exitCode":0,"output":"…"}}
 * ← {"type":"tool_error","id":"3","error":{"code":"TOOL_CALL_LIMIT","message":"…"}}
 * </pre>
 *
 * <p>{@code tool_result} means the tool answered — including when what it answered with is a refusal
 * or a failure, which the program reads from {@code success}/{@code error} just as the model reads
 * them from the result it is handed. {@code tool_error} means the sandbox itself would not relay the
 * call (budget exhausted, unknown request, frame it cannot parse); Python raises that as
 * {@code ToolError}.</p>
 *
 * <p>Responses are assembled by hand rather than by a serialiser, so the wire shape is visible in one
 * place: this is a contract with {@code ai_git_bot.py} and the two are read together. Requests are
 * read as a tree for the same reason — a program that hand-writes a frame may send fields this
 * version does not know, and that must not be fatal.</p>
 */
final class PythonToolBridge {

    static final String BAD_REQUEST = "BAD_REQUEST";
    static final String TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";
    static final String TOOL_CALL_LIMIT = "TOOL_CALL_LIMIT";

    private static final int MAX_SUMMARY_CHARS = 200;

    private final List<ToolDescriptor> available;
    private final PythonToolExecutor executor;
    private final int maxToolCalls;
    private final int maxNestedResultChars;
    private final ObjectMapper json;

    private final AtomicInteger toolCalls = new AtomicInteger();
    private volatile boolean limitReached;

    PythonToolBridge(List<ToolDescriptor> available,
                     PythonToolExecutor executor,
                     CodeExecutionLimits limits,
                     ObjectMapper json) {
        this.available = available == null ? List.of() : List.copyOf(available);
        this.executor = executor;
        this.maxToolCalls = limits.maxToolCalls();
        this.maxNestedResultChars = limits.maxResultChars();
        this.json = json;
    }

    /** One request line in, one response line out. Never throws: a bad frame is a reply. */
    JsonNode handle(String line) {
        JsonNode root;
        try {
            root = json.readTree(line);
        } catch (Exception e) {
            return error(null, BAD_REQUEST, "request is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            return error(null, BAD_REQUEST, "request is not a JSON object");
        }
        Request request = new Request(text(root, "type"), text(root, "id"),
                text(root, "name"), root.get("arguments"));
        String type = request.type() == null ? "" : request.type();
        return switch (type) {
            case "tools_list" -> toolsList(request.id());
            case "tools_describe" -> describe(request.id(), request.name());
            case "tool_call" -> call(request);
            default -> error(request.id(), BAD_REQUEST, "unknown request type '" + type + "'");
        };
    }

    /** Tool calls this execution made, refusals included. */
    int toolCalls() {
        return toolCalls.get();
    }

    /** {@code true} once the budget refused a call, so the result can say so. */
    boolean budgetExhausted() {
        return limitReached;
    }

    private JsonNode toolsList(String id) {
        ObjectNode response = envelope("tool_result", id);
        ArrayNode list = response.putObject("result").putArray("tools");
        for (ToolDescriptor tool : available) {
            ObjectNode entry = list.addObject();
            entry.put("name", tool.name());
            entry.put("description", oneLine(tool.description()));
        }
        return response;
    }

    private JsonNode describe(String id, String name) {
        Optional<ToolDescriptor> tool = find(name);
        if (tool.isEmpty()) {
            return error(id, TOOL_NOT_ALLOWED, notAllowedMessage(name));
        }
        ToolDescriptor descriptor = tool.get();
        ObjectNode response = envelope("tool_result", id);
        ObjectNode result = response.putObject("result");
        result.put("name", descriptor.name());
        result.put("description", descriptor.description() == null ? "" : descriptor.description());
        if (descriptor.jsonSchema() != null) {
            result.set("inputSchema", descriptor.jsonSchema());
        }
        return response;
    }

    private JsonNode call(Request request) {
        String name = request.name();
        if (name == null || name.isBlank()) {
            return error(request.id(), BAD_REQUEST, "tool_call without a name");
        }
        if (toolCalls.get() >= maxToolCalls) {
            limitReached = true;
            return error(request.id(), TOOL_CALL_LIMIT,
                    "tool call limit of " + maxToolCalls + " reached for this execution");
        }
        toolCalls.incrementAndGet();

        JsonNode arguments = request.arguments() == null || request.arguments().isNull()
                ? json.createObjectNode()
                : request.arguments();
        // No policy here: whatever the surface does with a tool name — including refusing one that is
        // not on the bot's whitelist — is what the program gets back.
        return toolResult(request.id(), executor.call(name, arguments));
    }

    private JsonNode toolResult(String id, ToolResult result) {
        ObjectNode response = envelope("tool_result", id);
        ObjectNode payload = response.putObject("result");
        payload.put("success", result.success());
        payload.put("exitCode", result.exitCode());
        String output = truncate(result.output());
        boolean truncated = result.outputTruncated()
                || result.output() != null && result.output().length() > maxNestedResultChars;
        if (result.outputTruncated()) {
            output += "\n" + ToolResult.TRUNCATED_OUTPUT_WARNING;
        }
        payload.put("output", output);
        payload.put("outputTruncated", truncated);
        if (result.error() != null && !result.error().isBlank()) {
            payload.put("error", truncate(result.error()));
        }
        return response;
    }

    private Optional<ToolDescriptor> find(String name) {
        String wanted = name == null ? "" : name.strip();
        return available.stream().filter(tool -> tool.name().equals(wanted)).findFirst();
    }

    private ObjectNode envelope(String type, String id) {
        ObjectNode node = json.createObjectNode();
        node.put("type", type);
        if (id != null) {
            node.put("id", id);
        }
        return node;
    }

    private ObjectNode error(String id, String code, String message) {
        ObjectNode response = envelope("tool_error", id);
        ObjectNode payload = response.putObject("error");
        payload.put("code", code);
        payload.put("message", message == null ? "" : message);
        return response;
    }

    /**
     * A single nested result is capped before it crosses the bridge, so a program cannot smuggle a
     * large file into the model's context by printing the result it just fetched.
     */
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxNestedResultChars) {
            return value;
        }
        return TextSupport.cutAtCodePoint(value, maxNestedResultChars)
                + "\n[nested result truncated at " + maxNestedResultChars + " chars]";
    }

    /** {@code tools.list()} carries one line per tool; the full text stays in {@code describe}. */
    private static String oneLine(String description) {
        if (description == null) {
            return "";
        }
        String flattened = description.strip().replaceAll("\\s+", " ");
        int sentence = flattened.indexOf(". ");
        if (sentence > 0) {
            flattened = flattened.substring(0, sentence + 1);
        }
        if (flattened.length() <= MAX_SUMMARY_CHARS) {
            return flattened;
        }
        return TextSupport.cutAtCodePoint(flattened, MAX_SUMMARY_CHARS - 3) + "...";
    }

    private static String notAllowedMessage(String name) {
        return "tool '" + name + "' is not available in this execution; "
                + "call tools.list() for the tools you may use";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** The request shape, populated from the tree so unknown fields are tolerated. */
    private record Request(String type, String id, String name, JsonNode arguments) {
    }
}
