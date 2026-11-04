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

    /**
     * Which agent role(s) may invoke a tool.
     *
     * <p>{@code PR_WORKFLOW} covers the agentic PR-workflow agents (E2E test,
     * unit-test author, readme-sync). Tools tagged with this role are of kind
     * {@link ToolKind#PR_WORKFLOW} and trigger a workflow-internal call inside
     * the bot rather than being general-purpose repository tools.</p>
     *
     * <p>{@code REVIEW} must be explicitly assigned to each read-only built-in.
     * Context classification or WRITER membership alone never grants review access.</p>
     */
    public enum Role { CODING, WRITER, REVIEW, PR_WORKFLOW }

    /** Intersects the bot configuration with explicit REVIEW-role entries; null denies all built-ins. */
    public Set<String> reviewToolNames(Set<String> allowed) {
        if (allowed == null) {
            return Set.of();
        }
        return STATIC_TOOLS.stream().filter(entry -> entry.roles().contains(Role.REVIEW))
                .map(Entry::name).filter(allowed::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Internal record per built-in (non-validation) tool.
     *
     * <p>{@code usageHint} is the tool's line in the prompt's tool-selection strategy: <em>when</em>
     * to reach for it — what it does is the description's job. It travels with the definition so the
     * strategy is derived from the tools an agent can actually call, and a tool that is not selected
     * is never mentioned. {@code null} when the tool needs no strategy line.</p>
     */
    private record Entry(String name, ToolKind kind, Set<Role> roles,
                          String description, JsonNode schema, String usageHint) {

        /** Copy of this entry carrying the tool-selection strategy line. */
        Entry withHint(String hint) {
            return new Entry(name, kind, roles, description, schema, hint);
        }
    }

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

            // ---- repository exploration (coding + writer; read-only entries opt in to review) ----
            entry("branch-switcher", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
                    "Switch the workspace/context to a different branch before any other repository "
                            + "tool. Call this FIRST when you need a non-default base branch.",
                    objectSchema(prop("branch", "string", "Branch name to check out."), required("branch")))
                .withHint("**Switch branches**: `branch-switcher` goes first, before any other repository tool."),
            entry("rg", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "Run ripgrep across the workspace. Args: [\"pattern\"] or [\"pattern\", \"path\"]; the pattern is a Java regex. "
                            + "Flags -i, -F, -l and -g <glob> may go anywhere; use -e <pattern> or -- for a pattern starting with -; "
                            + "[\"--files\", \"path\"] lists files.",
                    varargsSchema())
                .withHint("**Search the codebase**: `rg` finds symbol usages."),
            entry("find", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER,  Role.REVIEW),
                    "Find files by glob pattern. Args: [\"*.yml\"] or [\"*.java\", \"src\"].",
                    varargsSchema())
                .withHint("**Locate files by path**: `find` matches path patterns."),
            entry("cat", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "Read specific line ranges of a file with 1-based line numbers. "
                            + "Use this for precision reads after understanding structure via "
                            + "`ctags-signatures` — not for first-time file exploration.",
                    objectSchema(
                            prop("path",      "string",  "Repository-relative path."),
                            prop("startLine", "integer", "First line to include (inclusive, 1-based). Optional — omit to start at 1."),
                            prop("endLine",   "integer", "Last line to include (inclusive). Optional — omit to read to EOF."),
                            required("path")))
                .withHint("**Read specific lines once you know the structure**: `cat` with `startLine`/`endLine`. It is for precision reads, not for a first look at a file."),
            entry("git-log", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "Inspect change history. Args: [\"path/file\"], [\"path/file\", \"limit\"] or [\"limit\"]; "
                            + "filters --author, --since, --until, --grep, -S <text> and --no-merges.",
                    varargsSchema()),
            entry("git-blame", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "Inspect line history. Args: [\"path/file\", \"startLine\", \"endLine\"].",
                    varargsSchema()),
            entry("tree", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "List a directory recursively. Args: [\"src\"] or [\"src\", \"3\"] (depth).",
                    varargsSchema())
                .withHint("**See the shape of the tree**: `tree` lists the layout before you dive into a file."),
            entry("ctags-signatures", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "Extract function, class, method, and interface signatures from a source file. "
                            + "PREFER this over `cat` for first-time file exploration — it reveals the "
                            + "file's architecture (classes, methods, interfaces, functions) at a "
                            + "fraction of the tokens so you know what the file contains before "
                            + "reading specific lines. Args: [\"path/to/file\"] or [\"path/to/file\", "
                            + "\"limit\"] (default: 100).",
                    objectSchema(
                            prop("path",  "string",  "Repository-relative path to the file."),
                            prop("limit", "integer", "Max signatures to return (default: 100)."),
                            required("path")))
                .withHint("**First look at an unfamiliar file**: `ctags-signatures` gives you classes, methods and signatures without the file's full text, at a fraction of the tokens."),
            entry("ctags-deps", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "Extract imports, includes, and namespace/package declarations from a source file. "
                            + "Returns JSON with the declared namespace and all "
                            + "external dependencies. Use this to understand which modules a file depends on. "
                            + "Args: [\"path/to/file\"].",
                    objectSchema(
                            prop("path", "string", "Repository-relative path to the file."),
                            required("path")))
                .withHint("**Trace module relationships**: `ctags-deps` gives imports, includes and package declarations."),
            entry("pr-diff", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER, Role.REVIEW),
                    "Return the diff hunks for a specific changed file in the current pull request. "
                            + "Use this after inspecting the changed-file summary to see what exactly "
                            + "was added, removed, or modified in a file. "
                            + "Args: [\"path/to/file\"].",
                    objectSchema(
                            prop("path", "string", "Repository-relative path of the changed file."),
                            required("path"))),

            // Additional context aliases (silent at runtime — never advertised to the LLM
            // to avoid duplicate descriptors with conflicting docs).
            silentAlias("ripgrep", ToolKind.CONTEXT),
            silentAlias("grep",    ToolKind.CONTEXT),

            // ---- read-only repository helpers (writer + review) ----
            entry("get-issue", ToolKind.REPOSITORY, EnumSet.of(Role.WRITER, Role.REVIEW),
                    "Fetch the body and metadata of an issue by number (args: issue number).",
                    varargsSchema()),
            entry("search-issues", ToolKind.REPOSITORY, EnumSet.of(Role.WRITER, Role.REVIEW),
                    "Search issues by free-text query (args: query string).",
                    varargsSchema()),

            // ---- PR-workflow test tools (PR_WORKFLOW role only) ----
            entry("pr-test-write", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Write a generated test file into the sandboxed PR test workspace and persist "
                            + "(or update) the matching PrTestCase row. Path is workspace-relative; "
                            + "absolute paths or `..` traversal are rejected.",
                    objectSchema(
                            prop("path",    "string", "Workspace-relative path of the test file (e.g. \"tests/login.spec.ts\")."),
                            prop("content", "string", "Full UTF-8 file content."),
                            prop("title",   "string", "Optional human-readable test-case title; used in the PR comment summary."),
                            required("path", "content")))
                .withHint("**Write each test into the workspace**: `pr-test-write` records the file for the PR summary as well as writing it — that is what makes the test count."),
            entry("pr-test-run", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Execute the chosen test framework inside the PR test workspace. For Playwright "
                            + "this runs `npx playwright test` with the JSON reporter and parses per-test "
                            + "results back into PrTestCase rows. Returns a textual summary plus the raw "
                            + "stdout/stderr (truncated).",
                    objectSchema(
                            prop("framework", "string", "playwright (well-tested, recommended); pytest, k6, cypress are experimental."),
                            arrayProp("args", "Additional CLI arguments forwarded verbatim to the runner."),
                            required("framework", "args")))
                .withHint("**Run the suite and get the results back**: `pr-test-run` executes the framework and feeds the per-test outcomes into the run."),
            entry("preview-url", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Return the reachable preview URL the deployment strategy produced for the current PR.",
                    objectSchema())
                .withHint("**Find the deployed preview**: `preview-url` returns the URL this PR's deployment strategy produced."),
            entry("preview-status", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "HTTP-probe the preview deployment. Returns status code, latency and a short body "
                            + "excerpt. Use this to verify the preview is responsive before running tests.",
                    objectSchema(
                            prop("path",           "string",  "Optional URL path appended to the preview URL (defaults to \"/\")."),
                            prop("expectedStatus", "integer", "Optional expected HTTP status (defaults to 200). Probe is reported as failed when it differs.")))
                .withHint("**Check the preview before spending a run on it**: `preview-status` probes the URL and reports the status code, latency and a body excerpt."),
            entry("attach-artifact", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Attach a workspace-relative file as a Markdown comment on the current PR. "
                            + "Images are inlined as a data URI; other files are inlined as a fenced "
                            + "code block (truncated at 64 KiB). The path must resolve inside the PR "
                            + "test workspace.",
                    objectSchema(
                            prop("path",  "string", "Workspace-relative path of the artifact to attach."),
                            prop("title", "string", "Optional comment header; defaults to the file name."),
                            required("path")))
                .withHint("**Show the operator the evidence**: `attach-artifact` inlines a workspace file — a screenshot, a log — into the PR comment."),

            // ---- unit-test-author tool (PR_WORKFLOW role; operates on the real checkout) ----
            entry("unit-test-write", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Write a generated unit-test file into the repository checkout and persist "
                            + "(or update) the matching UnitTestCase row. The path is checkout-relative "
                            + "and must live under the project's conventional test source set "
                            + "(e.g. src/test/java/..., tests/..., *_test.go) — production code is "
                            + "off-limits. Absolute paths or `..` traversal are rejected.",
                    objectSchema(
                            prop("path",    "string", "Checkout-relative path of the test file (e.g. \"src/test/java/com/acme/FooTest.java\")."),
                            prop("content", "string", "Full UTF-8 file content."),
                            prop("title",   "string", "Optional human-readable test-case title; used in the PR comment summary."),
                            required("path", "content")))
                .withHint("**Write each test file into the checkout**: `unit-test-write` persists the case and keeps the path under the project's test source set."),

            // ---- readme-sync tools (PR_WORKFLOW role; operate on the real checkout, Markdown docs only) ----
            entry("doc-write", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Create or update a Markdown documentation file in the repository checkout. "
                            + "The path is checkout-relative and must be a Markdown file (*.md / *.markdown) "
                            + "that matches the workflow's configured documentation include patterns — any "
                            + "other file is rejected. Writing an existing path overwrites it. Absolute paths "
                            + "or `..` traversal are rejected.",
                    objectSchema(
                            prop("path",    "string", "Checkout-relative path of the Markdown file (e.g. \"README.md\", \"doc/setup/install.md\")."),
                            prop("content", "string", "Full UTF-8 Markdown content of the file."),
                            required("path", "content")))
                .withHint("**Update a document**: `doc-write` writes the whole Markdown file; the path must match the workflow's include patterns."),
            entry("doc-delete", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Delete an obsolete Markdown documentation file from the repository checkout. "
                            + "The path must be a Markdown file matching the workflow's configured include "
                            + "patterns and must already exist. Absolute paths or `..` traversal are rejected.",
                    objectSchema(
                            prop("path", "string", "Checkout-relative path of the Markdown file to delete."),
                            required("path")))
                .withHint("**Drop a document the change made obsolete**: `doc-delete` removes a matching Markdown file."),

            // ---- i18n-coverage tools (PR_WORKFLOW role; operate on the real checkout, locale files only) ----
            entry("i18n-write", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Create or update an i18n locale file (*.properties / *.json) in the repository "
                            + "checkout with drafted translations. The path is checkout-relative and must "
                            + "match the workflow's configured i18n include patterns — any other file is "
                            + "rejected. `content` must be the COMPLETE file content (all existing keys plus "
                            + "the new/updated translations), because writing overwrites the file. Absolute "
                            + "paths or `..` traversal are rejected.",
                    objectSchema(
                            prop("path",    "string", "Checkout-relative path of the locale file (e.g. \"i18n/messages_de.properties\", \"i18n/fr.json\")."),
                            prop("content", "string", "Full UTF-8 content of the locale file after applying the drafted translations."),
                            required("path", "content")))
                .withHint("**Write a locale file**: `i18n-write` takes the complete file — existing keys plus the added translations — because writing overwrites it."),
            entry("i18n-delete", ToolKind.PR_WORKFLOW, EnumSet.of(Role.PR_WORKFLOW),
                    "Delete an obsolete i18n locale file from the repository checkout. The path must be "
                            + "a locale file (*.properties / *.json) matching the workflow's configured "
                            + "include patterns and must already exist. Absolute paths or `..` traversal are "
                            + "rejected.",
                    objectSchema(
                            prop("path", "string", "Checkout-relative path of the locale file to delete."),
                            required("path")))
                .withHint("**Drop a locale file that is no longer needed**: `i18n-delete` removes a matching locale file."),

            // ---- agent-control tool (dispatched before the tool families) ----
            entry("execute-code", ToolKind.AGENT_CONTROL,
                    EnumSet.of(Role.CODING, Role.WRITER, Role.PR_WORKFLOW, Role.REVIEW),
                    "Run a Python program that can call the tools this run makes available to it "
                            + "and print one compact result. Reach for it when the answer takes "
                            + "more than one tool call, or a step between them: reading several "
                            + "files and comparing what they declare, searching the tree and "
                            + "keeping only the hits that matter, distilling a long result down "
                            + "to the fields you need, counting or grouping matches, checking one "
                            + "property across many files. Only what you print comes back, so a "
                            + "result you would otherwise read in full stays out of your context "
                            + "— but a single read or search is still cheaper as a direct "
                            + "call. In the program, tools.list() shows what you may call, "
                            + "tools.describe(name) its arguments, and tools.call(name, "
                            + "arguments) calls it — a tool outside that set is refused. "
                            + "The program starts in an empty working directory of its own, so "
                            + "these tools are how you find the checkout: read repository files "
                            + "with tools.call(\"cat\", {\"path\": \"...\"}) or search them with "
                            + "tools.call(\"rg\", {\"args\": [\"pattern\"]}). "
                            + "The built-in writes, the branch switch and the build tools are not "
                            + "callable from inside a program — call those directly, where the "
                            + "round accounting sees them. Selected MCP tools are callable, and an "
                            + "MCP tool may act outside the bot (open an issue, post a comment); "
                            + "such a call is not counted as a round. print() hands the answer back.",
                    objectSchema(
                            prop("code", "string", "Python source to execute."),
                            required("code")))
                .withHint("**An answer that takes several reads, or a step between them**: reach for `execute-code` instead of crawling. One program gathers, filters and prints, and the intermediate results never enter your context — several `cat` or `rg` calls become one; its description says how to call it.")
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
            Map.entry("cmake",   "Run CMake in the workspace root (e.g. --build . --config Debug)."),
            Map.entry("execute", "Run a validation script committed inside the repository (e.g. scripts/validate.sh).")
    );


    /**
     * One line per validation tool for the prompt's tool-selection strategy. Validation tools come
     * from configuration ({@link AgentConfigProperties.ValidationConfig#getAvailableTools()}) rather
     * than from {@link #STATIC_TOOLS}, so their strategy line cannot ride on an {@link Entry} the way
     * {@link #usageHint(String)} reads it — this map is their counterpart. A tool with no entry here
     * contributes nothing, which is the norm: `mvn` or `npm` need no explanation of when to run them.
     */
    private static final Map<String, String> VALIDATION_USAGE_HINTS = Map.of(
            "execute", "**A repository with no build command**: `execute` runs a committed script, for example `scripts/validate.sh`; exit code 0 passes."
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

    /** Names of all PR-workflow tools (E2E role). Unfiltered. */
    public List<String> prWorkflowToolNames() {
        return namesOf(ToolKind.PR_WORKFLOW);
    }

    /** Names of PR-workflow tools the bot is allowed to invoke. See {@link #contextToolNames(Set)} for whitelist semantics. */
    public List<String> prWorkflowToolNames(Set<String> allowed) {
        return filterNames(prWorkflowToolNames(), allowed);
    }

    /** Names of the agent-control tools. Unfiltered. */
    public List<String> agentControlToolNames() {
        return namesOf(ToolKind.AGENT_CONTROL);
    }

    /**
     * Names of the built-in tools a role may call, in the catalogue's display order: the static
     * tools declared for the role — PR-workflow tools included, classification-only aliases
     * excluded, since they are never advertised — then, for {@link Role#CODING} only, the
     * configured validation tools, matching {@link #nativeDescriptors}. Unfiltered; see
     * {@link #contextToolNames(Set)} for the whitelist semantics.
     */
    public List<String> builtinToolNames(Role role) {
        List<String> out = new ArrayList<>();
        for (Entry e : STATIC_TOOLS) {
            if (e.schema() != null && e.roles().contains(role)) {
                out.add(e.name());
            }
        }
        if (role == Role.CODING) {
            out.addAll(validationToolNames());
        }
        return List.copyOf(out);
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

    /**
     * The usage hint for a tool, or empty when it needs no strategy line. Read from the tool's own
     * definition ({@link Entry#usageHint()}), so a hint is added, changed or dropped together with
     * the tool it describes. Validation tools, being configuration rather than declarations, fall
     * back to {@link #VALIDATION_USAGE_HINTS}.
     */
    public Optional<String> usageHint(String tool) {
        String name = normalize(tool);
        Entry entry = byName.get(name);
        if (entry != null && entry.usageHint() != null) {
            return Optional.of(entry.usageHint());
        }
        return Optional.ofNullable(VALIDATION_USAGE_HINTS.get(name));
    }

    /**
     * The native JSON schema of a built-in tool, whose property order is the order its executor
     * reads positional arguments in. Empty for a tool the catalog does not declare — validation
     * and MCP tools come from configuration, and the classification-only aliases have no schema.
     */
    public Optional<JsonNode> schemaOf(String tool) {
        Entry entry = byName.get(normalize(tool));
        return Optional.ofNullable(entry != null ? entry.schema() : null);
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
            // AGENT_CONTROL is silent, so its bucket only keeps this switch exhaustive.
            case CONTEXT, REPOSITORY, MCP, AGENT_CONTROL -> DisplayBucket.CONTEXT;
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
     * (a {@code null} set disables filtering for other roles — test paths only;
     * REVIEW always requires an explicit non-null allowlist). Validation
     * tools come from {@link AgentConfigProperties.ValidationConfig#getAvailableTools()}
     * (coding only); MCP tools come from {@code mcpCatalog} and are passed
     * through unchanged — MCP filtering happens via {@code McpToolSelectionService}.
     */
    public List<ToolDescriptor> nativeDescriptors(Role role, McpToolCatalog mcpCatalog,
                                                  Set<String> allowedBuiltinTools) {
        if (role == Role.REVIEW) {
            allowedBuiltinTools = reviewToolNames(allowedBuiltinTools);
        }
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
        return new Entry(name, kind, roles, description, schema, null);
    }

    /** Runtime-only alias: classified but never exposed to the LLM (schema == null). */
    private static Entry silentAlias(String name, ToolKind kind) {
        return new Entry(name, kind, Set.of(), null, null, null);
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
