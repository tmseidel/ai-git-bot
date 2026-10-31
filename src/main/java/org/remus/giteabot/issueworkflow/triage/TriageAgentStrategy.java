package org.remus.giteabot.issueworkflow.triage;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.AgentStrategy;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.ToolFailures;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolArguments;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AgentStrategy} for the issue-triage agent: the model may gather
 * repository and issue context with the read-only tool surface
 * ({@link ToolCatalog.Role#WRITER} descriptors executed through
 * {@link AgentToolRouter.Mode#WRITER}, which never reaches a file-mutation,
 * validation/build or git-write tool) and finishes by emitting exactly one
 * routing decision.
 *
 * <p>Two transports share one validation path
 * ({@link RoutingDecision#validate}):</p>
 * <ul>
 *   <li><b>Native mode</b> — context tools are advertised next to the
 *   terminal {@code assign_issue} tool (its {@code name} parameter is an
 *   enum of the allowed assignees). A turn whose only call is a valid
 *   {@code assign_issue} finishes the loop; an invalid one is fed back as
 *   the tool result so the model can correct itself. A text-only turn is
 *   tolerated once as a legacy-style JSON answer, then nudged.</li>
 *   <li><b>Legacy mode</b> — context is requested via the standard
 *   {@code requestFiles}/{@code requestTools} JSON envelope (parsed by
 *   {@link AiResponseParser}); the terminal answer is a single
 *   {@code {"assignment", "reason"}} object.</li>
 * </ul>
 *
 * <p>Repeated invalid terminal answers (unknown assignee, blank/multi-line
 * reason, self-assignment, malformed JSON) are bounded by
 * {@link #MAX_INVALID_ATTEMPTS}; the loop then finishes with a failure
 * outcome so the service can post the error comment and raise
 * {@link TriageRoutingException}.</p>
 */
@Slf4j
public final class TriageAgentStrategy implements AgentStrategy {

    static final String TOOL_NAME = "assign_issue";

    /** How often the model may deliver an unusable terminal answer before the run fails. */
    private static final int MAX_INVALID_ATTEMPTS = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String systemPrompt;
    private final AgentToolRouter toolRouter;
    private final ToolCatalog catalog;
    private final McpToolCatalog mcpToolCatalog;
    private final Set<String> allowedBuiltinTools;
    private final Set<String> allowedAssignees;
    private final String botUsername;
    private final AiResponseParser responseParser;
    private final BranchSwitcher branchSwitcher;
    private final int maxContextRounds;

    /** Read-only context-fetch rounds consumed in legacy mode. */
    private int contextRounds;
    /** Invalid terminal answers seen so far (both modes). */
    private int invalidAttempts;

    public TriageAgentStrategy(String systemPrompt,
                               AgentToolRouter toolRouter,
                               ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog,
                               Set<String> allowedBuiltinTools,
                               Set<String> allowedAssignees,
                               String botUsername,
                               AiResponseParser responseParser,
                               BranchSwitcher branchSwitcher,
                               int maxContextRounds) {
        this.systemPrompt = systemPrompt;
        this.toolRouter = toolRouter;
        this.catalog = catalog;
        this.mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
        this.allowedBuiltinTools = allowedBuiltinTools;
        this.allowedAssignees = allowedAssignees;
        this.botUsername = botUsername;
        this.responseParser = responseParser;
        this.branchSwitcher = branchSwitcher;
        this.maxContextRounds = Math.max(1, maxContextRounds);
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    @Override
    public ToolingMode preferredToolMode() {
        return ToolingMode.NATIVE;
    }

    /** Read-only WRITER toolbox plus the terminal {@code assign_issue} tool. */
    @Override
    public List<ToolDescriptor> toolDescriptors() {
        List<ToolDescriptor> descriptors = new ArrayList<>(
                catalog.nativeDescriptors(ToolCatalog.Role.WRITER, mcpToolCatalog, allowedBuiltinTools));
        descriptors.add(assignIssueDescriptor());
        return List.copyOf(descriptors);
    }

    // ---------------------------------------------------------------------
    // Native mode
    // ---------------------------------------------------------------------

    @Override
    public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
        if (!turn.hasToolCalls()) {
            // Legacy transport: the structured intent lives in the text — defer
            // to the legacy JSON-parsing path (mirrors WriterAgentStrategy).
            if (ctx.toolingMode() != ToolingMode.NATIVE) {
                return step(ctx, turn.assistantText(), round);
            }
            // A text-only turn in native mode: tolerate a legacy-style JSON
            // answer, otherwise nudge the model towards the tool call.
            JsonNode terminal = extractAssignmentJson(turn.assistantText());
            if (terminal != null) {
                return finishFromJson(ctx, terminal);
            }
            return invalidAttempt(ctx, null,
                    "no routing decision yet — call the " + TOOL_NAME + " tool with your final answer");
        }

        List<ToolCall> assignCalls = turn.toolCalls().stream()
                .filter(c -> TOOL_NAME.equals(c.name()))
                .toList();

        if (assignCalls.size() == 1 && turn.toolCalls().size() == 1) {
            ToolCall call = assignCalls.getFirst();
            try {
                RoutingDecision decision = RoutingDecision.validate(
                        stringArg(call.args(), "name"), stringArg(call.args(), "reason"),
                        allowedAssignees, botUsername);
                return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), decision));
            } catch (RoutingDecision.InvalidOutput e) {
                return invalidAttempt(ctx, turn.toolCalls(),
                        TOOL_NAME + " rejected: " + e.getMessage()
                                + ". Correct the arguments and call " + TOOL_NAME + " again.");
            }
        }

        if (!assignCalls.isEmpty()) {
            return invalidAttempt(ctx, turn.toolCalls(),
                    TOOL_NAME + " must be the only tool call in its turn — gather context first,"
                            + " then call " + TOOL_NAME + " exactly once in a later turn.");
        }

        // Pure context-gathering turn: execute every read-only tool.
        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        for (ToolCall call : turn.toolCalls()) {
            requests.add(toRequest(call));
        }
        List<ToolResult> results = executeAll(ctx, requests);
        return new StepDecision.ContinueWithToolResults(
                packageResults(requests, results, turn.toolCalls()), null);
    }

    // ---------------------------------------------------------------------
    // Legacy mode
    // ---------------------------------------------------------------------

    @Override
    public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
        JsonNode terminal = extractAssignmentJson(aiResponse);
        if (terminal != null) {
            return finishFromJson(ctx, terminal);
        }

        ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);
        List<ImplementationPlan.ToolRequest> contextRequests = collectContextRequests(plan);
        if (!contextRequests.isEmpty() && contextRounds < maxContextRounds) {
            contextRounds++;
            log.debug("Triage agent (legacy) gathering context for issue #{} (round {}/{})",
                    ctx.issueNumber(), contextRounds, maxContextRounds);
            BranchSwitcher.Result branchSwitch = branchSwitcher.apply(
                    ctx.workspace(), ctx.baseBranch(), contextRequests, ctx.issueNumber());
            if (branchSwitch.selectedBranch() != null
                    && !branchSwitch.selectedBranch().equals(ctx.baseBranch())) {
                ctx.setBaseBranch(branchSwitch.selectedBranch());
            }
            List<ToolResult> results = executeAll(ctx, branchSwitch.remainingToolRequests());
            return new StepDecision.Continue(
                    buildToolFeedback(branchSwitch.remainingToolRequests(), results));
        }

        return invalidAttempt(ctx, null,
                "no routing decision yet — respond with ONLY the JSON object"
                        + " {\"assignment\": \"<user>\", \"reason\": \"<one-line justification>\"}");
    }

    @Override
    public LoopOutcome onBudgetExhausted(AgentRunContext ctx) {
        log.warn("Triage agent loop exhausted its round budget for issue #{} without a routing decision",
                ctx.issueNumber());
        return LoopOutcome.fail(ctx.baseBranch(),
                "the triage agent did not reach a routing decision within its round budget");
    }

    // ---------------------------------------------------------------------
    // Shared terminal-answer handling
    // ---------------------------------------------------------------------

    private StepDecision finishFromJson(AgentRunContext ctx, JsonNode terminal) {
        try {
            RoutingDecision decision = RoutingDecision.validate(
                    stringArg(terminal, "assignment"), stringArg(terminal, "reason"),
                    allowedAssignees, botUsername);
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), decision));
        } catch (RoutingDecision.InvalidOutput e) {
            return invalidAttempt(ctx, null, "invalid routing decision: " + e.getMessage());
        }
    }

    /**
     * Counts one unusable terminal answer and either lets the model retry
     * (native: the message is fed back as the tool result for every call of
     * the turn; legacy/no-call: as the next user message) or fails the run
     * once {@link #MAX_INVALID_ATTEMPTS} is reached.
     */
    private StepDecision invalidAttempt(AgentRunContext ctx, List<ToolCall> callsOfTurn, String message) {
        invalidAttempts++;
        if (invalidAttempts >= MAX_INVALID_ATTEMPTS) {
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(), message));
        }
        if (callsOfTurn != null && !callsOfTurn.isEmpty()) {
            List<StepDecision.ToolCallResult> packaged = new ArrayList<>(callsOfTurn.size());
            for (ToolCall call : callsOfTurn) {
                packaged.add(new StepDecision.ToolCallResult(call.id(), message));
            }
            return new StepDecision.ContinueWithToolResults(packaged, null);
        }
        return new StepDecision.Continue(message);
    }

    // ---------------------------------------------------------------------
    // Tool execution
    // ---------------------------------------------------------------------

    private List<ToolResult> executeAll(AgentRunContext ctx, List<ImplementationPlan.ToolRequest> requests) {
        List<ToolResult> results = new ArrayList<>(requests.size());
        for (ImplementationPlan.ToolRequest request : requests) {
            results.add(toolRouter.execute(AgentToolRouter.Mode.WRITER,
                    new ToolCallContext(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                            ctx.workspace(), request)));
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

    /** Legacy protocol: context tools plus explicit file requests (mapped to {@code cat}). */
    private List<ImplementationPlan.ToolRequest> collectContextRequests(ImplementationPlan plan) {
        if (plan == null) {
            return List.of();
        }
        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        if (plan.getRequestTools() != null) {
            requests.addAll(plan.getRequestTools());
        }
        if (plan.hasToolRequest()) {
            requests.addAll(plan.getEffectiveToolRequests());
        }
        if (plan.getRequestFiles() != null) {
            int idx = 1;
            for (String file : plan.getRequestFiles()) {
                requests.add(ImplementationPlan.ToolRequest.builder()
                        .id("triage-file-" + idx)
                        .tool("cat")
                        .args(List.of(file))
                        .build());
                idx++;
            }
        }
        return requests;
    }

    private String buildToolFeedback(List<ImplementationPlan.ToolRequest> requests,
                                     List<ToolResult> results) {
        StringBuilder sb = new StringBuilder("## Triage tool results\n\n");
        for (int i = 0; i < requests.size(); i++) {
            ImplementationPlan.ToolRequest request = requests.get(i);
            ToolResult result = results.get(i);
            sb.append("### Result for `").append(request.getId()).append("`: `")
                    .append(request.getTool()).append("`\n\n");
            if (result.success()) {
                sb.append(result.output() == null || result.output().isBlank()
                        ? "(no output)" : result.output());
            } else {
                sb.append("Failed: ").append(ToolFailures.describe(result));
            }
            sb.append("\n\n");
        }
        sb.append("Use these results to continue. When you have gathered enough context, respond with"
                + " ONLY the final JSON object {\"assignment\": \"<user>\","
                + " \"reason\": \"<one-line justification>\"}.");
        return sb.toString();
    }

    /** See {@link ToolArguments}: the mapping is shared, never copied per agent. */
    private ImplementationPlan.ToolRequest toRequest(ToolCall call) {
        return ImplementationPlan.ToolRequest.builder()
                .id(call.id() == null || call.id().isBlank() ? UUID.randomUUID().toString() : call.id())
                .tool(call.name())
                .args(ToolArguments.toPositional(call.name(), call.args(),
                        catalog.schemaOf(call.name()).orElse(null)))
                .build();
    }

    // ---------------------------------------------------------------------
    // Descriptor / parsing helpers
    // ---------------------------------------------------------------------

    private ToolDescriptor assignIssueDescriptor() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode name = props.putObject("name");
        name.put("type", "string");
        name.put("description", "The assignment. Must be one of the known users.");
        allowedAssignees.forEach(name.putArray("enum")::add);
        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put("description", "A one-line justification for the assignment.");
        schema.putArray("required").add("name").add("reason");
        return new ToolDescriptor(TOOL_NAME,
                "Assign the issue to one of the known users with a one-line justification."
                        + " Call this exactly once, in its own turn, when you have gathered enough context.",
                schema);
    }

    /**
     * Extracts the first JSON object that carries an {@code assignment} key,
     * tolerating prose around it (consistent with
     * {@link AiResponseParser}/{@code WriterResponseParser} practice).
     * Returns {@code null} when the text holds no terminal answer.
     */
    static JsonNode extractAssignmentJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() && node.has("assignment") ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringArg(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
