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
 * Second stage of the E2E workflow: materialises every journey from the
 * {@link TestPlan} into a runnable test file. The agent is given exactly
 * one tool — {@code pr-test-write} — and the prompt tells it to call the
 * tool once per journey using the planner-provided {@code fileName}.
 *
 * <p>Persistence of {@code PrTestCase} rows happens inside the tool
 * executor; the agent only needs to invoke {@code pr-test-write} with the
 * planner-supplied path / content / title.</p>
 */
@Slf4j
@Component
public class TestAuthorAgent {

    /** Default round budget per author run. One round per journey + a small overhead. */
    public static final int BASELINE_ROUNDS = 4;
    public static final int DEFAULT_MAX_TOKENS = 6_144;

    private final ToolCatalog toolCatalog;
    private final PrWorkflowToolExecutor toolExecutor;

    public TestAuthorAgent(ToolCatalog toolCatalog,
                           PrWorkflowToolExecutor toolExecutor) {
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
    }

    public Result write(AiClient aiClient,
                        PrWorkflowToolContext toolContext,
                        TestPlan plan) {
        if (aiClient == null) {
            return new Result(0, "AI client unavailable", true);
        }
        if (plan == null || plan.isEmpty()) {
            return new Result(0, "No journeys in the plan", false);
        }
        Set<String> allowed = Set.of("pr-test-write");
        List<ToolDescriptor> descriptors = toolCatalog.nativeDescriptors(
                ToolCatalog.Role.E2E, null, allowed);

        int maxRounds = Math.max(BASELINE_ROUNDS, plan.journeys().size() + 2);
        E2eAgentRunner runner = new E2eAgentRunner(
                aiClient, toolExecutor, toolContext, descriptors,
                E2ePromptLibrary.authorSystemPrompt(toolContext.framework()),
                maxRounds, DEFAULT_MAX_TOKENS, "test-author");

        E2eAgentRunner.Result raw = runner.run(renderUserMessage(toolContext.framework(), plan));

        int writes = 0;
        for (E2eAgentRunner.ToolInvocation inv : raw.toolInvocations()) {
            if ("pr-test-write".equalsIgnoreCase(inv.toolName())
                    && inv.result() != null
                    && inv.result().startsWith("OK:")) {
                writes++;
            }
        }
        boolean exhausted = raw.budgetExhausted() && writes < plan.journeys().size();
        if (exhausted) {
            log.warn("TestAuthorAgent: budget exhausted after writing {} / {} files",
                    writes, plan.journeys().size());
        }
        return new Result(writes, raw.lastAssistantText(), exhausted);
    }

    /**
     * @param filesWritten      number of successful {@code pr-test-write} calls
     * @param finalAssistantText the model's final text turn (for diagnostics)
     * @param budgetExhausted   whether the agent ran out of rounds before
     *                          finishing every journey
     */
    public record Result(int filesWritten, String finalAssistantText, boolean budgetExhausted) {
        public boolean wroteAnything() { return filesWritten > 0; }
    }

    private static String renderUserMessage(E2eTestFramework framework, TestPlan plan) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Framework: ").append(framework.key()).append('\n');
        sb.append("Journeys to materialise: ").append(plan.journeys().size()).append("\n\n");
        sb.append("Plan JSON:\n```json\n");
        for (int i = 0; i < plan.journeys().size(); i++) {
            TestPlan.Journey j = plan.journeys().get(i);
            sb.append(i == 0 ? "{\n  \"journeys\": [\n    " : ",\n    ");
            sb.append("{ \"id\": \"").append(escape(j.id())).append('"');
            sb.append(", \"title\": \"").append(escape(j.title())).append('"');
            sb.append(", \"fileName\": \"").append(escape(j.resolveFileName(defaultExtension(framework)))).append('"');
            sb.append(", \"steps\": ").append(toJsonArray(j.steps()));
            sb.append(", \"assertions\": ").append(toJsonArray(j.assertions()));
            sb.append(" }");
        }
        sb.append("\n  ]\n}\n```\n");
        sb.append("\nCall `pr-test-write` once per journey now.");
        return sb.toString();
    }

    private static String defaultExtension(E2eTestFramework framework) {
        return switch (framework) {
            case PLAYWRIGHT, CYPRESS -> ".spec.ts";
            case PYTEST -> ".py";
            case K6 -> ".js";
        };
    }

    private static String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(escape(items.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
