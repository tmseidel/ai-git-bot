package org.remus.giteabot.prworkflow.e2e.agents;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.prworkflow.e2e.E2eTestFramework;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolContext;
import org.remus.giteabot.prworkflow.e2e.tools.PrWorkflowToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Third stage of the E2E workflow: executes the just-authored test files
 * against the preview deployment and reports the outcome. The runner agent
 * has access to four tools:
 *
 * <ul>
 *     <li>{@code preview-url}</li>
 *     <li>{@code preview-status}</li>
 *     <li>{@code pr-test-run}</li>
 *     <li>{@code attach-artifact}</li>
 * </ul>
 *
 * <p>Per-case status updates happen inside {@code pr-test-run} (the
 * executor parses the Playwright JSON reporter and writes back into
 * {@code PrTestCase} rows). The agent's responsibility is to choose
 * whether to retry and to surface optional artefacts on the PR — the
 * authoritative outcome is re-read from the database by the
 * {@code PlaywrightTestSuiteRunner} after this agent returns.</p>
 */
@Slf4j
@Component
public class TestRunnerAgent {

    public static final int DEFAULT_MAX_TOKENS = 4_096;
    /** Extra rounds on top of {@code maxRetries} for the preview probe + final summary. */
    public static final int OVERHEAD_ROUNDS = 4;

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "preview-url", "preview-status", "pr-test-run", "attach-artifact");

    private final ToolCatalog toolCatalog;
    private final PrWorkflowToolExecutor toolExecutor;

    public TestRunnerAgent(ToolCatalog toolCatalog,
                           PrWorkflowToolExecutor toolExecutor) {
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
    }

    public Result execute(AiClient aiClient,
                          PrWorkflowToolContext toolContext,
                          TestPlan plan,
                          int maxRetries) {
        if (aiClient == null) {
            return new Result(0, 0, "AI client unavailable", true);
        }
        List<ToolDescriptor> descriptors = toolCatalog.nativeDescriptors(
                ToolCatalog.Role.E2E, null, ALLOWED_TOOLS);

        int maxRounds = Math.max(2, maxRetries) + OVERHEAD_ROUNDS;
        E2eAgentRunner runner = new E2eAgentRunner(
                aiClient, toolExecutor, toolContext, descriptors,
                E2ePromptLibrary.runnerSystemPrompt(toolContext.framework()),
                maxRounds, DEFAULT_MAX_TOKENS, "test-runner");

        E2eAgentRunner.Result raw = runner.run(renderUserMessage(toolContext.framework(), plan, maxRetries));

        int runs = 0;
        int artifacts = 0;
        for (E2eAgentRunner.ToolInvocation inv : raw.toolInvocations()) {
            String name = inv.toolName() == null ? "" : inv.toolName().toLowerCase();
            if ("pr-test-run".equals(name)) runs++;
            else if ("attach-artifact".equals(name)) artifacts++;
        }
        if (raw.budgetExhausted()) {
            log.warn("TestRunnerAgent: budget exhausted after {} pr-test-run / {} attach-artifact calls",
                    runs, artifacts);
        }
        return new Result(runs, artifacts, raw.lastAssistantText(), raw.budgetExhausted());
    }

    public record Result(int prTestRunInvocations,
                         int attachedArtifacts,
                         String finalAssistantText,
                         boolean budgetExhausted) {
    }

    private static String renderUserMessage(E2eTestFramework framework, TestPlan plan, int maxRetries) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Framework: ").append(framework.key()).append('\n');
        sb.append("Per-case retry budget: ").append(maxRetries).append('\n');
        sb.append("Journeys to execute (").append(plan.journeys().size()).append("):\n");
        for (TestPlan.Journey j : plan.journeys()) {
            sb.append("  - ").append(j.id()).append(": ").append(j.title()).append('\n');
        }
        sb.append("\nProceed with the steps documented in your system prompt.");
        return sb.toString();
    }
}
