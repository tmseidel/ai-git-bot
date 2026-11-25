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

import java.util.List;
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
                        "Collect review feedback",
                        WorkflowParamField.ParamType.BOOLEAN, false,
                        "false",
                        "Accept `@bot feedback …` commands on this bot's reviews, and store the task, "
                                + "diff, prompt and revisions a review was produced from so that "
                                + "feedback can later be analysed against what the bot actually saw. "
                                + "With this off, feedback commands are not recorded and no snapshot "
                                + "is kept; reviews are created identically."),
                new WorkflowParamField(AgentReviewParam.LEARNING_MODE,
                        "Learning mode",
                        false,
                        AgentReviewLearningMode.OFF.name(),
                        "How this bot's agentic reviews learn from feedback. This configuration is "
                                + "shared: the mode applies to every bot assigned to it, while each "
                                + "bot and repository keeps its own lessons.",
                        List.of(new WorkflowParamField.EnumOption(AgentReviewLearningMode.OFF.name(),
                                        "Off",
                                        "Record feedback (when collected) without creating or using "
                                                + "lessons. Stored lessons are kept."),
                                new WorkflowParamField.EnumOption(
                                        AgentReviewLearningMode.CURATED.name(),
                                        "Curated",
                                        "Learning maintainers write lessons from feedback and "
                                                + "activate them through Git comments. No AI "
                                                + "proposes or activates anything."))),
                new WorkflowParamField(AgentReviewParam.LEARNING_MAINTAINERS,
                        "Learning maintainers",
                        WorkflowParamField.ParamType.TEXT, false,
                        null,
                        "Git accounts allowed to propose, activate and disable lessons with "
                                + "`@bot learning …`, one per line. Empty means nobody: permission to "
                                + "give feedback is not permission to change what future reviews "
                                + "are told."));
    }

    @Override
    public List<String> validateParams(Map<String, String> params) {
        return AgentReviewLearningSettingsValidator.validate(params);
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
            // Generated and stored, but deliberately not posted. Not a failure: a head that moved
            // during the review is the normal reason, and the stored review stays available.
            case SUPPRESSED -> WorkflowResult.skipped(
                    "Agentic review stored but not posted (suppressed or already published; "
                            + "see the review log for the reason)");
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
                        boolParam(params, AgentReviewParam.ENABLE_PROGRESS_COMMENTS, false),
                        AgentReviewLearningSettingsResolver.from(params));

        WorkflowResult result = switch (answered) {
            case POSTED -> WorkflowResult.success("Clarification posted");
            case SUPPRESSED -> WorkflowResult.skipped("Clarification was not posted");
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

}
