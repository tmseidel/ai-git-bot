package org.remus.giteabot.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.McpConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-bot router that decides which executor handles a given AI tool request.
 * <p>
 * Two modes are supported:
 * <ul>
 *     <li>{@link Mode#CODING} — file → MCP → context → generic validation tool,
 *     mirroring the historic {@code IssueImplementationService.executeAllTools}.</li>
 *     <li>{@link Mode#WRITER} — get-issue / search-issues → MCP → context, with a
 *     curated repository payload for issue lookups, mirroring
 *     {@code WriterAgentService.executeTools}.</li>
 * </ul>
 * Behaviour is deliberately byte-equivalent to the previous in-line dispatch;
 * the abstraction exists so future steps can introduce tracing, retries and
 * policy without further duplication. Tool classification is delegated to
 * {@link ToolCatalog} so categorisation lives in exactly one place.
 */
@Slf4j
public class AgentToolRouter {

    public enum Mode { CODING, WRITER }

    private final ToolExecutionService toolExecutionService;
    private final ToolCatalog catalog;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpConfiguration mcpConfiguration;
    private final McpToolCatalog mcpToolCatalog;
    private final RepositoryApiClient repositoryClient;
    /** Whitelist of built-in tool names; {@code null} disables enforcement (test paths only). */
    private final Set<String> allowedBuiltinTools;


    public AgentToolRouter(ToolExecutionService toolExecutionService,
                           ToolCatalog catalog,
                           McpOrchestrationService mcpOrchestrationService,
                           McpConfiguration mcpConfiguration,
                           McpToolCatalog mcpToolCatalog,
                           RepositoryApiClient repositoryClient,
                           Set<String> allowedBuiltinTools) {
        this.toolExecutionService = toolExecutionService;
        this.catalog = catalog;
        this.mcpOrchestrationService = mcpOrchestrationService;
        this.mcpConfiguration = mcpConfiguration;
        this.mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
        this.repositoryClient = repositoryClient;
        this.allowedBuiltinTools = allowedBuiltinTools;
    }

    public boolean isMcpTool(String toolName) {
        return McpTools.isMcpTool(mcpOrchestrationService, mcpToolCatalog, toolName);
    }

    /**
     * Executes a single tool request. Behaviour matches the legacy in-line dispatch
     * of the corresponding agent service for the given mode.
     */
    public ToolResult execute(Mode mode, ToolCallContext context) {
        String tool = context.tool();
        if (tool.isBlank()) {
            return new ToolResult(false, -1, "", "Empty tool name");
        }
        ToolResult denied = enforceWhitelist(tool);
        if (denied != null) {
            return denied;
        }
        try {
            return switch (mode) {
                case CODING -> executeCoding(context);
                case WRITER -> executeWriter(context);
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

    private ToolResult executeCoding(ToolCallContext ctx) {
        String tool = ctx.tool();
        List<String> args = ctx.args();
        log.debug("Executing tool: {} {}", tool, String.join(" ", args));
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
        log.debug("Executing tool: {} {}", original, String.join(" ", args));
        if ("get-issue".equals(lower)) {
            Long issue = parseIssueNumber(args, ctx.issueNumber());
            return new ToolResult(true, 0,
                    toJson(curateIssue(repositoryClient.getIssueDetails(ctx.owner(), ctx.repo(), issue))), "");
        }
        if ("search-issues".equals(lower)) {
            String query = args.isEmpty() ? "" : args.getFirst();
            return new ToolResult(true, 0,
                    toJson(repositoryClient.searchIssues(ctx.owner(), ctx.repo(), query).stream()
                            .limit(10)
                            .map(this::curateIssue)
                            .toList()), "");
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

    private String toJson(Object value) {
        try {
            return AgentJackson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}

