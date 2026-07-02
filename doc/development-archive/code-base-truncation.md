# Implementation Plan: Code-Base Structure Extraction Agent Tools

## Goal

Add two new agent-invokable CONTEXT tools (`ctags-signatures` and `ctags-deps`) that let the
AI agent extract lightweight structural maps from source files using Universal Ctags under the
hood. The agent calls these on-demand during its loop — just like `cat`, `rg`, or `tree` —
instead of receiving pre-built enrichment in the prompt.

---

## What Already Exists

| Layer | How it works |
|---|---|
| **Tool registration** | `ToolCatalog.STATIC_TOOLS` — one `Entry` per built-in tool (name, `ToolKind`, roles, description, JSON schema). Adding a tool means appending one entry here. |
| **Tool dispatch** | `AgentToolRouter.executeCoding()` / `executeWriter()` — routes context tools to `ToolExecutionService.executeContextTool()`. |
| **Tool execution** | `ToolExecutionService.executeContextTool()` — switch on normalized tool name, delegates to private `execute*Tool()` methods. Each tool resolves the path via `resolveWorkspacePath()`, then either calls `executeCommand()` (for git, rg) or does custom logic (for cat, tree, find). Output is wrapped in `ToolResult` and truncated at `MAX_TOOL_OUTPUT_CHARS` (10,000). |
| **Subprocess execution** | `ToolExecutionService.executeCommand()` — runs a command array in the workspace directory, captures stdout, enforces timeout from `agentConfig.getValidation().getToolTimeoutSeconds()`. |
| **Docker runtime** | Ubuntu Noble 24.04 with `apt-get install` already pulling 20+ packages. `universal-ctags` is available in `universe`. |
| **Existing context tools** | `rg`/`ripgrep`/`grep`, `find`, `cat`, `git-log`, `git-blame`, `tree`, `branch-switcher` — all read-only, workspace-scoped, silent (never posted as comments). |

The new `ctags-signatures` and `ctags-deps` tools follow the exact same pattern as `git-log` and
`git-blame`: resolve a workspace path, build a `ctags` command array, call `executeCommand()`.

---

## Components to Create or Modify

- [ ] **Dockerfile** — Add `universal-ctags` to the `apt-get install` line (~line 38-46).
- [ ] **ToolCatalog** — Add two `Entry` records to `STATIC_TOOLS` (one per tool).
- [ ] **ToolExecutionService** — Add two `case` branches to `executeContextTool()`, and two
  private methods: `executeCtagsSignaturesTool()` and `executeCtagsDepsTool()`.
- [ ] **AgentToolRouter** — No changes needed. New tools are `ToolKind.CONTEXT`, so the
  existing `catalog.isContext(tool)` branch routes them automatically.

---

## Tool Definitions

### `ctags-signatures`

```
Description: Extract function, class, method, and interface signatures from a source file
using Universal Ctags. Returns a compact structural map — NO implementation details.
Use this to understand a file's architecture without consuming full content.

Args: ["path/to/file"] or ["path/to/file", "limit"]
  limit: max signatures to return (default: 100)

Output (example):
```
```java
class OrderProcessor {
  constructor OrderProcessor(String processorId)
  method processOrder(String orderId, double amount)
  method internalCleanup()
}
```

### `ctags-deps`

```
Description: Extract imports, includes, and namespace/package declarations from a source
file using Universal Ctags. Returns the declared namespace and all external dependencies.
Use this to understand which modules a file depends on.

Args: ["path/to/file"]

Output (example):
```
```json
{
  "file": "Button.tsx",
  "declared_namespace_or_package": "none",
  "dependencies": [
    "react",
    "../hooks/useAuth",
    "@mui/material/Button"
  ]
}
```

---

## Implementation Sequence

### 1. Dockerfile: Install Universal Ctags

Add `universal-ctags \` to the `apt-get install` block (alphabetical, between `unzip xz-utils tini` and `maven`):

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl wget git bash gnupg lsb-release apt-transport-https \
        unzip xz-utils tini \
        universal-ctags \
        maven \
        ...
```

Verify: build the image, run `ctags --version`, confirm `ctags --list-languages` includes
Java, Python, TypeScript, JavaScript, Go, Rust, C/C++, C#, Ruby.

### 2. ToolCatalog: Register Both Tools

Add two entries to `STATIC_TOOLS` in `ToolCatalog.java`, right after the existing context
tools (after `tree`, before the `silentAlias` entries):

```java
entry("ctags-signatures", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
        "Extract function, class, method, and interface signatures from a source file "
                + "using Universal Ctags. Returns a compact structural map — NO implementation "
                + "details. Use this to understand a file's architecture without consuming full "
                + "content. Args: [\"path/to/file\"] or [\"path/to/file\", \"limit\"].",
        objectSchema(
                prop("path",  "string",  "Repository-relative path to the file."),
                prop("limit", "integer", "Max signatures to return (default: 100)."),
                required("path"))),

entry("ctags-deps", ToolKind.CONTEXT, EnumSet.of(Role.CODING, Role.WRITER),
        "Extract imports, includes, and namespace/package declarations from a source file "
                + "using Universal Ctags. Returns JSON with the declared namespace and all "
                + "external dependencies. Use this to understand which modules a file depends on. "
                + "Args: [\"path/to/file\"].",
        objectSchema(
                prop("path", "string", "Repository-relative path to the file."),
                required("path"))),
```

### 3. ToolExecutionService: Implement Both Tools

#### 3a. Add `case` branches to `executeContextTool()` switch

```java
case "ctags-signatures" -> executeCtagsSignaturesTool(workspaceDir, arguments);
case "ctags-deps"       -> executeCtagsDepsTool(workspaceDir, arguments);
```

#### 3b. `executeCtagsSignaturesTool(Path workspaceDir, List<String> arguments)`

```java
private ToolResult executeCtagsSignaturesTool(Path workspaceDir, List<String> arguments) {
    if (arguments == null || arguments.isEmpty()) {
        return new ToolResult(false, -1, "", "ctags-signatures requires a file path");
    }
    String relativePath = arguments.getFirst();
    int limit = 100;
    if (arguments.size() > 1 && isInteger(arguments.get(1))) {
        limit = Integer.parseInt(arguments.get(1));
    }
    Path filePath;
    try {
        filePath = resolveWorkspacePath(workspaceDir, relativePath);
    } catch (IOException e) {
        return new ToolResult(false, -1, "", e.getMessage());
    }
    if (!Files.isRegularFile(filePath)) {
        return new ToolResult(false, 1, "", "File not found: " + relativePath);
    }

    // Run ctags: JSON output with name, kind, signature fields
    String[] command = {"ctags", "--output-format=json", "--fields=+neKz",
                        filePath.toAbsolutePath().toString()};
    ToolResult raw = executeCommand(workspaceDir, command);
    if (!raw.success()) {
        return raw;  // ctags error (e.g. unrecognized file)
    }

    // Parse JSON lines into structured pseudo-code
    String formatted = formatCtagsSignatures(raw.output(), limit);
    return new ToolResult(true, 0, formatted, "");
}

/**
 * Parses ctags JSON lines into a Markdown code block with pseudo-code signatures.
 * Filters to classes, interfaces, methods, functions, constructors, macros, namespaces.
 * Skips variables and local scopes to minimize context consumption.
 */
private static String formatCtagsSignatures(String ctagsJsonOutput, int limit) {
    // Implementation: split on newlines, parse each JSON line with AgentJackson,
    // extract "name", "kind", "signature" fields, format as:
    //   class Foo {
    //     constructor Foo(String arg)
    //     method bar(int x)
    //     method baz()
    //   }
    // Capped at `limit` entries. Wrap in fenced code block with language marker.
}
```

The `formatCtagsSignatures` method needs ~80 lines: a simple JSON-line parser that reads
ctags' `--output-format=json` format. Ctags JSON lines look like:
```json
{"_type": "tag", "name": "OrderProcessor", "kind": "class", "line": 15, ...}
{"_type": "tag", "name": "processOrder", "kind": "method", "signature": "(String orderId, double amount)", ...}
```

**Language marker detection:** Derive from file extension (same mapping as the user's
pseudo-code: `.py`→python, `.java`→java, `.ts`→typescript, `.js`→javascript, `.cpp`→cpp, etc.)

**Hierarchical grouping:** When multiple signatures exist, nest methods/constructors under
their class. Output a flat list if no class context is available (top-level functions).

#### 3c. `executeCtagsDepsTool(Path workspaceDir, List<String> arguments)`

```java
private ToolResult executeCtagsDepsTool(Path workspaceDir, List<String> arguments) {
    if (arguments == null || arguments.isEmpty()) {
        return new ToolResult(false, -1, "", "ctags-deps requires a file path");
    }
    String relativePath = arguments.getFirst();
    Path filePath;
    try {
        filePath = resolveWorkspacePath(workspaceDir, relativePath);
    } catch (IOException e) {
        return new ToolResult(false, -1, "", e.getMessage());
    }
    if (!Files.isRegularFile(filePath)) {
        return new ToolResult(false, 1, "", "File not found: " + relativePath);
    }

    // Run ctags: ONLY imports (i) and namespaces (n), JSON output
    String[] command = {"ctags", "--output-format=json",
                        "--kinds-all=-*", "--kinds-all=+i+n",
                        "--fields=+k",
                        filePath.toAbsolutePath().toString()};
    ToolResult raw = executeCommand(workspaceDir, command);
    if (!raw.success()) {
        return raw;
    }

    String json = formatCtagsDependencies(new File(relativePath).getName(), raw.output());
    return new ToolResult(true, 0, json, "");
}

/**
 * Parses ctags JSON lines into a structured dependency map.
 */
private static String formatCtagsDependencies(String fileName, String ctagsJsonOutput) {
    // Split output into lines, parse each JSON line,
    // classify by "kind": "import"/"include" → dependencies array,
    // "namespace"/"package" → declared_namespace_or_package.
    // Return as compact JSON string.
}
```

---

## Error Handling & Edge Cases

| Scenario | Behavior |
|---|---|
| File not found | Return `ToolResult(false, 1, "", "File not found: ...")` |
| Path escapes workspace | `resolveWorkspacePath()` throws — returns error ToolResult |
| ctags not installed | `executeCommand()` returns non-zero exit — error propagates |
| ctags returns nothing (empty file, unsupported language) | Return `ToolResult(true, 0, "No signatures/dependencies detected.", "")` |
| ctags output exceeds `MAX_TOOL_OUTPUT_CHARS` | Already handled by `truncateOutput()` in `executeCommand()` — head-only truncation with a marker |
| Binary file | ctags returns empty/no tags — handled gracefully |
| Constructor tagged as `method` (ctags 5.9) | Ubuntu Noble ships ctags 5.9.0, which lacks a `constructor` kind. Constructors appear with `kind: "method"` and `name` matching the enclosing class. The `formatCtagsSignatures` parser must detect this pattern (method with same name as enclosing class → render as `constructor`). ctags 6.x adds a native `constructor` kind — our parser should handle both.

---

## Testing Strategy

1. **Unit test: `formatCtagsSignatures()`** — Feed it known ctags JSON output, verify correct
   pseudo-code formatting. Include edge cases: empty output, single signature, nested classes,
   max limit exceeded.

2. **Unit test: `formatCtagsDependencies()`** — Feed known ctags JSON, verify correct
   dependency map. Include: no deps, multiple deps, namespace present/absent.

3. **Integration test: `executeCtagsSignaturesTool()`** — Create a temp Java file in the test
   workspace (a simple class with two methods), call the tool, verify output contains
   `class TestService {`, `method doWork()`, etc.

4. **Integration test: `executeCtagsDepsTool()`** — Create a temp file with imports, verify
   correct dependency extraction.

5. **Docker smoke test** — After building the image, run a container and verify:
   ```bash
   ctags --output-format=json --fields=+neKz src/main/java/org/remus/giteabot/admin/Bot.java
   ```
   produces valid JSON with class and method entries.

---

## ADR: Universal Ctags as the Extraction Engine

**Status:** Proposed

**Context**
We need a mechanism for the AI agent to extract function/class/method signatures and
dependency information from source files in 10+ languages. The extraction must run inside the
Docker container as a subprocess, produce machine-parseable output, and add minimal overhead.

**Options Considered**

1. **Universal Ctags** — CLI tool invoked via `ProcessBuilder` (same pattern as `git`, `rg`).
   JSON output with `--output-format=json`. Single `apt-get install`.
   - ✅ Pros: 50+ language support. Battle-tested (powers GitHub's symbol navigation, VS Code
     go-to-definition). JSON is trivially parseable. Zero Java dependencies. Fits the existing
     `executeCommand()` pattern perfectly.
   - ❌ Cons: ~20ms subprocess overhead per invocation. Must read file from disk (already true
     — tools operate on the workspace directory).

2. **Tree-sitter Java bindings** — Embed Tree-sitter grammars via JNI/JNA.
   - ✅ Pros: In-process, no subprocess overhead. Can parse in-memory strings.
   - ❌ Cons: Per-language grammar JARs/native libs (2-5MB each × 10+ languages). JNI stability
     across architectures. Must implement extraction logic per language. Massive dependency bloat
     compared to a ~2MB ctags binary.

**Decision**
We choose **Universal Ctags** because it fits the existing `executeCommand()` tool execution
pattern, has zero Java dependency footprint, and supports all languages with a single tool.

**Consequences**
- Docker image grows by ~2 MB.
- Tools are limited to files-on-disk (workspace directory), which is already the model for all
  context tools.
- Ctags JSON format is stable but should be guarded with error handling for unknown languages.

---

## Review Checklist (before implementation)

- [ ] No manual getters/setters — Lombok used everywhere
- [ ] No raw `@Autowired` field injection — constructor injection only
- [ ] DTOs at API boundary — `formatCtagsDependencies()` returns a JSON `String`; `formatCtagsSignatures()` returns a Markdown `String`. No new DTO classes needed.
- [ ] Java 21 features: pattern matching in `formatCtagsSignatures()` switch on ctags `kind`, text blocks for test fixtures
- [ ] Tests match existing project style — use the `ToolExecutionService` test approach (create temp workspace, run tool, assert on ToolResult)
- [ ] Context tools are silent (`isSilent` returns true for `ToolKind.CONTEXT`) — correct, no comment leaking
- [ ] Workspace path resolution via `resolveWorkspacePath()` prevents directory traversal attacks
