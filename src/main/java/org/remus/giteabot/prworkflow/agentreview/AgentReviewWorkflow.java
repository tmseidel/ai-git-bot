package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AgentReviewWorkflow implements PrWorkflow {

    public static final String KEY = "agentic-review";

    static final int DEFAULT_MAX_TOOL_ROUNDS = 12;

    static final String DEFAULT_FORMAL_REVIEW_DECISION_PROMPT = """
            # Formal Review Decision

            Based on your review findings, decide whether to approve, request changes,
            or leave the PR review state unchanged. Use the following guidelines:

            - **APPROVE** — The PR is correct, follows best practices, and has no
            significant issues that should block merging. Minor style nits or
            optional suggestions do not warrant blocking.

            - **REQUEST_CHANGES** — The PR has non-trivial bugs, security concerns,
            missing error handling, broken tests, or significant code quality issues
            that must be fixed before merging. Regression risk alone is not enough —
            there must be an identifiable problem.

            - **NONE** — There are minor issues or suggestions, but nothing blocking.
            Also use NONE when you lack sufficient information to make a confident
            decision, or when the change is too large to assess reliably from the
            diff alone.""";

    private final AgentReviewServiceFactory serviceFactory;
    private final WorkflowSelectionService selectionService;

    public AgentReviewWorkflow(AgentReviewServiceFactory serviceFactory,
                               @Lazy WorkflowSelectionService selectionService) {
        this.serviceFactory = serviceFactory;
        this.selectionService = selectionService;
    }

    @Override public String key() { return KEY; }
    @Override public String displayName() { return "Agentic PR Review"; }
    @Override public String description() {
        return "Reviews the pull request with an LLM that can iteratively call read-only "
                + "repository and MCP tools to gather context before writing its findings as a "
                + "Markdown comment.";
    }
    @Override public PrWorkflowCategory category() { return PrWorkflowCategory.REVIEW; }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField(AgentReviewParam.MAX_TOOL_ROUNDS,
                        "Max tool-exploration rounds",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(DEFAULT_MAX_TOOL_ROUNDS),
                        "Upper bound on how many explore/answer rounds the agent may take."),
                new WorkflowParamField(AgentReviewParam.ENABLE_FORMAL_REVIEW_DECISION,
                        "Enable formal review decision",
                        WorkflowParamField.ParamType.BOOLEAN, false,
                        "false",
                        "When enabled, the bot may post a formal PR review decision."),
                new WorkflowParamField(AgentReviewParam.FORMAL_REVIEW_DECISION_PROMPT,
                        "Approval decision prompt",
                        WorkflowParamField.ParamType.TEXT, false,
                        DEFAULT_FORMAL_REVIEW_DECISION_PROMPT,
                        "Criteria for when the bot should approve or request changes."));
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        Bot bot = context.bot();
        WebhookPayload payload = context.payload();

        String clarification = context.hint(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION);
        if (clarification != null && !clarification.isBlank()) {
            return doClarification(context, clarification);
        }

        Map<String, Object> params = resolveParams(bot);
        int maxToolRounds = intParam(params, AgentReviewParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);
        boolean enableFormalDecision = boolParam(params, AgentReviewParam.ENABLE_FORMAL_REVIEW_DECISION, false);
        String decisionPrompt = strParam(params, AgentReviewParam.FORMAL_REVIEW_DECISION_PROMPT,
                DEFAULT_FORMAL_REVIEW_DECISION_PROMPT);

        context.requireActive("before running agentic review");

        boolean reviewed = serviceFactory.create(bot)
                .reviewPullRequest(payload, maxToolRounds, enableFormalDecision, decisionPrompt,
                        context.runId(), context.auditToolCallConsumer());

        context.appendStep("agentic-review",
                reviewed ? "Posted agentic review for PR" : "Skipped — no diff or no review produced");

        return reviewed
                ? WorkflowResult.success("Agentic review posted")
                : WorkflowResult.skipped("No diff or no review produced");
    }

    private WorkflowResult doClarification(PrWorkflowContext context, String userQuestion) {
        Bot bot = context.bot();
        context.requireActive("before running agentic clarification");

        Map<String, Object> params = resolveParams(bot);
        int maxToolRounds = intParam(params, AgentReviewParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);

        boolean answered = serviceFactory.create(bot)
                .answerClarification(context.payload(), userQuestion, maxToolRounds);

        context.appendStep("agentic-clarification",
                answered ? "Posted clarification response" : "Failed to produce clarification");

        return answered
                ? WorkflowResult.success("Clarification posted")
                : WorkflowResult.skipped("No clarification produced");
    }

    private Map<String, Object> resolveParams(Bot bot) {
        if (bot.getWorkflowConfiguration() == null) return Map.of();
        return selectionService.resolveParams(bot.getWorkflowConfiguration().getId(), KEY);
    }

    private int intParam(Map<String, Object> params, AgentReviewParam name, int fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw.toString().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private boolean boolParam(Map<String, Object> params, AgentReviewParam name, boolean fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof Boolean b) return b;
        if (raw == null) return fallback;
        String s = raw.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String strParam(Map<String, Object> params, AgentReviewParam name, String fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof String s && !s.isBlank()) return s;
        return fallback;
    }
}
