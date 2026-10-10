package org.remus.giteabot.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.codeexecution.CodeExecutionScope;
import org.remus.giteabot.agent.codeexecution.PythonExecutionOutcome;
import org.remus.giteabot.agent.codeexecution.PythonExecutionService;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-bot router that decides which executor handles a given AI tool request.
 * <p>
 * Agent modes are supported:
 * <ul>
 *     <li>{@link Mode#CODING} — file → MCP → context → generic validation tool,
 *     mirroring the historic {@code IssueImplementationService.executeAllTools}.</li>
 *     <li>{@link Mode#WRITER} — get-issue / search-issues → MCP → context, with a
 *     curated repository payload for issue lookups, mirroring
 *     {@code WriterAgentService.executeTools}.</li>
 *     <li>{@link Mode#REVIEW} — explicit read-only built-ins and selected qualified MCP names.</li>
 * </ul>
 * Behaviour is deliberately byte-equivalent to the previous in-line dispatch;
 * the abstraction exists so future steps can introduce tracing, retries and
 * policy without further duplication. Tool classification is delegated to
 * {@link ToolCatalog} so categorisation lives in exactly one place.
 */
@Slf4j
public class AgentToolRouter {

    public enum Mode { CODING, WRITER, REVIEW }

    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog catalog;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpConfiguration mcpConfiguration;
    private final McpToolCatalog mcpToolCatalog;
    private final RepositoryApiClient repositoryClient;
    /** Whitelist of built-in tool names; {@code null} disables enforcement (test paths only). */
    private final Set<String> allowedBuiltinTools;
    /**
     * The sandbox an {@code execute-code} call runs its program in. {@code null} means this
     * deployment has none wired (test paths), and the tool says so instead of failing obscurely.
     */
    private final PythonExecutionService pythonExecution;


    public AgentToolRouter(ToolExecutionService toolExecutionService,
                           ToolCatalog catalog,
                           McpOrchestrationService mcpOrchestrationService,
                           McpConfiguration mcpConfiguration,
                           McpToolCatalog mcpToolCatalog,
                           RepositoryApiClient repositoryClient,
                           Set<String> allowedBuiltinTools,
                           PythonExecutionService pythonExecution) {
        this.toolExecutionService = toolExecutionService;
        this.catalog = catalog;
        this.mcpOrchestrationService = mcpOrchestrationService;
        this.mcpConfiguration = mcpConfiguration;
        this.mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
        this.repositoryClient = repositoryClient;
        this.allowedBuiltinTools = allowedBuiltinTools;
        this.pythonExecution = pythonExecution;
    }

    public boolean isMcpTool(String toolName) {
        return McpTools.isMcpTool(mcpOrchestrationService, mcpToolCatalog, toolName);
    }

    /**
     * Executes a single tool request. Behaviour matches the legacy in-line dispatch
     * of the corresponding agent service for the given mode.
     */
    public ToolResult execute(Mode mode, ToolCallContext context) {
        return execute(mode, context, null);
    }

    /**
     * The one dispatch path. Both a direct model call and a program's nested call come through here,
     * so the whitelist gates them alike — only the log line tells them apart.
     *
     * @param origin {@code null} when the model called the tool itself, otherwise the control tool the
     *               call came from. Without it a program's reads are logged identically to the model's
     *               own and a run cannot be reconstructed afterwards.
     */
    private ToolResult execute(Mode mode, ToolCallContext context, String origin) {
        String tool = context.tool();
        if (tool.isBlank()) {
            return new ToolResult(false, -1, "", "Empty tool name");
        }
        if (mode == Mode.REVIEW && !catalog.reviewToolNames(allowedBuiltinTools).contains(tool)
                && mcpToolCatalog.tools().stream().noneMatch(selected -> selected.qualifiedName().equals(tool))) {
            return new ToolResult(false, -1, "", "Tool is not enabled for read-only review: " + tool);
        }
        ToolResult denied = enforceWhitelist(tool);
        if (denied != null) {
            return denied;
        }
        if (origin == null) {
            log.debug("Executing tool: {} {}", tool, String.join(" ", context.args()));
        } else {
            log.debug("Executing nested tool (from {}): {} {}", origin, tool,
                    String.join(" ", context.args()));
        }
        try {
            if (catalog.kindOf(tool) == ToolKind.AGENT_CONTROL) {
                return executeAgentControl(mode, context);
            }
            return switch (mode) {
                case CODING -> executeCoding(context);
                case WRITER -> executeWriter(context);
                case REVIEW -> executeWriter(context);
            };
        } catch (Exception e) {
            return new ToolResult(false, -1, "", e.getMessage());
        }
    }

    /**
     * Blocks built-in tools that are not on the bot's configured whitelist.
     * MCP tools are exempt — they have their own selection layer in
     * {@code McpToolSelectionService}.
     */
    private ToolResult enforceWhitelist(String tool) {
        if (allowedBuiltinTools == null) {
            return null;
        }
        if (isMcpTool(tool)) {
            return null;
        }
        ToolKind kind = catalog.kindOf(tool);
        if (kind == ToolKind.UNKNOWN || kind == ToolKind.MCP) {
            return null;
        }
        String normalized = tool.strip().toLowerCase();
        if (allowedBuiltinTools.contains(normalized)) {
            return null;
        }
        log.warn("Tool '{}' is not on this bot's whitelist; refusing to execute", tool);
        return new ToolResult(false, -1, "",
                "Tool '" + tool + "' is not enabled for this bot. Choose another tool from the "
                        + "available list or ask the operator to enable it in the bot's tool configuration.");
    }

    /** The tool surface this mode offers the model — exactly what a program may call. */
    public List<ToolDescriptor> availableTools(Mode mode) {
        return catalog.nativeDescriptors(role(mode), mcpToolCatalog, allowedBuiltinTools);
    }

    /**
     * Runs one tool on behalf of a sandboxed program. Same dispatch as a direct call: the JSON
     * arguments are flattened to the positional vector this surface's executors declare, and the
     * result is the one the model itself would have been handed — a whitelist refusal included,
     * which comes back in the executor's own words. {@code execute-code} is refused, so a program
     * cannot nest sandboxes.
     */
    public ToolResult executeNested(Mode mode, ToolCallContext base, String tool, JsonNode arguments) {
        if (tool == null || tool.isBlank()) {
            return new ToolResult(false, -1, "", "Empty tool name");
        }
        if (catalog.kindOf(tool) == ToolKind.AGENT_CONTROL) {
            return new ToolResult(false, -1, "", "Tool '" + tool + "' cannot be called from a program");
        }
        if (!isCallableFromProgram(catalog.kindOf(tool), tool)) {
            return new ToolResult(false, -1, "", "Tool '" + tool + "' cannot be called from a program: "
                    + "call this one directly, where the round accounting sees it");
        }
        ToolCallContext nested = new ToolCallContext(base.owner(), base.repo(), base.issueNumber(),
                base.workspaceDir(),
                ImplementationPlan.ToolRequest.builder()
                        .id("execute-code-nested")
                        .tool(tool)
                        .args(ToolArguments.toPositional(tool, arguments,
                                catalog.schemaOf(tool).orElse(null)))
                        .build(),
                base.diffSummary());
        return execute(mode, nested, base.tool());
    }

    /**
     * Whether a program may call a tool: repository reads, plus the selected MCP tools.
     *
     * <p>The built-in writes, the branch switch and the validation tools stay model calls, because
     * the strategy classifies a round by the tools the model asked for ({@code CodingAgentStrategy}
     * counts context rounds, implementation attempts and validation separately): a mutation carried
     * out inside a program would be accounted as a read-only round, and a branch it moved would not
     * be the branch the strategy recorded. MCP is the exception that accounting cannot reach — an
     * MCP server's tools are its own, and one is free to act outside the bot (open an issue, post a
     * message), which no round counts. The same predicate filters the surface the program is
     * offered, so it is never advertised a tool it cannot use.</p>
     */
    private static boolean isCallableFromProgram(ToolKind kind, String tool) {
        if (kind == ToolKind.CONTEXT) {
            // CONTEXT, but it moves the checkout — the one read-only kind with a side effect.
            return !"branch-switcher".equals(tool);
        }
        return kind == ToolKind.REPOSITORY || kind == ToolKind.MCP;
    }

    /**
     * Runs the program. Logged at INFO, unlike the per-call DEBUG lines: this is the one tool that
     * spawns a process and folds an arbitrary number of reads into a single round, so an operator has
     * to be able to see that it happened and how it went without turning on DEBUG for the whole bot.
     * The program text itself is never logged — it is written by the model and may embed repository
     * content.
     */
    private ToolResult executeAgentControl(Mode mode, ToolCallContext context) {
        if (pythonExecution == null) {
            return new ToolResult(false, -1, "", "execute-code is not available in this deployment");
        }
        List<String> args = context.args();
        if (args.isEmpty() || args.getFirst().isBlank()) {
            return new ToolResult(false, -1, "",
                    "execute-code needs the Python program in its \"code\" property, "
                            + "e.g. {\"code\": \"print(1)\"}");
        }
        String program = args.getFirst();
        List<ToolDescriptor> surface = availableTools(mode).stream()
                .filter(tool -> isCallableFromProgram(catalog.kindOf(tool.name()), tool.name()))
                .toList();
        log.info("execute-code: running a {}-char program against {} available tool(s)",
                program.length(), surface.size());
        long started = System.nanoTime();
        CodeExecutionScope scope = new CodeExecutionScope(surface,
                (tool, arguments) -> executeNested(mode, context, tool, arguments));
        PythonExecutionOutcome outcome = pythonExecution.execute(program, scope);
        log.info("execute-code: finished in {} ms — success={}, exit={}, {} char(s) of output{}",
                (System.nanoTime() - started) / 1_000_000, outcome.success(), outcome.exitCode(),
                outcome.output() == null ? 0 : outcome.output().length(),
                outcome.error() == null || outcome.error().isBlank() ? "" : ", error=" + outcome.error());
        return new ToolResult(outcome.success(), outcome.exitCode(), outcome.output(), outcome.error(),
                outcome.outputTruncated());
    }

    /**
     * The descriptor surface a mode is offered — the same role its dispatch gate uses. Review is a
     * read-only surface ({@link ToolCatalog#reviewToolNames}), so advertising it the mutating coding
     * surface it would refuse only makes the model ask for what it cannot have.
     */
    private static ToolCatalog.Role role(Mode mode) {
        return switch (mode) {
            case CODING -> ToolCatalog.Role.CODING;
            case WRITER -> ToolCatalog.Role.WRITER;
            case REVIEW -> ToolCatalog.Role.REVIEW;
        };
    }

    private ToolResult executeCoding(ToolCallContext ctx) {
        String tool = ctx.tool();
        List<String> args = ctx.args();
        // Dispatch order: file > MCP > context > validation. Identical to the
        // historic in-line dispatch but driven by the central ToolCatalog
        // instead of stacking three boolean checks.
        if (catalog.isFile(tool)) {
            return toolExecutionService.executeFileTool(ctx.workspaceDir(), tool, args);
        }
        if (isMcpTool(tool)) {
            return mcpOrchestrationService.executeTool(mcpConfiguration, mcpToolCatalog, tool, args);
        }
        if (catalog.isContext(tool)) {
            return toolExecutionService.executeContextTool(ctx.workspaceDir(), tool, args);
        }
        return toolExecutionService.executeTool(ctx.workspaceDir(), tool, args);
    }

    private ToolResult executeWriter(ToolCallContext ctx) {
        String original = ctx.tool();
        String lower = original.strip().toLowerCase();
        List<String> args = ctx.args();
        switch (lower) {
            case "get-issue" -> {
                Long issue = parseIssueNumber(args, ctx.issueNumber());
                return new ToolResult(true, 0,
                        toJson(curateIssue(repositoryClient.getIssueDetails(ctx.owner(), ctx.repo(), issue))), "");
            }
            case "search-issues" -> {
                String query = args.isEmpty() ? "" : args.getFirst();
                return new ToolResult(true, 0,
                        toJson(repositoryClient.searchIssues(ctx.owner(), ctx.repo(), query).stream()
                                .limit(10)
                                .map(this::curateIssue)
                                .toList()), "");
            }
            case "pr-diff" -> {
                return executePrDiffTool(ctx);
            }
        }
        if (isMcpTool(original)) {
            return mcpOrchestrationService.executeTool(mcpConfiguration, mcpToolCatalog, original, args);
        }
        if (catalog.isContext(lower)) {
            return toolExecutionService.executeContextTool(ctx.workspaceDir(), lower, args);
        }
        return new ToolResult(false, -1, "",
                "Writer tool '" + original + "' is not available. Available tools: get-issue, "
                        + "search-issues, " + String.join(", ", catalog.contextToolNames(allowedBuiltinTools)));
    }

    private Long parseIssueNumber(List<String> args, Long defaultIssueNumber) {
        if (args.isEmpty() || args.getFirst() == null || args.getFirst().isBlank()) {
            return defaultIssueNumber;
        }
        return Long.parseLong(args.getFirst());
    }

    private Map<String, Object> curateIssue(Map<String, Object> issue) {
        Map<String, Object> curated = new LinkedHashMap<>();
        copyIfPresent(issue, curated, "number");
        copyIfPresent(issue, curated, "title");
        copyIfPresent(issue, curated, "body");
        copyIfPresent(issue, curated, "state");
        copyIfPresent(issue, curated, "url");
        copyIfPresent(issue, curated, "html_url");
        copyUser(issue, curated, "user");
        copyUser(issue, curated, "author");
        return curated;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void copyUser(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> userMap) {
            String identity = extractUserIdentity(userMap);
            if (identity != null) {
                target.put(key, Map.of("login", identity));
            }
        }
    }

    private String extractUserIdentity(Map<?, ?> userMap) {
        for (String key : List.of("login", "username", "name")) {
            Object value = userMap.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Executes the pr-diff tool by extracting per-file hunks from the
     * DiffSummary stored in the ToolCallContext.
     */
    private ToolResult executePrDiffTool(ToolCallContext ctx) {
        List<String> args = ctx.args();
        if (args.isEmpty() || args.getFirst() == null || args.getFirst().isBlank()) {
            return new ToolResult(false, -1, "", "pr-diff requires a file path argument");
        }
        String filePath = args.getFirst();

        if (ctx.diffSummary() == null) {
            return new ToolResult(false, -1, "", "No PR diff available in this context");
        }

        String hunk = ctx.diffSummary().fileDiff(filePath);
        if (hunk == null || hunk.isBlank()) {
            List<String> changedFiles = ctx.diffSummary().changedFiles();
            String fileList = String.join(", ", changedFiles);
            return new ToolResult(true, 0,
                    "No diff hunks found for: " + filePath
                            + "\nChanged files: " + fileList, "");
        }
        return new ToolResult(true, 0, hunk, "");
    }

    private String toJson(Object value) {
        try {
            return AgentJackson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
