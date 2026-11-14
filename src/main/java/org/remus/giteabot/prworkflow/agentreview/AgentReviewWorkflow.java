package org.remus.giteabot.prworkflow.agentreview;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;
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
                + "repository and MCP tools, then records findings and an overall assessment "
                + "that the application renders as the review comment.";
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
                new WorkflowParamField(AgentReviewParam.ENABLE_PROGRESS_COMMENTS,
                        "Show review progress",
                        WorkflowParamField.ParamType.BOOLEAN, false,
                        "false",
                        "Creates one progress comment and updates it during review or clarification."),
                new WorkflowParamField(AgentReviewParam.ENABLE_FORMAL_REVIEW_DECISION,
                        "Enable formal review decision",
                        WorkflowParamField.ParamType.BOOLEAN, false,
                        "false",
                        "When enabled, the bot posts approve or request-changes, computed from the "
                                + "stored findings and the thresholds below. The model is never asked "
                                + "to decide."),
                new WorkflowParamField(AgentReviewParam.BLOCKER_THRESHOLD,
                        "Blocker must be less or equal",
                        WorkflowParamField.ParamType.INTEGER, false,
                        null,
                        "Maximum allowed BLOCKER findings for a formal APPROVE. Leave empty to ignore."),
                new WorkflowParamField(AgentReviewParam.MEDIUM_THRESHOLD,
                        "Medium must be less or equal",
                        WorkflowParamField.ParamType.INTEGER, false,
                        null,
                        "Maximum allowed MEDIUM findings for a formal APPROVE. Leave empty to ignore."),
                new WorkflowParamField(AgentReviewParam.LOW_THRESHOLD,
                        "Low must be less or equal",
                        WorkflowParamField.ParamType.INTEGER, false,
                        null,
                        "Maximum allowed LOW findings for a formal APPROVE. Leave empty to ignore."),
                new WorkflowParamField(AgentReviewParam.FEEDBACK_ENABLED,
                        "Keep review evidence",
                        WorkflowParamField.ParamType.BOOLEAN, false,
                        "false",
                        "Store the task, diff, prompt and revisions a review was produced from, so "
                                + "later feedback can be analysed against what the bot actually saw. "
                                + "Reviews are created identically when this is off."));
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
        boolean enableProgressComments = boolParam(params, AgentReviewParam.ENABLE_PROGRESS_COMMENTS, false);
        boolean enableFormalDecision = boolParam(params, AgentReviewParam.ENABLE_FORMAL_REVIEW_DECISION, false);
        AgentReviewService.SeverityThresholds thresholds = new AgentReviewService.SeverityThresholds(
                integerParam(params, AgentReviewParam.BLOCKER_THRESHOLD),
                integerParam(params, AgentReviewParam.MEDIUM_THRESHOLD),
                integerParam(params, AgentReviewParam.LOW_THRESHOLD));

        context.requireActive("before running agentic review");

        AgentReviewService.ReviewResult reviewed = serviceFactory.create(bot)
                .reviewPullRequest(payload, maxToolRounds, enableFormalDecision, thresholds,
                        context.runId(), context.auditToolCallConsumer(), enableProgressComments,
                        AgentReviewLearningSettingsResolver.from(params));

        WorkflowResult result = switch (reviewed) {
            case POSTED -> WorkflowResult.success("Agentic review posted");
            case NO_DIFF -> WorkflowResult.skipped("No diff to review");
            case FAILED -> WorkflowResult.failed("Agentic review failed; no complete review was produced");
        };
        context.appendStep("agentic-review", result.summary());
        return result;
    }

    private WorkflowResult doClarification(PrWorkflowContext context, String userQuestion) {
        Bot bot = context.bot();
        context.requireActive("before running agentic clarification");

        Map<String, Object> params = resolveParams(bot);
        int maxToolRounds = intParam(params, AgentReviewParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);

        AgentReviewService.ReviewResult answered = serviceFactory.create(bot)
                .answerClarification(context.payload(), userQuestion, maxToolRounds,
                        boolParam(params, AgentReviewParam.ENABLE_PROGRESS_COMMENTS, false));

        WorkflowResult result = switch (answered) {
            case POSTED -> WorkflowResult.success("Clarification posted");
            case NO_DIFF -> WorkflowResult.skipped("No diff to answer clarification");
            case FAILED -> WorkflowResult.failed("No clarification produced");
        };
        context.appendStep("agentic-clarification", result.summary());
        return result;
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

    private Integer integerParam(Map<String, Object> params, AgentReviewParam name) {
        Object raw = params.get(name.key());
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return null;
        try { return Integer.parseInt(raw.toString().trim()); }
        catch (NumberFormatException e) { return null; }
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
