package org.remus.giteabot.agent.writerimpl;

import lombok.RequiredArgsConstructor;
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
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolArguments;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AgentStrategy} for the technical-writer agent.
 *
 * <p>The strategy is created fresh per loop run and tracks its own context-round
 * sub-budget. The {@code maxToolRounds} parameter mirrors the previous
 * {@code WriterConfig.maxToolRounds} cap.</p>
 *
 * <p>Round {@code maxToolRounds} is the wrap-up round: repository-context calls
 * are no longer executed, the model is told the budget is spent, and it gets one
 * final round to answer from what it has already read. That round keeps its tool
 * descriptors on purpose: taking them out of the request makes every client fall
 * back to its plain-text message shape, which cannot carry the tool exchanges the
 * round replays — a turn whose only content was its calls becomes an empty message,
 * which Anthropic and Gemini reject. A model that keeps calling tools therefore
 * ends with the "need more context" comment, from either the give-up branch or
 * {@link #onBudgetExhausted}, exactly like a model that never answers.</p>
 */
@Slf4j
@RequiredArgsConstructor
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

    /**
     * One-shot guard so a NATIVE model that narrates in prose (no JSON answer)
     * is nudged for its structured answer exactly once before we fall back to
     * the legacy clarifying-questions path. Prevents a nudge loop ending in
     * {@link #onBudgetExhausted} (a silent no-op).
     */
    private boolean nudgedForJsonAnswer;

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
            // In NATIVE mode the model must return its structured JSON answer once
            // it stops calling tools. A plain-language turn with no JSON is
            // narration, not a final answer — nudge once for the contract JSON
            // rather than prematurely concluding with prose-as-clarifying-questions.
            // If it still replies in prose, fall through to the legacy path so the
            // user still gets feedback (instead of looping into the no-op
            // onBudgetExhausted).
            if (ctx.toolingMode() == ToolingMode.NATIVE
                    && !nudgedForJsonAnswer
                    && !responseParser.hasJsonPayload(turn.assistantText())) {
                nudgedForJsonAnswer = true;
                return new StepDecision.Continue(
                        "Please provide your final answer as a single JSON object exactly as "
                        + "instructed (with `qualityAssessment`, `readyToCreate`, and either "
                        + "`clarifyingQuestions` or `revisedIssueDraft`) — not as prose.");
            }
            return step(ctx, turn.assistantText(), round);
        }
        int writerRound = round - 1;
        if (writerRound == maxToolRounds) {
            // Wrap-up round: the context budget is spent, so nothing is executed. Every
            // call still gets a synthetic result (a provider rejects a request whose
            // call ids are never answered) and the follow slot carries the wrap-up
            // instruction, leaving the next round to produce the final answer.
            List<StepDecision.ToolCallResult> skippedResults = new ArrayList<>(turn.toolCalls().size());
            for (ToolCall call : turn.toolCalls()) {
                skippedResults.add(new StepDecision.ToolCallResult(call.id(),
                        "not executed — the writer's repository-context budget is exhausted for this run"));
            }
            return new StepDecision.ContinueWithToolResults(skippedResults,
                    promptBuilder.buildWrapUpInstruction());
        }
        if (writerRound > maxToolRounds) {
            return new StepDecision.Finish(giveUp(ctx));
        }

        List<ImplementationPlan.ToolRequest> requests = new ArrayList<>();
        for (ToolCall call : turn.toolCalls()) {
            requests.add(toRequest(call));
        }
        BranchSwitcher.Result branchSwitch = branchSwitcher.apply(
                ctx.workspace(), ctx.baseBranch(), requests, ctx.issueNumber());
        if (branchSwitch.selectedBranch() != null
                && !branchSwitch.selectedBranch().equals(ctx.baseBranch())) {
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

    /**
     * The JSON-to-positional mapping lives in {@link ToolArguments} and is shared with every other
     * agent: a local copy is a second thing to keep in step with the tool schemas, and it falls
     * behind silently.
     */
    private ImplementationPlan.ToolRequest toRequest(ToolCall call) {
        return ImplementationPlan.ToolRequest.builder()
                .id(call.id() == null || call.id().isBlank() ? java.util.UUID.randomUUID().toString() : call.id())
                .tool(call.name())
                .args(ToolArguments.toPositional(call.name(), call.args(),
                        catalog.schemaOf(call.name()).orElse(null)))
                .build();
    }

    @Override
    public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
        // Writer round counter is 0-based historically: round 1 of the loop == round 0 of the writer.
        int writerRound = round - 1;
        WriterPlan plan = responseParser.parse(aiResponse);

        if (plan.hasContextRequests() && writerRound == maxToolRounds) {
            // Wrap-up round: the JSON envelope carries no call ids to answer, so the
            // instruction alone is the follow-up and the next round must answer.
            return new StepDecision.Continue(promptBuilder.buildWrapUpInstruction());
        }

        if (plan.hasContextRequests() && writerRound > maxToolRounds) {
            sessionService.setStatus(ctx.session(), AgentSession.AgentSessionStatus.IN_PROGRESS);
            repositoryClient.postIssueComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                    "⚠️ **AI Technical Writer**: I need more context before I can continue. "
                            + "Please add more details and mention me again.");
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), plan));
        }

        if (plan.hasContextRequests() && writerRound < maxToolRounds) {
            List<ImplementationPlan.ToolRequest> contextRequests = buildContextRequests(plan);
            BranchSwitcher.Result branchSwitch = branchSwitcher.apply(
                    ctx.workspace(), ctx.baseBranch(), contextRequests, ctx.issueNumber());
            if (branchSwitch.selectedBranch() != null
                    && !branchSwitch.selectedBranch().equals(ctx.baseBranch())) {
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
        // Reaching the cap without a terminal branch used to end silently, which left the
        // user with a run that produced nothing and said nothing. Since the round after
        // the wrap-up has no tool descriptors, an exhausted run means the model narrated
        // twice instead of answering (or called tools it no longer has) — the same dead
        // end as the give-up branch, so it gets the same comment.
        return giveUp(ctx);
    }

    /**
     * Ends the run with the historic "needs more context" comment, leaving the session
     * resumable: the user adds the missing details and mentions the bot again.
     */
    private LoopOutcome giveUp(AgentRunContext ctx) {
        sessionService.setStatus(ctx.session(), AgentSession.AgentSessionStatus.IN_PROGRESS);
        repositoryClient.postIssueComment(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                "⚠️ **AI Technical Writer**: I need more context before I can continue. "
                        + "Please add more details and mention me again.");
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
                            ctx.workspace(), request)));
        }
        return results;
    }
}

