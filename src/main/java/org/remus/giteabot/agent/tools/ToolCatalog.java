package org.remus.giteabot.agent.tools;

import org.remus.giteabot.agent.issueimpl.IssueNotificationService;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for the agent's tool taxonomy <em>and</em> the
 * JSON-schema surface advertised to the AI provider's native function-calling
 * API. Each tool is declared exactly once via {@link #STATIC_TOOLS}; adding a
 * new tool means appending one {@link Entry} here and nowhere else.
 *
 * <p>Previously this knowledge was split between the legacy
 * {@code AgentNativeTools} (schemas + descriptions) and an earlier version of
 * this class (name lists). That duplication is gone — schema, description,
 * classification and role membership all live next to each other on the same
 * record per tool.</p>
 *
 * <p>Validation tools (mvn, gradle, npm, …) are NOT hard-coded — they are
 * sourced from {@link AgentConfigProperties.ValidationConfig#getAvailableTools()}
 * so operators can extend the list by config alone. Per-tool descriptions for
 * the native API are looked up in {@link #VALIDATION_DESCRIPTIONS}; unknown
 * entries fall back to a generic description so a new tool added to the config
 * is immediately exposed to the LLM.</p>
 */
@Component
public class ToolCatalog {

    /** Which agent role(s) may invoke a tool. */
    public enum Role { CODING, WRITER, E2E }

    /** Internal record per built-in (non-validation) tool. */
    private record Entry(String name, ToolKind kind, Set<Role> roles,
                          String description, JsonNode schema) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * THE list. One entry per built-in tool, holding everything any consumer
     * could need: classification, role membership, native description, native
     * JSON schema. Add new tools here — and nowhere else.
     */
    private static final List<Entry> STATIC_TOOLS = List.of(
            // ---- file-mutation tools (coding only) ----
            entry("write-file", ToolKind.FILE, EnumSet.of(Role.CODING),
                    "Create or overwrite a file with the given content. Always use this in the same "
                            + "round as at least one validation tool (mvn, gradle, npm, dotnet, etc.).",
                    objectSchema(
                            prop("path",    "string",  "Repository-relative path to the file."),
                            prop("content", "string",  "Full file content (UTF-8)."),
                            required("path", "content"))),
            entry("patch-file", ToolKind.FILE, EnumSet.of(Role.CODING),
                    "Replace exact text inside a file. The search text must match exactly once. "
                            + "Matching is tolerant of CRLF vs LF line endings and of trailing "
                            + "whitespace differences, so you do not need a perfect byte-for-byte "
                            + "copy — but indentation and the actual content of each line must match. "
                            + "If you have not seen the file yet, call `cat` in a previous turn first.",
                    objectSchema(
                            prop("path",        "string", "Repository-relative path to the file."),
                            prop("search",      "string", "Exact existing text to replace (must match exactly once)."),
                            prop("replacement", "string", "New text that replaces the search snippet."),
                            required("path", "search", "replacement"))),
            entry("mkdir", ToolKind.FILE, EnumSet.of(Role.CODING),
                    "Create a directory (and any missing parents).",
                    objectSchema(prop("path", "string", "Repository-relative directory path."),
                            required("path"))),
            entry("delete-file", ToolKind.FILE, EnumSet.of(Role.CODING),
                    "Delete a file. Returns a warning if the file does not exist.",
                    objectSchema(prop("path", "string", "Repository-relative path to the file to delete."),
                            required("path"))),

            // ---- repository exploration (shared by coding + writer) ----
            entry("branch-switcher", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "Switch the workspace/context to a different branch before any other repository "
                            + "tool. Call this FIRST when you need a non-default base branch.",
                    objectSchema(prop("branch", "string", "Branch name to check out."), required("branch"))),
            entry("rg", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "Run ripgrep across the workspace. Common args: [\"pattern\"] or [\"pattern\", \"path\"].",
                    varargsSchema()),
            entry("find", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "Find files by glob pattern. Args: [\"*.yml\"] or [\"*.java\", \"src\"].",
                    varargsSchema()),
            entry("cat", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "Read part of a file with 1-based line numbers.",
                    objectSchema(
                            prop("path",      "string",  "Repository-relative path."),
                            prop("startLine", "integer", "First line to include (inclusive, 1-based). Optional — omit to start at 1."),
                            prop("endLine",   "integer", "Last line to include (inclusive). Optional — omit to read to EOF."),
                            required("path"))),
            entry("git-log", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "Inspect change history. Args: [\"path/file\"] or [\"path/file\", \"limit\"].",
                    varargsSchema()),
            entry("git-blame", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "Inspect line history. Args: [\"path/file\", \"startLine\", \"endLine\"].",
                    varargsSchema()),
            entry("tree", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "List a directory recursively. Args: [\"src\"] or [\"src\", \"3\"] (depth).",
                    varargsSchema()),

            // Additional context aliases (silent at runtime — never advertised to the LLM
            // to avoid duplicate descriptors with conflicting docs).
            silentAlias("ripgrep", ToolKind.CONTEXT),
            silentAlias("grep",    ToolKind.CONTEXT),

            // ---- writer-only repository helpers ----
            entry("get-issue", ToolKind.REPOSITORY, EnumSet.of(Role.WRITER),
                    "Fetch the body and metadata of an issue by number (args: issue number).",
                    varargsSchema()),
            entry("search-issues", ToolKind.REPOSITORY, EnumSet.of(Role.WRITER),
                    "Search issues by free-text query (args: query string).",
                    varargsSchema()),

            // ---- PR-workflow E2E tools (E2E role only) ----
            entry("pr-test-write", ToolKind.PR_WORKFLOW, EnumSet.of(Role.E2E),
                    "Write a generated test file into the sandboxed PR test workspace and persist "
                            + "(or update) the matching PrTestCase row. Path is workspace-relative; "
                            + "absolute paths or `..` traversal are rejected.",
                    objectSchema(
                            prop("path",    "string", "Workspace-relative path of the test file (e.g. \"tests/login.spec.ts\")."),
                            prop("content", "string", "Full UTF-8 file content."),
                            prop("title",   "string", "Optional human-readable test-case title; used in the PR comment summary."),
                            required("path", "content"))),
            entry("pr-test-run", ToolKind.PR_WORKFLOW, EnumSet.of(Role.E2E),
                    "Execute the chosen test framework inside the PR test workspace. For Playwright "
                            + "this runs `npx playwright test` with the JSON reporter and parses per-test "
                            + "results back into PrTestCase rows. Returns a textual summary plus the raw "
                            + "stdout/stderr (truncated).",
                    objectSchema(
                            prop("framework", "string", "One of: playwright, pytest, k6, cypress."),
                            arrayProp("args", "Additional CLI arguments forwarded verbatim to the runner."),
                            required("framework", "args"))),
            entry("preview-url", ToolKind.PR_WORKFLOW, EnumSet.of(Role.E2E),
                    "Return the reachable preview URL the deployment strategy produced for the current PR.",
                    objectSchema()),
            entry("preview-status", ToolKind.PR_WORKFLOW, EnumSet.of(Role.E2E),
                    "HTTP-probe the preview deployment. Returns status code, latency and a short body "
                            + "excerpt. Use this to verify the preview is responsive before running tests.",
                    objectSchema(
                            prop("path",           "string",  "Optional URL path appended to the preview URL (defaults to \"/\")."),
                            prop("expectedStatus", "integer", "Optional expected HTTP status (defaults to 200). Probe is reported as failed when it differs."))),
            entry("attach-artifact", ToolKind.PR_WORKFLOW, EnumSet.of(Role.E2E),
                    "Attach a workspace-relative file as a Markdown comment on the current PR. "
                            + "Images are inlined as a data URI; other files are inlined as a fenced "
                            + "code block (truncated at 64 KiB). The path must resolve inside the PR "
                            + "test workspace.",
                    objectSchema(
                            prop("path",  "string", "Workspace-relative path of the artifact to attach."),
                            prop("title", "string", "Optional comment header; defaults to the file name."),
                            required("path")))
    );

    /**
     * Human-readable native-API descriptions for the validation tools shipped by
     * default. A tool added to {@code agent.validation.available-tools} that is
     * not listed here is still exposed to the LLM, just with a generic description.
     */
    private static final Map<String, String> VALIDATION_DESCRIPTIONS = Map.ofEntries(
            Map.entry("mvn",     "Run Apache Maven in the workspace root (e.g. compile, test, verify)."),
            Map.entry("gradle",  "Run Gradle in the workspace root (e.g. compileJava, test)."),
            Map.entry("npm",     "Run npm in the workspace root (e.g. run build, test, ci)."),
            Map.entry("dotnet",  "Run the .NET CLI in the workspace root (e.g. build, test)."),
            Map.entry("cargo",   "Run Cargo in the workspace root (e.g. build, test)."),
            Map.entry("go",      "Run the Go toolchain in the workspace root (e.g. build ./..., test ./...)."),
            Map.entry("python3", "Run python3 in the workspace root (e.g. -m py_compile some/file.py)."),
            Map.entry("make",    "Run GNU make in the workspace root."),
            Map.entry("cmake",   "Run CMake in the workspace root (e.g. --build . --config Debug).")
    );

    private final AgentConfigProperties agentConfig;
    /** name → entry, lower-cased. */
    private final Map<String, Entry> byName;

    public ToolCatalog(AgentConfigProperties agentConfig) {
        this.agentConfig = agentConfig;
        Map<String, Entry> idx = new LinkedHashMap<>();
        for (Entry e : STATIC_TOOLS) {
            idx.put(e.name(), e);
        }
        this.byName = Map.copyOf(idx);
    }

    // ---------------------------------------------------------------- names

    public List<String> contextToolNames() {
        return namesOf(ToolKind.CONTEXT);
    }

    public List<String> fileToolNames() {
        return namesOf(ToolKind.FILE);
    }

    public List<String> writerRepositoryToolNames() {
        return namesOf(ToolKind.REPOSITORY);
    }


    public List<String> validationToolNames() {
        return agentConfig.getValidation().getAvailableTools();
    }

    /**
     * Filtered variants returning only those names that intersect with
     * {@code allowedBuiltinTools}. A {@code null} {@code allowed} set means
     * "no whitelist configured — return everything" (used by tests and the
     * pre-bot-tool-configuration code paths). An empty non-null set returns
     * an empty list (bot operator explicitly disabled every built-in).
     */
    public List<String> contextToolNames(Set<String> allowed) {
        return filterNames(contextToolNames(), allowed);
    }

    public List<String> fileToolNames(Set<String> allowed) {
        return filterNames(fileToolNames(), allowed);
    }

    public List<String> writerRepositoryToolNames(Set<String> allowed) {
        return filterNames(writerRepositoryToolNames(), allowed);
    }


    public List<String> validationToolNames(Set<String> allowed) {
        return filterNames(validationToolNames(), allowed);
    }

    private List<String> namesOf(ToolKind kind) {
        List<String> out = new ArrayList<>();
        for (Entry e : STATIC_TOOLS) {
            if (e.kind() == kind) {
                out.add(e.name());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> filterNames(List<String> names, Set<String> allowed) {
        if (allowed == null) {
            return names;
        }
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (allowed.contains(name)) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- queries

    /** Classifies the tool by its <em>declared</em> kind. MCP detection is name-prefix based. */
    public ToolKind kindOf(String tool) {
        String n = normalize(tool);
        if (n.isEmpty()) {
            return ToolKind.UNKNOWN;
        }
        if (McpTools.looksLikeMcpTool(n)) {
            return ToolKind.MCP;
        }
        Entry e = byName.get(n);
        if (e != null) {
            return e.kind();
        }
        if (validationToolNames().contains(n)) {
            return ToolKind.VALIDATION;
        }
        return ToolKind.UNKNOWN;
    }

    public boolean isContext(String tool)    { return kindOf(tool) == ToolKind.CONTEXT; }
    public boolean isFile(String tool)       { return kindOf(tool) == ToolKind.FILE; }
    public boolean isValidation(String tool) { return kindOf(tool) == ToolKind.VALIDATION; }
    public boolean isMcp(String tool)        { return kindOf(tool) == ToolKind.MCP; }

    /**
     * Whether the tool's output should be hidden from public issue/PR comments.
     * Validation tools' output is shown (build/test logs); the
     * PR-workflow test runner ({@code pr-test-run}) is also shown so operators
     * see the framework's output; everything else is silent.
     */
    public boolean isSilent(String tool) {
        ToolKind kind = kindOf(tool);
        if (kind == ToolKind.PR_WORKFLOW) {
            return !"pr-test-run".equals(normalize(tool));
        }
        return kind != ToolKind.VALIDATION && kind != ToolKind.UNKNOWN;
    }

    /**
     * Maps a tool to one of the visual buckets used by
     * {@link IssueNotificationService}.
     */
    public DisplayBucket bucketOf(String tool) {
        ToolKind kind = kindOf(tool);
        if (kind == ToolKind.PR_WORKFLOW) {
            return switch (normalize(tool)) {
                case "pr-test-write" -> DisplayBucket.MUTATION;
                case "pr-test-run"   -> DisplayBucket.VALIDATION;
                default              -> DisplayBucket.CONTEXT;
            };
        }
        return switch (kind) {
            case CONTEXT, REPOSITORY, MCP -> DisplayBucket.CONTEXT;
            case FILE -> DisplayBucket.MUTATION;
            case VALIDATION, UNKNOWN -> DisplayBucket.VALIDATION;
            default -> throw new IllegalStateException("Unexpected value: " + kind);
        };
    }

    /** Display bucket used by the notification service to group tools in a single comment. */
    public enum DisplayBucket { CONTEXT, MUTATION, VALIDATION }

    // ---------------------------------------------------------- native descriptors

    /**
     * The JSON-schema surface advertised via the AI provider's native
     * function-calling API for the given role. Built-in tools come from
     * {@link #STATIC_TOOLS} and are filtered through {@code allowedBuiltinTools}
     * (a {@code null} set disables filtering — test paths only). Validation
     * tools come from {@link AgentConfigProperties.ValidationConfig#getAvailableTools()}
     * (coding only); MCP tools come from {@code mcpCatalog} and are passed
     * through unchanged — MCP filtering happens via {@code McpToolSelectionService}.
     */
    public List<ToolDescriptor> nativeDescriptors(Role role, McpToolCatalog mcpCatalog,
                                                  Set<String> allowedBuiltinTools) {
        List<ToolDescriptor> out = new ArrayList<>();
        for (Entry e : STATIC_TOOLS) {
            if (e.schema() == null) {                 // silent alias — never advertised
                continue;
            }
            if (!e.roles().contains(role)) {
                continue;
            }
            if (allowedBuiltinTools != null && !allowedBuiltinTools.contains(e.name())) {
                continue;
            }
            out.add(new ToolDescriptor(e.name(), e.description(), e.schema()));
        }
        if (role == Role.CODING) {
            for (String name : validationToolNames()) {
                if (allowedBuiltinTools != null && !allowedBuiltinTools.contains(name)) {
                    continue;
                }
                String description = VALIDATION_DESCRIPTIONS.getOrDefault(name,
                        "Run `" + name + "` in the workspace root with the given positional arguments.");
                out.add(new ToolDescriptor(name, description, varargsSchema()));
            }
        }
        if (mcpCatalog != null) {
            for (McpToolDefinition info : mcpCatalog.tools()) {
                JsonNode schema = (info.inputSchema() == null || info.inputSchema().isEmpty())
                        ? JSON.createObjectNode().put("type", "object")
                        : JSON.valueToTree(info.inputSchema());
                out.add(new ToolDescriptor(info.qualifiedName(), info.description(), schema));
            }
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------- schema helpers

    private static Entry entry(String name, ToolKind kind, Set<Role> roles,
                                String description, ObjectNode schema) {
        return new Entry(name, kind, roles, description, schema);
    }

    /** Runtime-only alias: classified but never exposed to the LLM (schema == null). */
    private static Entry silentAlias(String name, ToolKind kind) {
        return new Entry(name, kind, Set.of(), null, null);
    }

    /** Schema for tools that accept a free positional argument vector. */
    private static ObjectNode varargsSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode args = props.putObject("args");
        args.put("type", "array");
        args.put("description",
                "Positional CLI arguments, one element per token. Pass an empty array for no args.");
        args.putObject("items").put("type", "string");
        root.putArray("required").add("args");
        return root;
    }

    private static ObjectNode objectSchema(Object... parts) {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        List<String> req = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Prop(String name, String type, String description)) {
                ObjectNode node = props.putObject(name);
                node.put("type", type);
                if (description != null) {
                    node.put("description", description);
                }
            } else if (part instanceof ArrayProp(String name, String description)) {
                ObjectNode node = props.putObject(name);
                node.put("type", "array");
                if (description != null) {
                    node.put("description", description);
                }
                node.putObject("items").put("type", "string");
            } else if (part instanceof Required(String[] names)) {
                Collections.addAll(req, names);
            }
        }
        if (!req.isEmpty()) {
            var arr = root.putArray("required");
            req.forEach(arr::add);
        }
        return root;
    }

    private static Prop prop(String name, String type, String description) {
        return new Prop(name, type, description);
    }

    private static ArrayProp arrayProp(String name, String description) {
        return new ArrayProp(name, description);
    }

    private static Required required(String... names) {
        return new Required(names);
    }

    private record Prop(String name, String type, String description) { }
    private record ArrayProp(String name, String description) { }
    private record Required(String[] names) { }

    private static String normalize(String tool) {
        return tool != null ? tool.strip().toLowerCase() : "";
    }

    /** Mostly for tests / diagnostics. */
    public Optional<String> describeFor(Role role, String toolName) {
        Entry e = byName.get(normalize(toolName));
        if (e != null && e.roles().contains(role) && e.description() != null) {
            return Optional.of(e.description());
        }
        if (role == Role.CODING && validationToolNames().contains(normalize(toolName))) {
            return Optional.ofNullable(VALIDATION_DESCRIPTIONS.get(normalize(toolName)));
        }
        return Optional.empty();
    }

    /**
     * Returns a legacy-protocol JSON example for the named tool, derived from its
     * schema. Object-schema tools produce positional {@code args} with one
     * {@code "<propertyName>"} placeholder per declared property (required first,
     * then optional, preserving insertion order). Vararg tools (and validation
     * tools, which are vararg by construction) produce {@code ["<arg>", "..."]}.
     * Unknown tools fall back to a single placeholder.
     */
    public String legacyUsageExample(String toolName) {
        String name = normalize(toolName);
        Entry entry = byName.get(name);
        JsonNode schema = entry != null ? entry.schema() : null;
        String argsJson = renderArgsExample(schema);
        return "{\"id\": \"<uuid>\", \"tool\": \"" + name + "\", \"args\": " + argsJson + "}";
    }

    private static String renderArgsExample(JsonNode schema) {
        if (schema == null) {
            return "[\"<arg>\", \"...\"]";
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || properties.isEmpty()) {
            return "[]";
        }
        // Varargs shape: a single `args` array property.
        if (properties.size() == 1 && properties.get("args") != null
                && "array".equals(textOrNull(properties.get("args").get("type")))) {
            return "[\"<arg>\", \"...\"]";
        }
        // Object schema: emit positional placeholders. Required properties first
        // (in schema order), then any remaining optional properties.
        List<String> ordered = new ArrayList<>();
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode req : requiredNode) {
                String reqName = req.asString();
                if (reqName != null && properties.get(reqName) != null) {
                    ordered.add(reqName);
                }
            }
        }
        for (Map.Entry<String, JsonNode> prop : properties.properties()) {
            if (!ordered.contains(prop.getKey())) {
                ordered.add(prop.getKey());
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append('<').append(ordered.get(i)).append('>').append('"');
        }
        return sb.append(']').toString();
    }

    private static String textOrNull(JsonNode n) {
        return n == null ? null : n.asString();
    }
}
