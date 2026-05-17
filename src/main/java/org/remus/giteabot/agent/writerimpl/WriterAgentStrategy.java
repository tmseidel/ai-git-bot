package org.remus.giteabot.agent.writerimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.AgentStrategy;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AgentStrategy} for the technical-writer agent.
 *
 * <p>The strategy is created fresh per loop run and tracks its own context-round
 * sub-budget. The {@code maxToolRounds} parameter mirrors the previous
 * {@code WriterConfig.maxToolRounds} cap.</p>
 */
@Slf4j
public final class WriterAgentStrategy implements AgentStrategy {


    private final String systemPrompt;
    private final WriterPromptBuilder promptBuilder;
    private final WriterResponseParser responseParser;
    private final AgentSessionService sessionService;
    private final RepositoryApiClient repositoryClient;
    private final BranchSwitcher branchSwitcher;
    private final AgentToolRouter toolRouter;
    private final McpToolCatalog mcpToolCatalog;
    private final ToolCatalog catalog;
    private final java.util.Set<String> allowedBuiltinTools;
    private final int maxToolRounds;

    public WriterAgentStrategy(String systemPrompt,
                               WriterPromptBuilder promptBuilder,
                               WriterResponseParser responseParser,
                               AgentSessionService sessionService,
                               RepositoryApiClient repositoryClient,
                               BranchSwitcher branchSwitcher,
                               AgentToolRouter toolRouter,
                               McpToolCatalog mcpToolCatalog,
                               ToolCatalog catalog,
                               java.util.Set<String> allowedBuiltinTools,
                               int maxToolRounds) {
        this.systemPrompt = systemPrompt;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.sessionService = sessionService;
        this.repositoryClient = repositoryClient;
        this.branchSwitcher = branchSwitcher;
        this.toolRouter = toolRouter;
        this.mcpToolCatalog = mcpToolCatalog;
        this.catalog = catalog;
        this.allowedBuiltinTools = allowedBuiltinTools;
        this.maxToolRounds = maxToolRounds;
    }


    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    // ---------------------------------------------------------------------
    // Step 6 follow-up: native function-calling opt-in.
    // ---------------------------------------------------------------------

    @Override
    public ToolingMode preferredToolMode() {
        return ToolingMode.NATIVE;
    }

    @Override
    public List<ToolDescriptor> toolDescriptors() {
        return catalog.nativeDescriptors(ToolCatalog.Role.WRITER, mcpToolCatalog, allowedBuiltinTools);
    }

    /**
     * Native-mode step: translate the model's read-only {@code tool_calls}
     * into the existing {@link ImplementationPlan.ToolRequest} pipeline.
     * When the model returns text-only (no tool calls), defer to the legacy
     * JSON-parsing path so the terminal "create-issue" decision still works.
     */
    @Override
    public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
        if (!turn.hasToolCalls()) {
            return step(ctx, turn.assistantText(), round);
        }
        int writerRound = round - 1;
        if (writerRound >= maxToolRounds) {
            sessionService.setStatus(ctx.session(), AgentSession.AgentSessionStatus.IN_PROGRESS);
            repositoryClient.postIssueComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                    "⚠️ **AI Technical Writer**: I need more context before I can continue. "
                            + "Please add more details and mention me again.");
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), null));
        }

        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        for (ToolCall call : turn.toolCalls()) {
            requests.add(toRequest(call));
        }
        BranchSwitcher.Result branchSwitch = branchSwitcher.apply(
                ctx.workspaceDir(), ctx.session().getBranchName(), requests, ctx.issueNumber());
        if (branchSwitch.selectedBranch() != null
                && !branchSwitch.selectedBranch().equals(ctx.session().getBranchName())) {
            sessionService.setBranchName(ctx.session(), branchSwitch.selectedBranch());
            ctx.setBaseBranch(branchSwitch.selectedBranch());
        }
        List<ImplementationPlan.ToolRequest> remaining = branchSwitch.remainingToolRequests();
        List<ToolResult> rawResults = executeTools(ctx, remaining);

        // Map id -> result text, then iterate over the original calls to ensure
        // every tool_call gets a paired tool_result (Anthropic/OpenAI reject otherwise).
        java.util.Map<String, String> byId = new java.util.HashMap<>();
        for (int i = 0; i < remaining.size(); i++) {
            byId.put(remaining.get(i).getId(), rawResults.get(i).formatForAi());
        }
        List<StepDecision.ToolCallResult> packaged = new ArrayList<>(turn.toolCalls().size());
        for (ToolCall call : turn.toolCalls()) {
            packaged.add(new StepDecision.ToolCallResult(call.id(),
                    byId.getOrDefault(call.id(),
                            "[branch-switcher handled inline — no separate result]")));
        }
        return new StepDecision.ContinueWithToolResults(packaged, null);
    }

    private ImplementationPlan.ToolRequest toRequest(ToolCall call) {
        List<String> args = new ArrayList<>();
        JsonNode root = call.args();
        if (root != null && root.isObject()) {
            // MCP tools use arbitrary provider-defined schemas; flattening only
            // known fields (path/branch/...) would silently drop everything and
            // the MCP server would reject the call. Pass the full args object
            // as a single JSON-encoded arg — McpOrchestrationService.parseArguments
            // turns it back into a Map.
            if (McpTools.looksLikeMcpTool(call.name())) {
                args.add(root.toString());
            } else {
                JsonNode varargs = root.get("args");
                if (varargs != null && varargs.isArray()) {
                    varargs.forEach(node -> args.add(node.isString() ? node.asString() : node.toString()));
                } else {
                    addIfPresent(root, "path", args);
                    addIfPresent(root, "branch", args);
                    addIfPresent(root, "startLine", args);
                    addIfPresent(root, "endLine", args);
                    // Safety net: model used a tool/property we don't recognise. Fall
                    // through to a JSON blob so the call still carries data and surface
                    // a warning so the schema drift gets noticed.
                    if (args.isEmpty() && !root.isEmpty()) {
                        log.warn("Writer tool '{}' called with unrecognised arg fields {} — "
                                + "passing raw JSON. Update WriterAgentStrategy.toRequest if this tool "
                                + "is supposed to be supported natively.",
                                call.name(), fieldNames(root));
                        args.add(root.toString());
                    }
                }
            }
        }
        return ImplementationPlan.ToolRequest.builder()
                .id(call.id() == null || call.id().isBlank() ? java.util.UUID.randomUUID().toString() : call.id())
                .tool(call.name())
                .args(args)
                .build();
    }

    private static List<String> fieldNames(JsonNode root) {
        return new ArrayList<>(root.propertyNames());
    }

    private static void addIfPresent(JsonNode root, String field, List<String> out) {
        JsonNode v = root.get(field);
        if (v != null && !v.isMissingNode() && !v.isNull()) {
            out.add(v.isString() ? v.asString() : v.toString());
        }
    }

    @Override
    public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
        // Writer round counter is 0-based historically: round 1 of the loop == round 0 of the writer.
        int writerRound = round - 1;
        WriterPlan plan = responseParser.parse(aiResponse);

        if (plan.hasContextRequests() && writerRound >= maxToolRounds) {
            sessionService.setStatus(ctx.session(), AgentSession.AgentSessionStatus.IN_PROGRESS);
            repositoryClient.postIssueComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                    "⚠️ **AI Technical Writer**: I need more context before I can continue. "
                            + "Please add more details and mention me again.");
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), plan));
        }

        if (plan.hasContextRequests() && writerRound < maxToolRounds) {
            List<ImplementationPlan.ToolRequest> contextRequests = buildContextRequests(plan);
            BranchSwitcher.Result branchSwitch = branchSwitcher.apply(
                    ctx.workspaceDir(), ctx.session().getBranchName(), contextRequests, ctx.issueNumber());
            if (branchSwitch.selectedBranch() != null
                    && !branchSwitch.selectedBranch().equals(ctx.session().getBranchName())) {
                sessionService.setBranchName(ctx.session(), branchSwitch.selectedBranch());
                ctx.setBaseBranch(branchSwitch.selectedBranch());
            }
            List<ToolResult> results = executeTools(ctx, branchSwitch.remainingToolRequests());
            return new StepDecision.Continue(
                    promptBuilder.buildToolFeedback(branchSwitch.remainingToolRequests(), results));
        }

        if (plan.hasQuestions() || !plan.isReadyToCreate()) {
            sessionService.setStatus(ctx.session(), AgentSession.AgentSessionStatus.IN_PROGRESS);
            repositoryClient.postIssueComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                    promptBuilder.buildClarifyingQuestionComment(plan));
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), plan));
        }

        Long createdIssueNumber = repositoryClient.createIssue(ctx.owner(), ctx.repo(),
                "AI Created Issue: " + ctx.session().getIssueTitle(),
                promptBuilder.buildIssueBody(ctx.issueNumber(), plan));
        if (createdIssueNumber == null) {
            sessionService.setStatus(ctx.session(), AgentSession.AgentSessionStatus.FAILED);
            repositoryClient.postIssueComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                    "⚠️ **AI Technical Writer**: I drafted the improved issue, but creating it failed. "
                            + "Please check the repository provider response and try again.");
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch()));
        }
        sessionService.setGeneratedIssueNumber(ctx.session(), createdIssueNumber);
        repositoryClient.postIssueComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                "🤖 **AI Technical Writer**: Created improved issue #" + createdIssueNumber
                        + " from this discussion.");
        return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), plan));
    }

    @Override
    public LoopOutcome onBudgetExhausted(AgentRunContext ctx) {
        // Historical writer behaviour: the for-loop simply ends after maxToolRounds+1 iterations
        // without further action when no terminal branch has fired. Mirror that as a no-op success.
        return LoopOutcome.success(ctx.baseBranch(), null);
    }

    private List<ImplementationPlan.ToolRequest> buildContextRequests(WriterPlan plan) {
        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        if (plan.getRequestTools() != null) {
            requests.addAll(plan.getRequestTools());
        }
        if (plan.getRequestFiles() != null) {
            int idx = 1;
            for (String file : plan.getRequestFiles()) {
                requests.add(ImplementationPlan.ToolRequest.builder()
                        .id("writer-file-" + idx)
                        .tool("cat")
                        .args(List.of(file))
                        .build());
                idx++;
            }
        }
        return requests;
    }

    private List<ToolResult> executeTools(AgentRunContext ctx,
                                          List<ImplementationPlan.ToolRequest> requests) {
        List<ToolResult> results = new ArrayList<>();
        for (ImplementationPlan.ToolRequest request : requests) {
            results.add(toolRouter.execute(AgentToolRouter.Mode.WRITER,
                    new ToolCallContext(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                            ctx.workspaceDir(), request)));
        }
        return results;
    }
}

