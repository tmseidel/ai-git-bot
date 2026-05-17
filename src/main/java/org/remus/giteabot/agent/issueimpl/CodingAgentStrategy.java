package org.remus.giteabot.agent.issueimpl;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.AgentStrategy;
import org.remus.giteabot.agent.loop.LoopOutcome;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.shared.BranchSwitcher;
import org.remus.giteabot.agent.shared.McpTools;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * {@link AgentStrategy} for the coding agent. Encapsulates the previously
 * inline {@code runToolImplementationLoop} logic, including:
 * <ul>
 *     <li>Separate sub-budgets for context-fetch rounds vs. tool-execution rounds
 *         (replaces the legacy {@code attempt--} hack).</li>
 *     <li>Validation-policy handling
 *         (including {@code IGNORE_MCP_AFTER_VALIDATION_SUCCESS}).</li>
 *     <li>Workspace-change detection with retry feedback.</li>
 *     <li>Branch-switcher integration during context fetches.</li>
 * </ul>
 *
 * <p>Strategies hold per-run mutable state (counters, current branch) and
 * therefore must be instantiated fresh for every loop run.</p>
 */
@Slf4j
public final class CodingAgentStrategy implements AgentStrategy {


    private final String systemPrompt;
    private final AgentPromptBuilder promptBuilder;
    private final AiResponseParser responseParser;
    private final IssueNotificationService notificationService;
    private final AgentSessionService sessionService;
    private final BranchSwitcher branchSwitcher;
    private final AgentToolRouter toolRouter;
    private final ToolCatalog catalog;
    private final WorkspaceService workspaceService;
    private final AgentConfigProperties agentConfig;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpToolCatalog mcpToolCatalog;
    private final java.util.Set<String> allowedBuiltinTools;
    private final ContextFetcher fetchContext;

    private final int maxToolRounds;
    private final int maxRetries;
    private final int maxContextRounds;
    private int fileRequestRounds = 0;
    private int toolRounds = 0;
    private int attempt = 1;

    /** Functional hook so the strategy stays decoupled from the surrounding service's helpers. */
    @FunctionalInterface
    public interface ContextFetcher {
        String fetch(String owner, String repo, String branch,
                     List<String> files, List<ImplementationPlan.ToolRequest> tools,
                     java.nio.file.Path workspaceDir);
    }


    public CodingAgentStrategy(String systemPrompt,
                               AgentPromptBuilder promptBuilder,
                               AiResponseParser responseParser,
                               IssueNotificationService notificationService,
                               AgentSessionService sessionService,
                               BranchSwitcher branchSwitcher,
                               AgentToolRouter toolRouter,
                               ToolCatalog catalog,
                               WorkspaceService workspaceService,
                               AgentConfigProperties agentConfig,
                               McpOrchestrationService mcpOrchestrationService,
                               McpToolCatalog mcpToolCatalog,
                               java.util.Set<String> allowedBuiltinTools,
                               ContextFetcher contextFetcher) {
        this.systemPrompt = systemPrompt;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.notificationService = notificationService;
        this.sessionService = sessionService;
        this.branchSwitcher = branchSwitcher;
        this.toolRouter = toolRouter;
        this.catalog = catalog;
        this.workspaceService = workspaceService;
        this.agentConfig = agentConfig;
        this.mcpOrchestrationService = mcpOrchestrationService;
        this.mcpToolCatalog = mcpToolCatalog;
        this.allowedBuiltinTools = allowedBuiltinTools;
        this.fetchContext = contextFetcher;
        this.maxRetries =  agentConfig.getBudget().getMaxValidationRetries();
        this.maxToolRounds = agentConfig.getValidation().getMaxToolExecutions();
        this.maxContextRounds = agentConfig.getBudget().getMaxContextRounds();
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    // ---------------------------------------------------------------------
    // Step 6 follow-up: native function-calling opt-in.
    // ---------------------------------------------------------------------

    /** Coding agent advertises NATIVE; the loop falls back to LEGACY when the
     *  client doesn't support native tools (i.e. operator toggled
     *  {@code use_legacy_tool_calling=true}) or the descriptor list is empty. */
    @Override
    public ToolingMode preferredToolMode() {
        return ToolingMode.NATIVE;
    }

    /** Full coding toolbox (file mutations + repository exploration + validation
     *  + MCP), reused as JSON-schema descriptors for the provider's function
     *  calling API. */
    @Override
    public List<ToolDescriptor> toolDescriptors() {
        return catalog.nativeDescriptors(ToolCatalog.Role.CODING, mcpToolCatalog, allowedBuiltinTools);
    }

    /**
     * Native-mode step: translate the model's {@code tool_calls} into the
     * existing {@link ImplementationPlan.ToolRequest} pipeline, reuse the
     * legacy execution / validation / retry logic, and report results back
     * as {@code tool}-role messages.
     */
    @Override
    public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
        if (!turn.hasToolCalls()) {
            // Model returned text only — defer to the legacy parser, which
            // covers the "no tool calls but a final summary" case and the
            // "model emitted a JSON envelope despite NATIVE" fallback.
            return step(ctx, turn.assistantText(), round);
        }
        if (attempt > maxRetries) {
            log.warn("Tool implementation loop exhausted {} attempts without full success", maxRetries);
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch()));
        }
        log.info("Tool implementation loop for issue #{}, attempt {}/{} (native, {} calls)",
                ctx.issueNumber(), attempt, maxRetries, turn.toolCalls().size());

        // 1) Resolve branch-switcher requests up-front (same precedence as legacy).
        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        for (ToolCall call : turn.toolCalls()) {
            requests.add(toRequest(call));
        }

        // Always emit a thinking/plan comment so users see what the agent is about
        // to do — providers often return only tool_calls with empty assistantText
        // in native mode, which would otherwise leave the issue silent. Tool args
        // are truncated by the notification service to keep large/sensitive
        // write-file payloads out of issue comments.
        notificationService.postNativeToolPlanComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                turn.assistantText(), requests);

        BranchSwitcher.Result branchSwitchResult = branchSwitcher.apply(
                ctx.workspaceDir(), ctx.baseBranch(), requests, ctx.issueNumber());
        ctx.setBaseBranch(branchSwitchResult.selectedBranch());
        List<ImplementationPlan.ToolRequest> remaining = branchSwitchResult.remainingToolRequests();

        // 2) Distinguish context-only rounds (cat/rg/find/...) from mutation/validation rounds.
        boolean hasMutationOrValidation = remaining.stream().anyMatch(this::isMutationOrValidation);
        if (!hasMutationOrValidation && fileRequestRounds < maxContextRounds && !remaining.isEmpty()) {
            fileRequestRounds++;
            log.info("AI requested native context tools (round {}/{}, {} call(s))",
                    fileRequestRounds, maxContextRounds, remaining.size());
            List<StepDecision.ToolCallResult> results = executeAndPackage(ctx, remaining, turn.toolCalls());
            return new StepDecision.ContinueWithToolResults(results, null);
        }

        // 3) Tool-execution round: respect the retry cap.
        if (toolRounds >= maxToolRounds) {
            log.warn("Reached max tool rounds ({}) — returning current result", maxToolRounds);
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch()));
        }
        toolRounds++;

        List<ToolResult> rawResults = executeAllTools(ctx.workspaceDir(), remaining);
        boolean hasValidationTools = hasValidationTools(remaining);
        boolean validationPassed = !hasValidationTools || allValidationToolsPassed(remaining, rawResults);

        // 4) Surface non-silent results as issue comments (mirror legacy behaviour).
        for (int i = 0; i < remaining.size(); i++) {
            ImplementationPlan.ToolRequest req = remaining.get(i);
            if (!catalog.isSilent(req.getTool()) && !isMcpTool(req.getTool())) {
                notificationService.postToolResultComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                        req, rawResults.get(i));
            }
        }

        // 5) Always report tool results back to the model via tool-role messages so
        //    Anthropic/OpenAI/Google can correlate call ids; the assistant turn after
        //    a tool round is implicit (no user message).
        List<StepDecision.ToolCallResult> packaged = packageResults(remaining, rawResults, turn.toolCalls());

        // 6) Decide whether to finish, retry, or simply hand back the results for another round.
        if (hasBlockingNonValidationToolFailures(remaining, rawResults, validationPassed)) {
            log.info("Non-validation tools failed on attempt {}; asking AI to correct (native)", attempt);
            attempt++;
            return new StepDecision.ContinueWithToolResults(packaged, null);
        }
        if (!agentConfig.getValidation().isEnabled() || !hasValidationTools || validationPassed) {
            if (hasValidationTools && validationPassed) {
                log.info("All validation tools passed on attempt {} (native)", attempt);
            }
            if (workspaceService.hasUncommittedChanges(ctx.workspaceDir())) {
                // Validation is mandatory: if validation is enabled but the model
                // never called a build/test tool, do NOT finish silently. Hand the
                // tool results back together with an explicit instruction to run
                // the project's build (mvn / gradle / npm / ...). This counts as
                // an attempt so we don't loop forever.
                if (agentConfig.getValidation().isEnabled() && !hasValidationTools) {
                    log.info("Workspace changed but no validation tool was called on attempt {}; "
                                    + "asking AI to run the project build (native)", attempt);
                    attempt++;
                    return new StepDecision.ContinueWithToolResults(packaged,
                            promptBuilder.buildMissingValidationFeedback());
                }
                ImplementationPlan plan = ImplementationPlan.builder()
                        .summary(turn.assistantText() == null || turn.assistantText().isBlank()
                                ? "Implementation produced workspace changes."
                                : turn.assistantText())
                        .toolRequests(remaining)
                        .build();
                sessionService.recordPlan(ctx.session(), plan.getSummary(), turn.assistantText());
                return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), plan));
            }
            log.info("Native tool round produced no Git-detectable workspace changes; continuing");
            attempt++;
            return new StepDecision.ContinueWithToolResults(packaged, null);
        }
        attempt++;
        return new StepDecision.ContinueWithToolResults(packaged, null);
    }

    /** Convert a single native {@link ToolCall} into a positional-args
     *  {@link ImplementationPlan.ToolRequest} compatible with the existing
     *  {@link AgentToolRouter}. */
    private ImplementationPlan.ToolRequest toRequest(ToolCall call) {
        List<String> args = new ArrayList<>();
        JsonNode root = call.args();
        if (root != null && root.isObject()) {
            // MCP tools accept arbitrary provider-defined schemas (any field name).
            // Flattening only known property names would silently drop all of them
            // and the MCP server would reject the call with a parameter-validation
            // error. Pass the full args object as a single JSON-encoded arg so
            // McpOrchestrationService.parseArguments can turn it back into a Map.
            if (McpTools.looksLikeMcpTool(call.name())) {
                args.add(root.toString());
            } else {
                // 1) varargs convention: a top-level "args" array.
                JsonNode varargs = root.get("args");
                if (varargs != null && varargs.isArray()) {
                    varargs.forEach(node -> args.add(asString(node)));
                } else {
                    // 2) Typed schema (write-file/patch-file/mkdir/delete-file/cat/branch-switcher):
                    //    flatten the known property order into positional args. We honour the
                    //    schema ordering documented in ToolCatalog so the existing executors
                    //    keep working unchanged.
                    addIfPresent(root, "path", args);
                    addIfPresent(root, "branch", args);
                    addIfPresent(root, "content", args);
                    addIfPresent(root, "search", args);
                    addIfPresent(root, "replacement", args);
                    addIfPresent(root, "startLine", args);
                    addIfPresent(root, "endLine", args);
                    // 3) Safety net: if the whitelist matched nothing but the args object
                    //    actually carried fields, the model is either using a tool we don't
                    //    recognise or a schema we haven't updated. Fall through to a JSON
                    //    blob so the call still carries data and surface a warning so the
                    //    schema drift gets noticed.
                    if (args.isEmpty() && !root.isEmpty()) {
                        log.warn("Tool '{}' called with unrecognised arg fields {} — "
                                + "passing raw JSON. Update CodingAgentStrategy.toRequest if this tool "
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
            out.add(asString(v));
        }
    }

    private static String asString(JsonNode node) {
        return node.isString() ? node.asString() : node.toString();
    }

    /** Decide whether a tool request mutates the workspace or validates. */
    private boolean isMutationOrValidation(ImplementationPlan.ToolRequest req) {
        return catalog.isFile(req.getTool())
                || catalog.isValidation(req.getTool());
    }

    /** Execute {@code requests} and turn the results into
     *  {@link StepDecision.ToolCallResult} entries keyed by the original
     *  {@link ToolCall#id()} so providers can correlate. */
    private List<StepDecision.ToolCallResult> executeAndPackage(AgentRunContext ctx,
                                                                List<ImplementationPlan.ToolRequest> requests,
                                                                List<ToolCall> originalCalls) {
        List<ToolResult> raw = executeAllTools(ctx.workspaceDir(), requests);
        return packageResults(requests, raw, originalCalls);
    }

    private List<StepDecision.ToolCallResult> packageResults(List<ImplementationPlan.ToolRequest> requests,
                                                             List<ToolResult> rawResults,
                                                             List<ToolCall> originalCalls) {
        List<StepDecision.ToolCallResult> out = new ArrayList<>(rawResults.size());
        // Map request id -> result text, then iterate originalCalls so every call id
        // gets a tool-result message (some providers reject unmatched ids).
        java.util.Map<String, String> byId = new java.util.HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            byId.put(requests.get(i).getId(), rawResults.get(i).formatForAi());
        }
        for (ToolCall call : originalCalls) {
            String text = byId.getOrDefault(call.id(),
                    "[branch-switcher handled inline — no separate result]");
            out.add(new StepDecision.ToolCallResult(call.id(), text));
        }
        return out;
    }

    @Override
    public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
        // Sub-budget exhausted? -> fail.
        if (attempt > maxRetries) {
            log.warn("Tool implementation loop exhausted {} attempts without full success", maxRetries);
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch()));
        }
        log.info("Tool implementation loop for issue #{}, attempt {}/{}",
                ctx.issueNumber(), attempt, maxRetries);

        notificationService.postAiThinkingComment(ctx.owner(), ctx.repo(), ctx.issueNumber(), aiResponse);

        ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);
        if (plan == null) {
            log.warn("Failed to parse AI response on attempt {}", attempt);
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch()));
        }
        // Step 7.1 — persist the latest parsed plan on the session so PR-body
        // and follow-up comment generation no longer need to re-parse the
        // entire conversation history.
        sessionService.recordPlan(ctx.session(), plan.getSummary(), aiResponse);

        // 1) Context-only request (no tool requests yet): fetch and continue without spending an attempt.
        if (plan.hasContextRequests() && !plan.hasToolRequest() && fileRequestRounds < maxContextRounds) {
            fileRequestRounds++;
            log.info("AI requesting additional context (round {}/{})", fileRequestRounds, maxContextRounds);
            BranchSwitcher.Result branchSwitchResult = branchSwitcher.apply(
                    ctx.workspaceDir(), ctx.baseBranch(), plan.getRequestTools(), ctx.issueNumber());
            ctx.setBaseBranch(branchSwitchResult.selectedBranch());
            String fetched = fetchContext.fetch(ctx.owner(), ctx.repo(), ctx.baseBranch(),
                    plan.getRequestFiles(), branchSwitchResult.remainingToolRequests(), ctx.workspaceDir());
            String ctxMsg = "Here is the requested repository context:\n" + fetched
                    + "\n\nNow implement the issue using `runTools`. "
                    + "Use write-file/patch-file for changes and include validation tools.";
            return new StepDecision.Continue(ctxMsg);
        }

        // 2) No tool requests at all -> ask AI to provide them. Counts as an attempt (legacy behaviour).
        if (!plan.hasToolRequest()) {
            log.info("AI provided no runTools on attempt {}", attempt);
            attempt++;
            return new StepDecision.Continue(promptBuilder.buildMissingToolFeedback());
        }

        // 3) Guard against runaway tool rounds.
        if (toolRounds >= maxToolRounds) {
            log.warn("Reached max tool rounds ({}) — returning current result", maxToolRounds);
            return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch()));
        }
        toolRounds++;

        // 4) Execute the requested tools.
        List<ImplementationPlan.ToolRequest> requests = plan.getEffectiveToolRequests();
        List<ToolResult> results = executeAllTools(ctx.workspaceDir(), requests);
        boolean hasValidationTools = hasValidationTools(requests);
        boolean validationPassed = !hasValidationTools || allValidationToolsPassed(requests, results);

        // 5) Surface non-silent tool results as comments.
        for (int i = 0; i < requests.size(); i++) {
            ImplementationPlan.ToolRequest req = requests.get(i);
            if (!catalog.isSilent(req.getTool()) && !isMcpTool(req.getTool())) {
                notificationService.postToolResultComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                        req, results.get(i));
            }
        }

        // 6) Blocking non-validation failures -> retry with feedback.
        if (hasBlockingNonValidationToolFailures(requests, results, validationPassed)) {
            log.info("One or more non-validation tools failed on attempt {}; asking AI to correct", attempt);
            attempt++;
            return new StepDecision.Continue(promptBuilder.buildMultiToolFeedback(requests, results));
        }

        // 7) Validation disabled or no validation tools -> success-after-changes.
        if (!agentConfig.getValidation().isEnabled() || !hasValidationTools) {
            return finalizeOrRetry(ctx, plan, requests, results);
        }

        // 8) Validation passed -> success-after-changes.
        if (validationPassed) {
            log.info("All validation tools passed on attempt {}", attempt);
            return finalizeOrRetry(ctx, plan, requests, results);
        }

        // 9) Validation failed -> feedback, retry.
        attempt++;
        return new StepDecision.Continue(promptBuilder.buildMultiToolFeedback(requests, results));
    }

    @Override
    public LoopOutcome onBudgetExhausted(AgentRunContext ctx) {
        log.warn("AgentLoop budget exhausted for issue #{}", ctx.issueNumber());
        return LoopOutcome.fail(ctx.baseBranch());
    }

    private StepDecision finalizeOrRetry(AgentRunContext ctx,
                                         ImplementationPlan plan,
                                         List<ImplementationPlan.ToolRequest> requests,
                                         List<ToolResult> results) {
        if (workspaceService.hasUncommittedChanges(ctx.workspaceDir())) {
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), plan));
        }
        log.info("Tool execution produced no Git-detectable workspace changes; asking AI to correct");
        attempt++;
        return new StepDecision.Continue(buildNoWorkspaceChangesFeedback(requests, results));
    }

    private String buildNoWorkspaceChangesFeedback(List<ImplementationPlan.ToolRequest> requests,
                                                   List<ToolResult> results) {
        return promptBuilder.buildMultiToolFeedback(requests, results)
                + "\n\nNo file changes are currently present in the git workspace. "
                + "Your previous file tools either failed, made no effective change, or only created empty directories. "
                + "Inspect the files with context tools if needed, then use write-file or patch-file so Git has actual changes to commit.";
    }

    private List<ToolResult> executeAllTools(java.nio.file.Path workspaceDir,
                                             List<ImplementationPlan.ToolRequest> requests) {
        List<ToolResult> results = new ArrayList<>();
        for (ImplementationPlan.ToolRequest req : requests) {
            results.add(toolRouter.execute(AgentToolRouter.Mode.CODING,
                    new ToolCallContext(null, null, null, workspaceDir, req)));
        }
        return results;
    }

    private boolean hasValidationTools(List<ImplementationPlan.ToolRequest> requests) {
        return requests.stream().anyMatch(r -> catalog.isValidation(r.getTool()));
    }

    private boolean allValidationToolsPassed(List<ImplementationPlan.ToolRequest> requests,
                                             List<ToolResult> results) {
        return IntStream.range(0, requests.size())
                .filter(i -> catalog.isValidation(requests.get(i).getTool()))
                .allMatch(i -> results.get(i).success());
    }

    private boolean hasNonValidationToolFailures(List<ImplementationPlan.ToolRequest> requests,
                                                 List<ToolResult> results) {
        return IntStream.range(0, requests.size())
                .filter(i -> !catalog.isValidation(requests.get(i).getTool()))
                .anyMatch(i -> !results.get(i).success());
    }

    private boolean hasBlockingNonValidationToolFailures(List<ImplementationPlan.ToolRequest> requests,
                                                         List<ToolResult> results,
                                                         boolean validationPassed) {
        if (!hasNonValidationToolFailures(requests, results)) {
            return false;
        }
        AgentConfigProperties.ValidationConfig.NonValidationFailurePolicy policy =
                agentConfig.getValidation().getNonValidationFailurePolicy();
        if (policy == AgentConfigProperties.ValidationConfig.NonValidationFailurePolicy.IGNORE_MCP_AFTER_VALIDATION_SUCCESS
                && validationPassed
                && hasOnlyMcpNonValidationFailures(requests, results)) {
            log.info("Ignoring MCP non-validation tool failures because validation passed");
            return false;
        }
        return true;
    }

    private boolean hasOnlyMcpNonValidationFailures(List<ImplementationPlan.ToolRequest> requests,
                                                    List<ToolResult> results) {
        return IntStream.range(0, requests.size())
                .filter(i -> !catalog.isValidation(requests.get(i).getTool()))
                .filter(i -> !results.get(i).success())
                .allMatch(i -> isMcpTool(requests.get(i).getTool()));
    }

    private boolean isMcpTool(String toolName) {
        return McpTools.isMcpTool(mcpOrchestrationService, mcpToolCatalog, toolName);
    }
}







