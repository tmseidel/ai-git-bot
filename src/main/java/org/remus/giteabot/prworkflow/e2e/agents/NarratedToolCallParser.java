package org.remus.giteabot.prworkflow.e2e.agents;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers tool invocations that Claude printed as plain text instead of
 * emitting them as native {@code tool_use} blocks.
 *
 * <p>Three failure modes are recognised:</p>
 * <ul>
 *     <li><b>JSON fence form</b> — the model wraps each call in a fenced
 *         <code>```json {"name": "...", "parameters": {...}}```</code>
 *         block. We accept {@code parameters}, {@code arguments} and
 *         {@code input} as the args key.</li>
 *     <li><b>Claude XML (function_calls) form</b> — the training-time
 *         tool-call syntax leaks into the text output as
 *         <code>&lt;function_calls&gt;&lt;invoke name="tool"&gt;&lt;parameter
 *         name="key"&gt;value&lt;/parameter&gt;...&lt;/invoke&gt;&lt;/function_calls&gt;</code>.
 *         Multi-line {@code <parameter>} bodies are preserved verbatim — we
 *         must keep newlines and indentation since they are usually source
 *         code.</li>
 *     <li><b>{@code <tool_call>} JSON form</b> — a hybrid leaked from
 *         chat-template training: each call is wrapped in
 *         <code>&lt;tool_call&gt;{"name": "...", "arguments": {...}}&lt;/tool_call&gt;</code>
 *         and is typically accompanied by hallucinated
 *         <code>&lt;tool_response&gt;...&lt;/tool_response&gt;</code> blocks
 *         that the model invents to continue reasoning. The hallucinated
 *         responses are ignored; only the calls are recovered.</li>
 * </ul>
 *
 * <p>The parser is intentionally tolerant: malformed or truncated blocks are
 * skipped silently so a single bad fragment cannot poison the recovery of the
 * remaining valid ones. Callers should still cross-check whether the parsed
 * calls cover the expected number of journeys.</p>
 */
public final class NarratedToolCallParser {

    /** One recovered call — same shape as a native {@code ToolCall}. */
    public record Call(String name, Map<String, Object> args) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    // ```json ... ``` blocks — non-greedy, DOTALL. We also accept a bare
    // ``` ... ``` fence in case the language hint is missing.
    private static final Pattern JSON_FENCE = Pattern.compile(
            "```(?:json|JSON)?\\s*(\\{.*?})\\s*```",
            Pattern.DOTALL);

    // <invoke name="..."> ... </invoke> — DOTALL so the body can span lines.
    private static final Pattern INVOKE_BLOCK = Pattern.compile(
            "<invoke\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>(.*?)</invoke>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // <parameter name="..."> ... </parameter> — DOTALL, keeps body whitespace.
    private static final Pattern PARAMETER_BLOCK = Pattern.compile(
            "<parameter\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>(.*?)</parameter>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // <tool_call> ... JSON object ... </tool_call> — DOTALL. The body should
    // be a single JSON object {"name": "...", "arguments"|"parameters"|"input": {...}}.
    private static final Pattern TOOL_CALL_BLOCK = Pattern.compile(
            "<tool_call>\\s*(\\{.*?})\\s*</tool_call>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private NarratedToolCallParser() { }

    /**
     * Scans {@code assistantText} and returns every tool call it can recover,
     * in the order they appear. Returns an empty list when {@code assistantText}
     * is {@code null}/blank or contains no recognisable invocation.
     */
    public static List<Call> parse(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) return List.of();
        List<Call> out = new ArrayList<>();
        out.addAll(parseXml(assistantText));
        out.addAll(parseToolCallTagged(assistantText));
        if (out.isEmpty()) {
            // Only fall back to the bare JSON-fence form when no tagged calls
            // were found — a fenced ```json plan block on the user side would
            // otherwise be mis-parsed as a tool call.
            out.addAll(parseJson(assistantText));
        }
        return out;
    }

    // ---- XML form ----

    static List<Call> parseXml(String text) {
        List<Call> calls = new ArrayList<>();
        Matcher invokeMatcher = INVOKE_BLOCK.matcher(text);
        while (invokeMatcher.find()) {
            String name = invokeMatcher.group(1).trim();
            String body = invokeMatcher.group(2);
            Map<String, Object> args = new LinkedHashMap<>();
            Matcher paramMatcher = PARAMETER_BLOCK.matcher(body);
            while (paramMatcher.find()) {
                String key = paramMatcher.group(1).trim();
                String value = paramMatcher.group(2);
                // Strip exactly one leading newline if present — the XML
                // pretty-printing convention is to put the value on its own
                // line, and the trailing newline before </parameter> is
                // also pretty-printing. We DO NOT trim arbitrary whitespace
                // because indentation matters for code payloads.
                value = stripOneLeadingNewline(value);
                value = stripOneTrailingNewline(value);
                args.put(key, value);
            }
            if (!name.isEmpty() && !args.isEmpty()) {
                calls.add(new Call(name, args));
            }
        }
        return calls;
    }

    private static String stripOneLeadingNewline(String s) {
        if (s.startsWith("\r\n")) return s.substring(2);
        if (s.startsWith("\n")) return s.substring(1);
        return s;
    }

    private static String stripOneTrailingNewline(String s) {
        if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
        if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
        return s;
    }

    // ---- JSON form ----

    static List<Call> parseJson(String text) {
        List<Call> calls = new ArrayList<>();
        Matcher fenceMatcher = JSON_FENCE.matcher(text);
        while (fenceMatcher.find()) {
            Call c = decodeJsonCall(fenceMatcher.group(1));
            if (c != null) calls.add(c);
        }
        return calls;
    }

    // ---- <tool_call>{json}</tool_call> form ----

    static List<Call> parseToolCallTagged(String text) {
        List<Call> calls = new ArrayList<>();
        Matcher m = TOOL_CALL_BLOCK.matcher(text);
        while (m.find()) {
            Call c = decodeJsonCall(m.group(1));
            if (c != null) calls.add(c);
        }
        return calls;
    }

    /** Decodes a single {"name": ..., "arguments"|"parameters"|"input": {...}} object. */
    private static Call decodeJsonCall(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isObject()) return null;
            JsonNode nameNode = node.get("name");
            if (nameNode == null || !nameNode.isString()) return null;
            JsonNode argsNode = firstNonNull(node, "parameters", "arguments", "input");
            Map<String, Object> args = new LinkedHashMap<>();
            if (argsNode != null && argsNode.isObject()) {
                for (Map.Entry<String, JsonNode> e : argsNode.properties()) {
                    args.put(e.getKey(), jsonValueToJava(e.getValue()));
                }
            }
            // Allow zero-argument calls (e.g. `preview-url` takes no args) —
            // only the name is mandatory.
            return new Call(nameNode.asString().trim(), args);
        } catch (Exception ignored) {
            // Skip malformed blocks silently — recovery is best-effort.
            return null;
        }
    }

    private static JsonNode firstNonNull(JsonNode parent, String... keys) {
        for (String key : keys) {
            JsonNode n = parent.get(key);
            if (n != null && !n.isNull()) return n;
        }
        return null;
    }

    private static Object jsonValueToJava(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isString()) return node.asString();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isArray()) {
            // Preserve array shape so downstream executors that expect
            // List<String> (e.g. `pr-test-run` args) receive a List, not a
            // JSON-encoded string literal.
            List<Object> arr = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                arr.add(jsonValueToJava(item));
            }
            return arr;
        }
        if (node.isObject()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                obj.put(e.getKey(), jsonValueToJava(e.getValue()));
            }
            return obj;
        }
        return node.toString();
    }
}

