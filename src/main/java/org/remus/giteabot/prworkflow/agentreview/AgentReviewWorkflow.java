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

/**
 * Agentic {@link PrWorkflow}: reviews a pull request like
 * {@link org.remus.giteabot.prworkflow.review.ReviewWorkflow}, but lets the LLM
 * iteratively call read-only repository tools and MCP tools (via
 * {@link AgentReviewService}) before producing its review.
 *
 * <p>The bot may <strong>only read</strong> the repository — no file-mutation,
 * build/validation or git-write tools are exposed, and the workflow never
 * commits, pushes, opens branches or posts a formal review action. The result
 * is a single Markdown PR comment.</p>
 *
 * <p>Category {@link PrWorkflowCategory#REVIEW}; the workflow is opt-in per bot
 * via the workflow-selection UI (the orchestrator only runs workflows an
 * operator has explicitly enabled on the bot's configuration).</p>
 */
@Slf4j
@Component
public class AgentReviewWorkflow implements PrWorkflow {

    /** Public, stable identifier referenced by config rows and metrics tags. */
    public static final String KEY = "agentic-review";

    static final int DEFAULT_MAX_TOOL_ROUNDS = 12;

    private final AgentReviewServiceFactory serviceFactory;
    private final WorkflowSelectionService selectionService;

    /**
     * {@code selectionService} is {@link Lazy} for the same reason as in
     * {@code E2ETestWorkflow}: it transitively depends on
     * {@code PrWorkflowRegistry}, which enumerates every {@link PrWorkflow}
     * bean (including this one). The lazy proxy breaks the construction cycle.
     */
    public AgentReviewWorkflow(AgentReviewServiceFactory serviceFactory,
                               @Lazy WorkflowSelectionService selectionService) {
        this.serviceFactory = serviceFactory;
        this.selectionService = selectionService;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "Agentic PR Review";
    }

    @Override
    public String description() {
        return "Reviews the pull request with an LLM that can iteratively call read-only "
                + "repository and MCP tools to gather context before writing its findings as a "
                + "Markdown comment. Read-only — it never commits, pushes or posts a formal "
                + "review action.";
    }

    @Override
    public PrWorkflowCategory category() {
        return PrWorkflowCategory.REVIEW;
    }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField(AgentReviewParam.MAX_TOOL_ROUNDS,
                        "Max tool-exploration rounds",
                        WorkflowParamField.ParamType.INTEGER, false,
                        String.valueOf(DEFAULT_MAX_TOOL_ROUNDS),
                        "Upper bound on how many explore/answer rounds the agent may take while "
                                + "reading the repository (1-30). Higher values allow deeper analysis "
                                + "at higher token cost."));
    }

    @Override
    public WorkflowResult run(PrWorkflowContext context) {
        Bot bot = context.bot();
        WebhookPayload payload = context.payload();

        // Conversational clarification: user posted @bot clarify <question>
        String clarification = context.hint(PrWorkflowContext.HINT_AGENTIC_REVIEW_CLARIFICATION);
        if (clarification != null && !clarification.isBlank()) {
            return doClarification(context, clarification);
        }

        Map<String, Object> params = bot.getWorkflowConfiguration() == null
                ? Map.of()
                : selectionService.resolveParams(bot.getWorkflowConfiguration().getId(), KEY);
        int maxToolRounds = intParam(params, AgentReviewParam.MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);

        // Cooperative cancellation guard before the (potentially expensive) LLM run.
        context.requireActive("before running agentic review");

        boolean reviewed = serviceFactory.create(bot)
                .reviewPullRequest(payload, maxToolRounds);

        context.appendStep("agentic-review",
                reviewed ? "Posted agentic review for PR" : "Skipped — no diff or no review produced");

        return reviewed
                ? WorkflowResult.success("Agentic review posted")
                : WorkflowResult.skipped("No diff or no review produced");
    }

    private WorkflowResult doClarification(PrWorkflowContext context, String userQuestion) {
        Bot bot = context.bot();
        context.requireActive("before running agentic clarification");

        boolean answered = serviceFactory.create(bot)
                .answerClarification(context.payload(), userQuestion);

        context.appendStep("agentic-clarification",
                answered ? "Posted clarification response" : "Failed to produce clarification");

        return answered
                ? WorkflowResult.success("Clarification posted")
                : WorkflowResult.skipped("No clarification produced");
    }

    private int intParam(Map<String, Object> params, AgentReviewParam name, int fallback) {
        Object raw = params.get(name.key());
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}



