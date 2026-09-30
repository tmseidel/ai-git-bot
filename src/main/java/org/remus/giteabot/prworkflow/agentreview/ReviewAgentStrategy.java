package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.AgentStrategy;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.agent.shared.ToolFailures;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only {@link AgentStrategy} powering the agentic PR-review workflow.
 *
 * <p>The strategy is deliberately <em>read-only</em>: it advertises the
 * {@link ToolCatalog.Role#REVIEW} descriptor surface (repository-exploration
 * tools such as {@code cat}, {@code rg}, {@code find}, {@code tree},
 * {@code git-log}, {@code git-blame}, the read-only issue helpers and MCP
 * tools) and routes execution through {@link AgentToolRouter.Mode#REVIEW},
 * which rejects file-mutation, validation/build and branch-switching built-ins.
 * Operators must select only read-only MCP tools for the review configuration.</p>
 *
 * <p>Two transports are supported, mirroring
 * {@link org.remus.giteabot.agent.issueimpl.CodingAgentStrategy}: in
 * {@link ToolingMode#NATIVE} mode tool calls arrive as a structured
 * {@link ChatTurn} and results are fed back as {@code tool}-role messages; when
 * the client cannot do native function calling the loop falls back to the
 * legacy JSON protocol ({@code requestFiles}/{@code requestTools}/
 * {@code runTools}) parsed via {@link AiResponseParser}. In both cases the loop
 * accepts a non-empty final text without tool/context requests. In both modes
 * its stop reason must also indicate completion. Exhausted budgets and empty
 * or truncated replies are failures, never fallback reviews.</p>
 */
@Slf4j
public final class ReviewAgentStrategy implements AgentStrategy {

    /** Fetches repository file contents for the legacy {@code requestFiles} protocol. */
    @FunctionalInterface
    public interface FileFetcher {
        String fetch(String owner, String repo, String ref, List<String> files);
    }

    private final String systemPrompt;
    private final AgentToolRouter toolRouter;
    private final ToolCatalog catalog;
    private final McpToolCatalog mcpToolCatalog;
    private final Set<String> allowedBuiltinTools;

    // Legacy-mode collaborators (mirror CodingAgentStrategy).
    private final AiResponseParser responseParser;
    private final FileFetcher fileFetcher;
    private final int maxContextRounds;
    private final int maxToolRounds;
    private int toolRounds;

    /** Read-only context-fetch rounds consumed in legacy mode. */
    private int contextRounds = 0;

    public ReviewAgentStrategy(String systemPrompt,
                               AgentToolRouter toolRouter,
                               ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog,
                               Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser,
                               BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher,
                               int maxContextRounds) {
        this(systemPrompt, toolRouter, catalog, mcpToolCatalog, allowedBuiltinTools, responseParser,
                branchSwitcher, fileFetcher, maxContextRounds, maxContextRounds);
    }

    /** Configures independent legacy context and native tool-round limits. */
    public ReviewAgentStrategy(String systemPrompt, AgentToolRouter toolRouter, ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog, Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser, BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher, int maxContextRounds, int maxToolRounds) {
        this.systemPrompt = systemPrompt;
        this.toolRouter = toolRouter;
        this.catalog = catalog;
        this.mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
        this.allowedBuiltinTools = allowedBuiltinTools;
        this.responseParser = responseParser;
        // Retain the constructor parameter for callers; reviews never switch branches.
        this.fileFetcher = fileFetcher;
        this.maxContextRounds = Math.max(1, maxContextRounds);
        this.maxToolRounds = Math.max(1, maxToolRounds);
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public ToolingMode preferredToolMode() {
        return ToolingMode.NATIVE;
    }

    /** Read-only review toolbox plus the explicitly selected MCP catalog. */
    @Override
    public List<ToolDescriptor> toolDescriptors() {
        return catalog.nativeDescriptors(ToolCatalog.Role.REVIEW, mcpToolCatalog, allowedBuiltinTools);
    }

    @Override
    public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
        StopReason expected = turn.hasToolCalls() ? StopReason.TOOL_USE : StopReason.END_TURN;
        if (turn.stopReason() != expected) {
            return incompleteTurn(ctx, turn.stopReason());
        }
        // Only a completed, non-empty text turn may become the final review.
        if (!turn.hasToolCalls()) {
            return finish(ctx, turn.assistantText());
        }

        if (toolRounds >= maxToolRounds) {
            return new StepDecision.Finish(onBudgetExhausted(ctx));
        }
        toolRounds++;

        // Execute every requested (read-only) tool and feed the results back.
        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        for (ToolCall call : turn.toolCalls()) {
            requests.add(toRequest(call));
        }
        List<ToolResult> results = executeAll(ctx, requests);
        List<StepDecision.ToolCallResult> packaged = packageResults(requests, results, turn.toolCalls());
        return new StepDecision.ContinueWithToolResults(packaged, null);
    }

    @Override
    public StepDecision stepLegacy(AgentRunContext ctx, ChatTurn turn, int round) {
        // Reject any turn carrying tool calls, even with END_TURN: the legacy
        // JSON protocol has no native-call envelope, so such a mix can only be
        // provider metadata leaking into a text turn — never accept its text.
        if (turn.stopReason() != StopReason.END_TURN || turn.hasToolCalls()) {
            return incompleteTurn(ctx, turn.stopReason());
        }
        return step(ctx, turn.assistantText(), round);
    }

    private StepDecision incompleteTurn(AgentRunContext ctx, StopReason reason) {
        log.warn("Agentic review stopped before completion for PR #{}: {}", ctx.issueNumber(), reason);
        return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                "Agentic review stopped before completion: " + reason));
    }

    @Override
    public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
        ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);

        // Collect every read-only request the model made: context tools
        // (requestTools), action tools (runTools — e.g. get-issue/search-issues,
        // all harmless in WRITER mode) and explicit file requests.
        List<ImplementationPlan.ToolRequest> toolRequests = new ArrayList<>();
        List<String> requestFiles = null;
        if (plan != null) {
            if (plan.getRequestTools() != null) {
                toolRequests.addAll(plan.getRequestTools());
            }
            if (plan.hasToolRequest()) {
                toolRequests.addAll(plan.getEffectiveToolRequests());
            }
            requestFiles = plan.getRequestFiles();
        }
        boolean wantsContext = !toolRequests.isEmpty()
                || (requestFiles != null && !requestFiles.isEmpty());

        // Gather the requested read-only context and ask the model to continue.
        if (wantsContext && contextRounds < maxContextRounds) {
            contextRounds++;
            log.info("Agentic review (legacy) gathering context for PR #{} (round {}/{})",
                    ctx.issueNumber(), contextRounds, maxContextRounds);

            // Review context stays on the original revision; all tools pass through the review policy.
            String context = gatherContext(ctx, requestFiles, toolRequests);
            return new StepDecision.Continue(
                    "Here is the requested repository context:\n" + context
                            + "\n\nContinue your review. When you have gathered enough context, reply with "
                            + "the final review as plain Markdown (no JSON, no further tool requests).");
        }

        if (wantsContext) {
            log.warn("Agentic review (legacy) hit the context-round cap ({}) for PR #{} before completion",
                    maxContextRounds, ctx.issueNumber());
            return new StepDecision.Finish(onBudgetExhausted(ctx));
        }
        return finish(ctx, finalReviewText(plan, aiResponse));
    }

    @Override
    public LoopOutcome onBudgetExhausted(AgentRunContext ctx) {
        log.warn("Agentic review loop exhausted its round budget for PR #{}", ctx.issueNumber());
        return LoopOutcome.fail(ctx.baseBranch(),
                "⚠️ *The review loop reached its round budget limit and could not generate a review.*");
    }

    // ---------------------------------------------------------------------

    private StepDecision finish(AgentRunContext ctx, String review) {
        if (review == null || review.isBlank()) {
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                    "⚠️ *The review feedback was empty or could not be generated by the agent.*"));
        }
        return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), review));
    }

    /**
     * Determines the final review text from a legacy turn. A parseable plan
     * without further requests yields its {@code summary}; otherwise the raw
     * (plain-text) assistant response is the review.
     */
    private String finalReviewText(ImplementationPlan plan, String aiResponse) {
        if (plan == null) {
            return aiResponse;
        }
        String nonJson = responseParser.extractNonJsonResponse(aiResponse);
        if (nonJson != null && !nonJson.isBlank()) {
            return nonJson;
        }
        return (plan.getSummary() != null && !plan.getSummary().isBlank())
                ? plan.getSummary() : aiResponse;
    }

    /** Renders requested files + read-only tool results into a single context block. */
    private String gatherContext(AgentRunContext ctx, List<String> files,
                                 List<ImplementationPlan.ToolRequest> toolRequests) {
        StringBuilder sb = new StringBuilder();
        if (files != null && !files.isEmpty() && fileFetcher != null) {
            String fetched = fileFetcher.fetch(ctx.owner(), ctx.repo(), ctx.baseBranch(), files);
            if (fetched != null && !fetched.isBlank()) {
                sb.append("## Requested Files\n").append(fetched);
            }
        }
        if (toolRequests != null && !toolRequests.isEmpty()) {
            List<ToolResult> results = executeAll(ctx, toolRequests);
            StringBuilder tools = new StringBuilder();
            for (int i = 0; i < toolRequests.size(); i++) {
                ImplementationPlan.ToolRequest req = toolRequests.get(i);
                ToolResult result = results.get(i);
                if (result.outputTruncated()) {
                    tools.append("Output is truncated; this is not complete evidence.\n");
                }
                tools.append("### `").append(req.getTool());
                if (req.getArgs() != null && !req.getArgs().isEmpty()) {
                    tools.append(' ').append(String.join(" ", req.getArgs()));
                }
                tools.append("`\n");
                if (result.success()) {
                    String output = result.output();
                    tools.append(output == null || output.isBlank() ? "(no output)" : output).append("\n\n");
                } else {
                    tools.append("Failed: ").append(ToolFailures.describe(result)).append("\n\n");
                }
            }
            if (!tools.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("## Repository Tool Results\n").append(tools.toString().strip());
            }
        }
        return sb.isEmpty() ? "No additional repository context could be retrieved." : sb.toString();
    }

    private List<ToolResult> executeAll(AgentRunContext ctx, List<ImplementationPlan.ToolRequest> requests) {
        List<ToolResult> results = new ArrayList<>(requests.size());
        for (ImplementationPlan.ToolRequest req : requests) {
            results.add(toolRouter.execute(AgentToolRouter.Mode.REVIEW,
                    new ToolCallContext(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                            ctx.workspaceDir(), req, ctx.diffSummary())));
        }
        return results;
    }

    private List<StepDecision.ToolCallResult> packageResults(List<ImplementationPlan.ToolRequest> requests,
                                                             List<ToolResult> results,
                                                             List<ToolCall> originalCalls) {
        Map<String, String> byId = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            byId.put(requests.get(i).getId(), results.get(i).formatForAi());
        }
        List<StepDecision.ToolCallResult> out = new ArrayList<>(originalCalls.size());
        for (ToolCall call : originalCalls) {
            out.add(new StepDecision.ToolCallResult(call.id(),
                    byId.getOrDefault(call.id(), "[no result]")));
        }
        return out;
    }

    /**
     * Converts a native {@link ToolCall} into the positional-args
     * {@link ImplementationPlan.ToolRequest} that {@link AgentToolRouter}
     * expects. Only the read-only WRITER tool schemas are relevant here.
     */
    private ImplementationPlan.ToolRequest toRequest(ToolCall call) {
        List<String> args = new ArrayList<>();
        JsonNode root = call.args();
        if (root != null && root.isObject()) {
            if (McpTools.looksLikeMcpTool(call.name())) {
                // MCP: pass the whole arguments object through as a single JSON blob.
                args.add(root.toString());
            } else {
                JsonNode varargs = root.get("args");
                if (varargs != null && varargs.isArray()) {
                    varargs.forEach(node -> args.add(asString(node)));
                } else {
                    addIfPresent(root, "path", args);
                    addIfPresent(root, "branch", args);
                    addIfPresent(root, "startLine", args);
                    addIfPresent(root, "endLine", args);
                    if (args.isEmpty() && !root.isEmpty()) {
                        args.add(root.toString());
                    }
                }
            }
        }
        return ImplementationPlan.ToolRequest.builder()
                .id(call.id() == null || call.id().isBlank() ? UUID.randomUUID().toString() : call.id())
                .tool(call.name())
                .args(args)
                .build();
    }

    private static void addIfPresent(JsonNode root, String field, List<String> out) {
        JsonNode v = root.get(field);
        if (v != null && !v.isMissingNode() && !v.isNull()) {
            out.add(asString(v));
        }
    }

    private static String asString(JsonNode node) {
        return node.isString() ? node.asString() : node.toString();
    }
}
