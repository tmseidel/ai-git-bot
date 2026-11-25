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
import org.remus.giteabot.agent.shared.ToolFailures;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCallContext;
import org.remus.giteabot.agent.tools.ToolArguments;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.tools.ToolKind;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.review.structured.DraftSnapshot;
import org.remus.giteabot.review.structured.ReviewInvocation;
import org.remus.giteabot.review.structured.ReviewLookupInvocation;
import org.remus.giteabot.review.structured.ReviewLookupToolExecutor;
import org.remus.giteabot.review.structured.ReviewSubmissionToolExecutor;
import org.remus.giteabot.review.structured.ReviewToolCall;
import org.remus.giteabot.review.structured.StructuredReviewService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only {@link AgentStrategy} powering the agentic PR-review workflow.
 *
 * <p>Two modes share the read-only exploration surface:</p>
 * <ul>
 *     <li><strong>Structured review</strong> ({@code submission != null}) — the run is published
 *     from the review draft, never from prose. Findings and the assessment are submitted through
 *     the {@link ToolKind#REVIEW_OUTPUT} protocol and {@code review-complete} freezes the draft; a
 *     text-only reply is answered with a bounded reminder and can never become the review.</li>
 *     <li><strong>Clarification</strong> ({@code submission == null}) — a plain answer to a
 *     question about a reviewed PR: no submission tools, and text finishes the run.</li>
 * </ul>
 *
 * <p>Exploration stays read-only and bounded by {@code maxToolRounds}. Submission rounds are
 * counted apart from exploration rounds, so exhausting exploration cannot take away the capacity
 * to submit and complete. Neither counter can exceed the loop's hard round budget.</p>
 */
@Slf4j
public final class ReviewAgentStrategy implements AgentStrategy {

    /** Number of rounds before the limit at which the closing reminder is sent. */
    private static final int CLOSING_REMINDER_OFFSET = 2;

    /** How often a text-only reply is answered with the submission reminder before giving up. */
    static final int MAX_COMPLETION_REMINDERS = 2;

    static final String SUBMISSION_REMINDER = """
            Your reply was not stored, and text is never published as the review. This review is
            built from the review tools:
              1. submit every finding with `review-upsert-finding`,
              2. set the overall assessment with `review-set-assessment`,
              3. call `review-complete` on its own.
            Do not describe the review in text. Call the tools until the draft is complete.""";

    static final String SUBMISSION_CLOSING_REMINDER = """
            The review is approaching its exploration limit. Stop exploring and finish the
            submission: submit any remaining findings, set the assessment with
            `review-set-assessment`, then call `review-complete` on its own. A submission round is
            always allowed, even after exploration is exhausted.""";

    static final String EXPLORATION_EXHAUSTED_MESSAGE =
            "Exploration budget exhausted: repository reads are no longer executed. Submit your "
                    + "findings and the assessment, then call `review-complete` on its own.";

    static final String COMPLETION_ALONE_MESSAGE =
            "review-complete must be the only call in its batch. Nothing in this batch was applied. "
                    + "Submit findings and the assessment in earlier batches, then complete alone.";

    /** Sent when the mutation budget is spent: the draft survives and only finalization is left. */
    static final String SUBMISSION_BUDGET_MESSAGE =
            "Submission-round limit reached: further findings, corrections and assessment changes "
                    + "are no longer executed. Everything already submitted is kept. Call "
                    + "`review-complete` on its own to publish the review.";

    /** Fetches repository file contents for the legacy {@code requestFiles} protocol. */
    @FunctionalInterface
    public interface FileFetcher {
        String fetch(String owner, String repo, String ref, List<String> files);
    }

    /**
     * Collaborators of a structured review run: the submission executor and the trusted
     * invocation its tools write through, plus the draft service used to describe progress in a
     * reminder. Absent for a clarification.
     */
    public record Submission(ReviewSubmissionToolExecutor executor, ReviewInvocation invocation,
                             StructuredReviewService reviewService) {
    }

    /**
     * Read-only access to the reviews this bot already published on the pull request. Present for
     * reviews and follow-ups alike: a follow-up has no history of what was published, and a rerun may
     * want to know what it said before.
     */
    public record Lookup(ReviewLookupToolExecutor executor, ReviewLookupInvocation invocation) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

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
    private final int maxSubmissionRounds;
    private int toolRounds;

    /** Read-only context-fetch rounds consumed in legacy mode. */
    private int contextRounds = 0;

    /** Submission rounds are counted apart from exploration rounds. */
    private int submissionRounds;
    private int completionReminders;

    private final Submission submission;
    private final Lookup lookup;

    /** Learned guidance this run received, or {@code null}; rechecked at every round boundary. */
    private final LearnedGuidance guidance;

    public ReviewAgentStrategy(String systemPrompt,
                               AgentToolRouter toolRouter,
                               ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog,
                               Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser,
                               BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher,
                               int maxContextRounds,
                               int maxToolRounds,
                               int maxSubmissionRounds,
                               Lookup lookup,
                               Submission submission) {
        this(systemPrompt, toolRouter, catalog, mcpToolCatalog, allowedBuiltinTools, responseParser,
                branchSwitcher, fileFetcher, maxContextRounds, maxToolRounds, maxSubmissionRounds,
                lookup, submission, null);
    }

    /**
     * @param guidance learned guidance delivered in the initial user message, or {@code null}. It is
     *                 pinned against compaction and rechecked before each round's tools run; once
     *                 revoked the run ends without executing anything further.
     */
    public ReviewAgentStrategy(String systemPrompt,
                               AgentToolRouter toolRouter,
                               ToolCatalog catalog,
                               McpToolCatalog mcpToolCatalog,
                               Set<String> allowedBuiltinTools,
                               AiResponseParser responseParser,
                               BranchSwitcher branchSwitcher,
                               FileFetcher fileFetcher,
                               int maxContextRounds,
                               int maxToolRounds,
                               int maxSubmissionRounds,
                               Lookup lookup,
                               Submission submission,
                               LearnedGuidance guidance) {
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
        this.maxSubmissionRounds = Math.max(1, maxSubmissionRounds);
        this.submission = submission;
        this.lookup = lookup;
        this.guidance = guidance;
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    /** The learned guidance block, kept in the conversation even when compaction drops the kickoff. */
    @Override
    public String pinnedContext() {
        return guidance == null ? null : guidance.text();
    }

    /** {@code true} when this run publishes a stored review rather than an answer. */
    private boolean structured() {
        return submission != null;
    }

    @Override
    public String roundBudgetReminder(int remainingRounds, ToolingMode mode) {
        int modeRounds = mode == ToolingMode.NATIVE
                ? maxToolRounds - toolRounds : maxContextRounds - contextRounds;
        if (structured()) {
            int roundsLeft = Math.min(remainingRounds, Math.min(modeRounds,
                    maxSubmissionRounds - submissionRounds));
            return roundsLeft <= CLOSING_REMINDER_OFFSET + 1 ? SUBMISSION_CLOSING_REMINDER : null;
        }
        return Math.min(remainingRounds, modeRounds) <= CLOSING_REMINDER_OFFSET + 1 ? """
                The review is approaching its exploration or round limit. \
                Conclude your review now using \
                the evidence already gathered. Avoid further tool calls and additional exploration. \
                Return your final response in the required output format, including any required \
                severity classification. Clearly state unverified areas or limitations; do not \
                claim they were checked.
                """ : null;
    }

    @Override
    public ToolingMode preferredToolMode() {
        return ToolingMode.NATIVE;
    }

    /**
     * Read-only exploration surface plus the selected MCP catalog — and, for a structured review,
     * the submission protocol, which is always available whatever the operator selected for
     * exploration.
     */
    @Override
    public List<ToolDescriptor> toolDescriptors() {
        List<ToolDescriptor> exploration =
                catalog.nativeDescriptors(ToolCatalog.Role.REVIEW, mcpToolCatalog, allowedBuiltinTools);
        List<ToolDescriptor> combined = new ArrayList<>(exploration);
        if (lookup != null) {
            combined.addAll(catalog.reviewContextDescriptors());
        }
        if (structured()) {
            combined.addAll(catalog.reviewOutputDescriptors());
        }
        return List.copyOf(combined);
    }

    @Override
    public StepDecision step(AgentRunContext ctx, ChatTurn turn, int round) {
        if (guidanceRevoked()) {
            return cancelForRevokedGuidance(ctx, turn.toolCalls());
        }
        StopReason expected = turn.hasToolCalls() ? StopReason.TOOL_USE : StopReason.END_TURN;
        if (turn.stopReason() != expected) {
            return incompleteTurn(ctx, turn.stopReason());
        }
        if (!turn.hasToolCalls()) {
            return onTextOnlyTurn(ctx, turn.assistantText());
        }
        return executeRound(ctx, turn, round);
    }

    @Override
    public StepDecision stepLegacy(AgentRunContext ctx, ChatTurn turn, int round) {
        if (guidanceRevoked()) {
            return cancelForRevokedGuidance(ctx, List.of());
        }
        // Reject any turn carrying tool calls, even with END_TURN: the legacy
        // JSON protocol has no native-call envelope, so such a mix can only be
        // provider metadata leaking into a text turn — never accept its text.
        if (turn.stopReason() != StopReason.END_TURN || turn.hasToolCalls()) {
            return incompleteTurn(ctx, turn.stopReason());
        }
        return step(ctx, turn.assistantText(), round);
    }

    // ------------------------------------------------------------------ learned guidance

    /** Round boundary: whether guidance this run already received was revoked since. */
    private boolean guidanceRevoked() {
        return guidance != null && guidance.recheck().isPresent();
    }

    /**
     * Ends the run without executing this round's calls. The model has already read guidance that is
     * no longer permitted and the earlier messages cannot be erased, so nothing it produced may be
     * published; the caller turns this into a cancelled run.
     */
    private StepDecision cancelForRevokedGuidance(AgentRunContext ctx, List<ToolCall> calls) {
        String reason = guidance.revokedReason().orElse("its learned guidance was revoked");
        log.warn("Agentic review run for PR #{} stops before publication: {}", ctx.issueNumber(), reason);
        List<StepDecision.ToolCallResult> notExecuted = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            notExecuted.add(new StepDecision.ToolCallResult(call.id(),
                    "Not executed: the run was cancelled because " + reason + "."));
        }
        return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                "⚠️ *The run was cancelled because learned guidance it used was revoked: " + reason
                        + ".*"), notExecuted);
    }

    // ------------------------------------------------------------------ native transport

    /**
     * Executes one native round in the model's order. Submission calls go to the submission
     * executor as typed JSON, everything else to the read-only router. A batch containing
     * {@code review-complete} next to other calls is refused without executing anything, so a
     * completion can never run before the receipts of its own batch were observed.
     */
    private StepDecision executeRound(AgentRunContext ctx, ChatTurn turn, int round) {
        List<ToolCall> calls = turn.toolCalls();
        if (structured() && calls.size() > 1 && calls.stream().anyMatch(call -> isCompletion(call.name()))) {
            List<StepDecision.ToolCallResult> refused = new ArrayList<>(calls.size());
            for (ToolCall call : calls) {
                refused.add(new StepDecision.ToolCallResult(call.id(),
                        "Not executed: " + COMPLETION_ALONE_MESSAGE));
            }
            return new StepDecision.ContinueWithToolResults(refused, null);
        }

        // A lookup reads stored records, not the workspace: it must not spend the exploration budget
        // (nor end an unstructured follow-up when that budget is gone).
        boolean hasExploration = calls.stream()
                .anyMatch(call -> !isSubmission(call.name()) && !isLookup(call.name()));
        boolean hasSubmission = calls.stream().anyMatch(call -> isSubmission(call.name()));
        boolean explorationExhausted = hasExploration && toolRounds >= maxToolRounds;

        if (hasExploration && !explorationExhausted) {
            toolRounds++;
        }
        if (hasSubmission && !finalizesOnly(calls.stream().map(ToolCall::name).toList())) {
            submissionRounds++;
            if (structured() && submissionRounds > maxSubmissionRounds) {
                log.warn("Agentic review for PR #{} exhausted its {}-round submission budget; "
                        + "refusing further mutations and asking for completion",
                        ctx.issueNumber(), maxSubmissionRounds);
                return refuseOverBudget(calls);
            }
        }
        if (explorationExhausted && !structured()) {
            return new StepDecision.Finish(onBudgetExhausted(ctx));
        }
        List<StepDecision.ToolCallResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            if (isSubmission(call.name())) {
                results.add(executeSubmissionCall(call));
            } else if (isLookup(call.name())) {
                results.add(executeLookupCall(call));
            } else if (explorationExhausted) {
                results.add(new StepDecision.ToolCallResult(call.id(), EXPLORATION_EXHAUSTED_MESSAGE));
            } else {
                results.add(executeExplorationCall(ctx, call));
            }
        }
        if (ctx.progressConsumer() != null && !calls.isEmpty()) {
            // What actually happened this round, not a generic "completed": submissions used to
            // report nothing at all, so the progress comment stayed on "Waiting for model".
            ctx.progressConsumer().accept(new AgentRunContext.ProgressUpdate(
                    round, describeActivity(calls.stream().map(ToolCall::name).toList()),
                    results.size()));
        }
        return finishOrContinue(ctx, results);
    }

    /**
     * One line describing what this round did, in the terms an operator reads: findings recorded,
     * assessment set, review completed, files inspected. Tool names are mapped rather than echoed so
     * the progress comment does not read like the wire protocol.
     */
    String describeActivity(List<String> toolNames) {
        List<String> exploration = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (String tool : toolNames) {
            if (isLookup(tool)) {
                actions.add("looked up the published review");
            } else if (ReviewSubmissionToolExecutor.TOOL_NAMES.contains(normalize(tool))) {
                actions.add(describeSubmission(tool));
            } else {
                exploration.add(tool);
            }
        }
        if (!exploration.isEmpty()) {
            actions.addFirst("inspected repository: " + String.join(", ", exploration));
        }
        return String.join(" · ", actions);
    }

    private static String describeSubmission(String tool) {
        return switch (normalize(tool)) {
            case ReviewSubmissionToolExecutor.UPSERT_FINDING -> "recorded a finding";
            case ReviewSubmissionToolExecutor.REMOVE_FINDING -> "withdrew a finding";
            case ReviewSubmissionToolExecutor.SET_ASSESSMENT -> "set the assessment";
            case ReviewSubmissionToolExecutor.COMPLETE -> "completed the review";
            case ReviewSubmissionToolExecutor.GET_DRAFT -> "read the draft";
            default -> "submitted review content";
        };
    }

    private StepDecision finishOrContinue(AgentRunContext ctx, List<StepDecision.ToolCallResult> results) {
        String reference = finalizedReference(results);
        if (reference != null) {
            log.info("Agentic review for PR #{} completed as {}", ctx.issueNumber(), reference);
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), reference), results);
        }
        return new StepDecision.ContinueWithToolResults(results, null);
    }

    /** The review reference, when this round's successful completion call finalized the draft. */
    private String finalizedReference(List<StepDecision.ToolCallResult> results) {
        for (StepDecision.ToolCallResult result : results) {
            String text = result.resultText();
            if (text == null || !text.contains("\"finalized\"")) {
                continue;
            }
            String reference = referenceFromReceipt(text);
            if (reference != null) {
                return reference;
            }
        }
        return null;
    }

    /** Reads {@code reference} out of a completion receipt embedded in a formatted tool result. */
    static String referenceFromReceipt(String receipt) {
        int start = receipt.indexOf('{');
        if (start < 0) {
            return null;
        }
        try {
            JsonNode payload = JSON.readTree(receipt.substring(start,
                    ReviewLegacyProtocol.endOfJson(receipt, start)));
            if (payload.path("ok").asBoolean(false) && payload.has("reference")) {
                return payload.get("reference").asString();
            }
        } catch (RuntimeException e) {
            log.debug("Review completion receipt could not be parsed: {}", e.getMessage());
        }
        return null;
    }

    private boolean isLookup(String tool) {
        return lookup != null && lookup.executor().isLookupTool(tool);
    }

    private StepDecision.ToolCallResult executeLookupCall(ToolCall call) {
        ToolResult result = lookup.executor().execute(lookup.invocation(),
                new ReviewToolCall(call.name(), call.id(), call.args()));
        return new StepDecision.ToolCallResult(call.id(), result.formatForAi());
    }

    private StepDecision.ToolCallResult executeSubmissionCall(ToolCall call) {
        ToolResult result = submission.executor().execute(submission.invocation(),
                new ReviewToolCall(call.name(), call.id(), call.args()));
        return new StepDecision.ToolCallResult(call.id(), result.formatForAi());
    }

    private StepDecision.ToolCallResult executeExplorationCall(AgentRunContext ctx, ToolCall call) {
        ToolResult result = toolRouter.execute(AgentToolRouter.Mode.REVIEW,
                new ToolCallContext(ctx.owner(), ctx.repo(), ctx.issueNumber(), ctx.workspace(),
                        toRequest(call), ctx.diffSummary()));
        return new StepDecision.ToolCallResult(call.id(), result.formatForAi());
    }

    private boolean isSubmission(String tool) {
        return structured() && submission.executor().isSubmissionTool(tool);
    }

    /**
     * Whether every call in this batch belongs to finalization (assessment and completion), which
     * never counts against the mutation budget.
     *
     * <p>Both are required to publish and neither can loop: there is one assessment per review and
     * one completion. If they were charged, a review that used its rounds on findings could never be
     * finalized — the failure this budget is not meant to cause.</p>
     */
    private static boolean finalizesOnly(List<String> toolNames) {
        List<String> submissions = toolNames.stream().filter(ReviewAgentStrategy::isStaticSubmission)
                .toList();
        return !submissions.isEmpty() && submissions.stream()
                .allMatch(tool -> isCompletion(tool)
                        || ReviewSubmissionToolExecutor.SET_ASSESSMENT.equals(normalize(tool)));
    }

    private static boolean isStaticSubmission(String tool) {
        return ReviewSubmissionToolExecutor.UPSERT_FINDING.equals(normalize(tool))
                || ReviewSubmissionToolExecutor.REMOVE_FINDING.equals(normalize(tool))
                || ReviewSubmissionToolExecutor.GET_DRAFT.equals(normalize(tool))
                || ReviewSubmissionToolExecutor.SET_ASSESSMENT.equals(normalize(tool))
                || ReviewSubmissionToolExecutor.COMPLETE.equals(normalize(tool));
    }

    /**
     * Refuses an over-budget batch without executing any of it. The draft keeps everything already
     * submitted, and the model is told to finalize: a spent submission budget must never discard a
     * review that is merely missing its completion call.
     */
    private static StepDecision refuseOverBudget(List<ToolCall> calls) {
        List<StepDecision.ToolCallResult> refused = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            refused.add(new StepDecision.ToolCallResult(call.id(), "Not executed: "
                    + SUBMISSION_BUDGET_MESSAGE));
        }
        return new StepDecision.ContinueWithToolResults(refused, SUBMISSION_BUDGET_MESSAGE
                + " Nothing in this batch was applied. Call `review-complete` on its own to publish "
                + "what you already submitted.");
    }

    private static boolean isCompletion(String tool) {
        return ReviewSubmissionToolExecutor.COMPLETE.equals(normalize(tool));
    }

    // ------------------------------------------------------------------ text turns

    /**
     * A structured review is never published from text: the first replies are answered with the
     * submission reminder, and after {@value #MAX_COMPLETION_REMINDERS} attempts the run fails, so
     * an incomplete draft cannot become an empty review.
     */
    private StepDecision onTextOnlyTurn(AgentRunContext ctx, String text) {
        if (!structured()) {
            return finish(ctx, text);
        }
        if (completionReminders < MAX_COMPLETION_REMINDERS) {
            completionReminders++;
            log.info("Agentic review for PR #{} replied with text; asking for submission ({}/{})",
                    ctx.issueNumber(), completionReminders, MAX_COMPLETION_REMINDERS);
            String draftState = describeDraft();
            return new StepDecision.Continue(
                    draftState == null ? SUBMISSION_REMINDER : SUBMISSION_REMINDER
                            + "\n\nCurrent draft: " + draftState);
        }
        log.warn("Agentic review for PR #{} never completed its draft; no review will be published",
                ctx.issueNumber());
        return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                "⚠️ *The review was not submitted through the review tools and was not published.*"));
    }

    /** A compact view of the draft so the reminder can name what is still missing. */
    private String describeDraft() {
        if (submission.reviewService() == null) {
            return null;
        }
        return submission.reviewService().findDraft(submission.invocation().reviewId())
                .map(ReviewAgentStrategy::describeDraft)
                .orElse(null);
    }

    private static String describeDraft(DraftSnapshot draft) {
        return "revision " + draft.draftRevision() + ", " + draft.findings().size() + " finding(s)"
                + (draft.assessmentCurrent() ? "" : ", assessment missing or stale");
    }

    // ------------------------------------------------------------------ legacy transport

    @Override
    public StepDecision step(AgentRunContext ctx, String aiResponse, int round) {
        if (structured()) {
            StepDecision submissionStep = stepLegacySubmission(ctx, aiResponse, round);
            if (submissionStep != null) {
                return submissionStep;
            }
        }
        return stepExploration(ctx, aiResponse, round);
    }

    /**
     * Handles a legacy turn that carries submission calls. The generic plan parser reduces tool
     * arguments to positional strings and drops unknown fields, which would flatten evidence and
     * assessment objects — so submission entries are read from the raw JSON by
     * {@link ReviewLegacyProtocol}. A turn mixing submission and exploration entries is refused as
     * a whole rather than half-applied.
     */
    private StepDecision stepLegacySubmission(AgentRunContext ctx, String aiResponse, int round) {
        List<ReviewLegacyProtocol.Entry> entries = ReviewLegacyProtocol.entries(aiResponse);
        if (entries.isEmpty()) {
            return null;
        }
        List<ReviewLegacyProtocol.Entry> submissions =
                entries.stream().filter(entry -> isSubmission(entry.tool())).toList();
        if (submissions.isEmpty()) {
            return null;
        }
        if (entries.stream().anyMatch(ReviewLegacyProtocol.Entry::ambiguous)) {
            return new StepDecision.Continue("Nothing in the previous turn was applied: a submission "
                    + "entry must carry `arguments` (a JSON object) and no `args` list. Send the "
                    + "finding fields as one `arguments` object.");
        }
        if (submissions.stream().anyMatch(entry -> entry.arguments() == null)) {
            return new StepDecision.Continue("Nothing in the previous turn was applied: review tools "
                    + "need their `arguments` object (key/value pairs), not a positional `args` list.");
        }
        if (submissions.size() != entries.size()) {
            return new StepDecision.Continue("Nothing in the previous turn was applied: submission "
                    + "and exploration calls cannot share one turn in this transport. Submit findings "
                    + "and the assessment in one turn, and explore in another.");
        }
        if (submissions.size() > 1 && submissions.stream().anyMatch(entry -> isCompletion(entry.tool()))) {
            return new StepDecision.Continue("Not applied: " + COMPLETION_ALONE_MESSAGE);
        }
        if (!finalizesOnly(submissions.stream().map(ReviewLegacyProtocol.Entry::tool).toList())) {
            submissionRounds++;
            if (submissionRounds > maxSubmissionRounds) {
                log.warn("Agentic review for PR #{} exhausted its {}-round submission budget in the "
                        + "legacy transport; refusing further mutations and asking for completion",
                        ctx.issueNumber(), maxSubmissionRounds);
                return new StepDecision.Continue(SUBMISSION_BUDGET_MESSAGE + " Nothing in this batch "
                        + "was applied. Call `review-complete` on its own to publish what you already "
                        + "submitted.");
            }
        }

        List<StepDecision.ToolCallResult> receipts = new ArrayList<>(submissions.size());
        String reference = null;
        for (ReviewLegacyProtocol.Entry entry : submissions) {
            ToolResult result = submission.executor().execute(submission.invocation(),
                    new ReviewToolCall(entry.tool(), entry.id(), entry.arguments()));
            receipts.add(new StepDecision.ToolCallResult(entry.id(), formatLegacyResult(entry.tool(), result)));
            if (isCompletion(entry.tool()) && result.success()) {
                reference = referenceFromReceipt(result.output());
            }
        }
        if (ctx.progressConsumer() != null) {
            ctx.progressConsumer().accept(new AgentRunContext.ProgressUpdate(round,
                    describeActivity(submissions.stream().map(ReviewLegacyProtocol.Entry::tool).toList()),
                    receipts.size()));
        }
        if (reference != null) {
            return new StepDecision.Finish(LoopOutcome.success(ctx.baseBranch(), reference), receipts);
        }
        return new StepDecision.Continue("Review tool results:\n"
                + String.join("\n", receipts.stream().map(StepDecision.ToolCallResult::resultText).toList())
                + "\n\nContinue with the review tools until the draft is complete, then call "
                + "`review-complete` on its own.");
    }

    private static String formatLegacyResult(String tool, ToolResult result) {
        String body = result.success()
                ? result.output()
                : "Failed: " + ToolFailures.describe(result);
        return "### `" + tool + "`\n" + body;
    }

    /** The unchanged read-only exploration path (requestFiles/requestTools/runTools). */
    private StepDecision stepExploration(AgentRunContext ctx, String aiResponse, int round) {
        ImplementationPlan plan = responseParser.parseAiResponse(aiResponse);

        // Collect legacy tool requests (requestTools/runTools) and explicit file requests.
        // The REVIEW router rejects tools not explicitly enabled for read-only review.
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
            if (ctx.progressConsumer() != null) {
                List<String> inspected = new ArrayList<>(
                        toolRequests.stream().map(ImplementationPlan.ToolRequest::getTool).toList());
                if (requestFiles != null && !requestFiles.isEmpty()) {
                    inspected.add("cat " + requestFiles.size() + " file(s)");
                }
                ctx.progressConsumer().accept(new AgentRunContext.ProgressUpdate(round,
                        "inspected repository: " + String.join(", ", inspected),
                        toolRequests.size()));
            }
            String next = structured()
                    ? "Here is the requested repository context:\n" + context
                            + "\n\nContinue with the review tools: submit findings with "
                            + "`review-upsert-finding`, set the assessment with "
                            + "`review-set-assessment`, then call `review-complete` on its own."
                    : "Here is the requested repository context:\n" + context
                            + "\n\nContinue your review. When you have gathered enough context, reply with "
                            + "the final review as plain Markdown (no JSON, no further tool requests).";
            return new StepDecision.Continue(next);
        }

        if (wantsContext) {
            log.warn("Agentic review (legacy) hit the context-round cap ({}) for PR #{} before completion",
                    maxContextRounds, ctx.issueNumber());
            if (structured()) {
                return new StepDecision.Continue(EXPLORATION_EXHAUSTED_MESSAGE);
            }
            return new StepDecision.Finish(onBudgetExhausted(ctx));
        }
        if (structured()) {
            // A structured review is only ever finished by the completion tool; a prose reply is
            // answered with the bounded submission reminder instead.
            return onTextOnlyTurn(ctx, finalReviewText(plan, aiResponse));
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
     * Determines the final answer from a legacy turn (clarification mode only). A parseable plan
     * without further requests yields its {@code summary}; otherwise the raw assistant response.
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
                    tools.append(ToolResult.TRUNCATED_OUTPUT_WARNING).append('\n');
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
            if (isLookup(req.getTool())) {
                results.add(executeLegacyLookup(req));
                continue;
            }
            results.add(toolRouter.execute(AgentToolRouter.Mode.REVIEW,
                    new ToolCallContext(ctx.owner(), ctx.repo(), ctx.issueNumber(),
                            ctx.workspace(), req, ctx.diffSummary())));
        }
        return results;
    }

    /** The legacy transport passes positions, so `reference` is argument 0 and `query` argument 1. */
    private ToolResult executeLegacyLookup(ImplementationPlan.ToolRequest request) {
        ObjectNode arguments = JSON.createObjectNode();
        List<String> args = request.getArgs() == null ? List.of() : request.getArgs();
        if (!args.isEmpty() && args.get(0) != null && !args.getFirst().isBlank()) {
            arguments.put("reference", args.getFirst());
        }
        if (args.size() > 1 && args.get(1) != null && !args.get(1).isBlank()) {
            arguments.put("query", args.get(1));
        }
        return lookup.executor().execute(lookup.invocation(),
                new ReviewToolCall(request.getTool(), request.getId(), arguments));
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

    private static String normalize(String tool) {
        return tool == null ? "" : tool.strip().toLowerCase(Locale.ROOT);
    }

    private StepDecision incompleteTurn(AgentRunContext ctx, StopReason reason) {
        log.warn("Agentic review stopped before completion for PR #{}: {}", ctx.issueNumber(), reason);
        return new StepDecision.Finish(LoopOutcome.fail(ctx.baseBranch(),
                "Agentic review stopped before completion: " + reason));
    }

    int submissionRoundsUsed() {
        return submissionRounds;
    }
}
