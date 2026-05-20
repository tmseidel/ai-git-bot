package org.remus.giteabot.prworkflow.e2e.agents;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * First stage of the E2E workflow: turns a PR diff into a structured
 * {@link TestPlan}. The planner is deliberately tool-free in Iteration 2 —
 * it gets the diff inline as the user message and reasons purely over text.
 * Repository-aware tooling ({@code cat}, {@code rg}, {@code tree}, {@code
 * get-issue}) requires the heavier {@code AgentToolRouter} integration and
 * is tracked separately as a follow-up.
 *
 * <p>The planner runs through the dedicated {@link E2eAgentRunner} (not the
 * coding-agent {@code AgentLoop}) — see that class's Javadoc for the
 * rationale. The runner is configured with an empty descriptor list so the
 * loop short-circuits after the first text-only turn.</p>
 */
@Slf4j
@Component
public class TestPlannerAgent {

    /** Soft cap on assistant rounds the planner is allowed to spend. */
    public static final int DEFAULT_MAX_ROUNDS = 2;
    public static final int DEFAULT_MAX_TOKENS = 4_096;

    private final PrWorkflowToolExecutor toolExecutor;

    public TestPlannerAgent(PrWorkflowToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    /**
     * Runs the planner. Returns the parsed plan if the model produced one,
     * empty otherwise (parser rejection, AI failure, or empty journeys).
     */
    public Optional<TestPlan> plan(AiClient aiClient,
                                   E2eTestFramework framework,
                                   PlannerInput input) {
        if (aiClient == null) {
            log.warn("TestPlannerAgent.plan called with null AiClient — skipping");
            return Optional.empty();
        }
        E2eAgentRunner runner = new E2eAgentRunner(
                aiClient,
                toolExecutor,
                /* toolContext = */ null,
                /* toolDescriptors = */ List.of(),
                E2ePromptLibrary.plannerSystemPrompt(framework),
                DEFAULT_MAX_ROUNDS,
                DEFAULT_MAX_TOKENS,
                "test-planner");

        E2eAgentRunner.Result result = runner.run(renderUserMessage(framework, input));
        if (result.budgetExhausted() && result.lastAssistantText().isBlank()) {
            log.warn("TestPlannerAgent: budget exhausted with no assistant text");
            return Optional.empty();
        }
        Optional<TestPlan> parsed = TestPlanParser.parse(result.lastAssistantText());
        if (parsed.isEmpty()) {
            log.warn("TestPlannerAgent: assistant text could not be parsed into a TestPlan ({} chars)",
                    result.lastAssistantText().length());
            return Optional.empty();
        }
        TestPlan plan = parsed.get();
        if (plan.isEmpty()) {
            log.info("TestPlannerAgent: planner returned a plan with zero journeys");
            return Optional.of(plan);
        }
        return Optional.of(plan);
    }

    /** Inputs the planner needs: PR title, body, unified diff, optional operator feedback. */
    public record PlannerInput(String prTitle, String prBody, String diff, String feedback) {
        public PlannerInput {
            if (prTitle == null) prTitle = "";
            if (prBody  == null) prBody  = "";
            if (diff    == null) diff    = "";
            if (feedback == null) feedback = "";
        }

        /** Convenience constructor for the no-feedback case (back-compat). */
        public PlannerInput(String prTitle, String prBody, String diff) {
            this(prTitle, prBody, diff, "");
        }
    }

    private static String renderUserMessage(E2eTestFramework framework, PlannerInput input) {
        StringBuilder sb = new StringBuilder(input.diff().length() + 512);
        sb.append("Target framework: ").append(framework.key()).append('\n');
        sb.append("\n## Pull request title\n").append(input.prTitle()).append('\n');
        if (!input.prBody().isBlank()) {
            sb.append("\n## Pull request body\n").append(input.prBody()).append('\n');
        }
        if (!input.feedback().isBlank()) {
            sb.append("\n## Operator feedback (from `@bot regenerate-tests`)\n")
              .append("Treat this as the primary signal — adjust journeys, coverage and assertions accordingly.\n\n")
              .append(input.feedback()).append('\n');
        }
        sb.append("\n## Unified diff (truncated if very large)\n");
        sb.append("```diff\n");
        sb.append(truncate(input.diff(), 40_000));
        if (!input.diff().endsWith("\n")) sb.append('\n');
        sb.append("```\n");
        sb.append("\nProduce the JSON plan now.");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n[…diff truncated…]";
    }
}


