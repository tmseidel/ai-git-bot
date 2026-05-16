package org.remus.giteabot.agent.shared;

import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds JSON-Schema-described {@link ToolDescriptor}s for the agent tools
 * exposed through the AI provider's native function-calling API (Step 6
 * follow-up).
 *
 * <p>Two surfaces are produced:</p>
 * <ul>
 *   <li>{@link #codingTools()} &mdash; full coding-agent toolbox
 *       (write/patch/mkdir/delete + repository exploration + validation tools).
 *   </li>
 *   <li>{@link #writerTools()} &mdash; read-only repository helpers plus
 *       issue-lookup, mirroring the writer agent's pre-existing surface.</li>
 * </ul>
 *
 * <p>All schemas use the lowest common denominator accepted by OpenAI,
 * Anthropic, Google and Ollama: a Draft-2020-12 object schema with primitive
 * properties and a {@code required} array. {@code positionalArgs} keeps the
 * shape compatible with the existing
 * {@link org.remus.giteabot.agent.model.ImplementationPlan.ToolRequest}
 * executor — i.e. tools that take a varargs list pass them via a {@code args}
 * array of strings; specialised tools expose typed properties that the
 * dispatcher translates into the same positional list.</p>
 */
public final class AgentNativeTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentNativeTools() {}

    // ---------------------------------------------------------------- coding

    public static List<ToolDescriptor> codingTools(McpToolCatalog mcpCatalog) {
        List<ToolDescriptor> tools = new ArrayList<>();

        // ---- File-mutation tools (CODING-only) ----
        tools.add(descriptor("write-file",
                "Create or overwrite a file with the given content. Always use this in the same "
                        + "round as at least one validation tool (mvn, gradle, npm, dotnet, etc.).",
                schema(prop("path", "string", "Repository-relative path to the file."),
                        prop("content", "string", "Full file content (UTF-8)."),
                        required("path", "content"))));

        tools.add(descriptor("patch-file",
                "Replace exact text inside a file. The search text must match exactly once. "
                        + "If you have not seen the file yet, call `cat` in a previous turn first.",
                schema(prop("path", "string", "Repository-relative path to the file."),
                        prop("search", "string", "Exact existing text to replace (must match exactly once)."),
                        prop("replacement", "string", "New text that replaces the search snippet."),
                        required("path", "search", "replacement"))));

        tools.add(descriptor("mkdir",
                "Create a directory (and any missing parents).",
                schema(prop("path", "string", "Repository-relative directory path."),
                        required("path"))));

        tools.add(descriptor("delete-file",
                "Delete a file. Returns a warning if the file does not exist.",
                schema(prop("path", "string", "Repository-relative path to the file to delete."),
                        required("path"))));

        // ---- Repository-exploration tools (shared with writer) ----
        explorationTools(tools);

        // ---- Validation tools (CODING-only) ----
        tools.add(varargsDescriptor("mvn",
                "Run Apache Maven in the workspace root (e.g. compile, test, verify)."));
        tools.add(varargsDescriptor("gradle",
                "Run Gradle in the workspace root (e.g. compileJava, test)."));
        tools.add(varargsDescriptor("npm",
                "Run npm in the workspace root (e.g. run build, test, ci)."));
        tools.add(varargsDescriptor("dotnet",
                "Run the .NET CLI in the workspace root (e.g. build, test)."));
        tools.add(varargsDescriptor("cargo",
                "Run Cargo in the workspace root (e.g. build, test)."));
        tools.add(varargsDescriptor("go",
                "Run the Go toolchain in the workspace root (e.g. build ./..., test ./...)."));
        tools.add(varargsDescriptor("python3",
                "Run python3 in the workspace root (e.g. -m py_compile some/file.py)."));
        tools.add(varargsDescriptor("make",
                "Run GNU make in the workspace root."));
        tools.add(varargsDescriptor("cmake",
                "Run CMake in the workspace root (e.g. --build . --config Debug)."));

        // ---- MCP tools (dynamic) ----
        mcpDescriptors(mcpCatalog, tools);

        return List.copyOf(tools);
    }

    // ---------------------------------------------------------------- writer

    public static List<ToolDescriptor> writerTools(McpToolCatalog mcpCatalog) {
        List<ToolDescriptor> tools = new ArrayList<>();
        explorationTools(tools);
        tools.add(varargsDescriptor("get-issue",
                "Fetch the body and metadata of an issue by number (args: issue number)."));
        tools.add(varargsDescriptor("search-issues",
                "Search issues by free-text query (args: query string)."));
        mcpDescriptors(mcpCatalog, tools);
        return List.copyOf(tools);
    }

    // -------------------------------------------------------------- helpers

    private static void explorationTools(List<ToolDescriptor> tools) {
        tools.add(descriptor("branch-switcher",
                "Switch the workspace/context to a different branch before any other repository "
                        + "tool. Call this FIRST when you need a non-default base branch.",
                schema(prop("branch", "string", "Branch name to check out."),
                        required("branch"))));

        tools.add(varargsDescriptor("rg",
                "Run ripgrep across the workspace. Common args: [\"pattern\"] or [\"pattern\", \"path\"]."));
        tools.add(varargsDescriptor("find",
                "Find files by glob pattern. Args: [\"*.yml\"] or [\"*.java\", \"src\"]."));
        tools.add(descriptor("cat",
                "Read part of a file with 1-based line numbers.",
                schema(prop("path", "string", "Repository-relative path."),
                        prop("startLine", "integer", "First line to include (inclusive, 1-based). Optional — omit to start at 1."),
                        prop("endLine", "integer", "Last line to include (inclusive). Optional — omit to read to EOF."),
                        required("path"))));
        tools.add(varargsDescriptor("git-log",
                "Inspect change history. Args: [\"path/file\"] or [\"path/file\", \"limit\"]."));
        tools.add(varargsDescriptor("git-blame",
                "Inspect line history. Args: [\"path/file\", \"startLine\", \"endLine\"]."));
        tools.add(varargsDescriptor("tree",
                "List a directory recursively. Args: [\"src\"] or [\"src\", \"3\"] (depth)."));
    }

    private static void mcpDescriptors(McpToolCatalog catalog, List<ToolDescriptor> sink) {
        if (catalog == null) {
            return;
        }
        for (org.remus.giteabot.mcp.McpToolDefinition info : catalog.tools()) {
            // MCP tools already provide a JSON Schema as a Map; reuse it verbatim.
            JsonNode schema;
            if (info.inputSchema() == null || info.inputSchema().isEmpty()) {
                schema = JSON.createObjectNode().put("type", "object");
            } else {
                schema = JSON.valueToTree(info.inputSchema());
            }
            sink.add(new ToolDescriptor(info.qualifiedName(), info.description(), schema));
        }
    }

    /**
     * Schema for tools whose CLI accepts a free positional argument vector,
     * mirroring the existing {@code ToolRequest.args} list.
     */
    private static ToolDescriptor varargsDescriptor(String name, String description) {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode args = props.putObject("args");
        args.put("type", "array");
        args.put("description",
                "Positional CLI arguments, one element per token. Pass an empty array for no args.");
        args.putObject("items").put("type", "string");
        root.putArray("required").add("args");
        return new ToolDescriptor(name, description, root);
    }

    private static ToolDescriptor descriptor(String name, String description, ObjectNode schema) {
        return new ToolDescriptor(name, description, schema);
    }

    /** Build an object schema by attaching property defs and a required array. */
    private static ObjectNode schema(Object... parts) {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        List<String> required = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof PropDef p) {
                ObjectNode node = props.putObject(p.name);
                node.put("type", p.type);
                if (p.description != null) {
                    node.put("description", p.description);
                }
            } else if (part instanceof RequiredDef r) {
                for (String n : r.names) {
                    required.add(n);
                }
            }
        }
        if (!required.isEmpty()) {
            var arr = root.putArray("required");
            required.forEach(arr::add);
        }
        return root;
    }

    private static PropDef prop(String name, String type, String description) {
        return new PropDef(name, type, description);
    }

    private static RequiredDef required(String... names) {
        return new RequiredDef(names);
    }

    private record PropDef(String name, String type, String description) {}
    private record RequiredDef(String[] names) {}
}


