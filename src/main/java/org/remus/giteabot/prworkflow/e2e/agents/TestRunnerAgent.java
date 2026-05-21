package org.remus.giteabot.prworkflow.e2e.agents;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
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
    private final SystemPromptAssembler promptAssembler;

    public TestRunnerAgent(ToolCatalog toolCatalog,
                           PrWorkflowToolExecutor toolExecutor) {
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
        this.promptAssembler = new SystemPromptAssembler();
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

        // System prompt = role description (E2ePromptLibrary) + tool protocol
        // section rendered from ToolCatalog by SystemPromptAssembler — same
        // pipeline as the issue / writer agents. Mode mirrors what
        // E2eAgentRunner will resolve so prompt and dispatch stay aligned.
        ToolingMode mode = ToolingMode.resolve(ToolingMode.NATIVE,
                aiClient.supportsNativeTools(), !descriptors.isEmpty());
        String systemPrompt = promptAssembler.assemble(
                E2ePromptLibrary.runnerSystemPrompt(toolContext.framework()),
                toolCatalog, ALLOWED_TOOLS, null, mode,
                SystemPromptAssembler.PromptKind.E2E_AGENT);

        int maxRounds = Math.max(2, maxRetries) + OVERHEAD_ROUNDS;
        E2eAgentRunner runner = new E2eAgentRunner(
                aiClient, toolExecutor, toolContext, descriptors,
                systemPrompt,
                maxRounds, DEFAULT_MAX_TOKENS, "test-runner");

        E2eAgentRunner.Result raw = runner.run(renderUserMessage(toolContext.framework(), plan, maxRetries));

        int runs = 0;
        int artifacts = 0;
        for (E2eAgentRunner.ToolInvocation inv : raw.toolInvocations()) {
            String name = inv.toolName() == null ? "" : inv.toolName().toLowerCase();
            if ("pr-test-run".equals(name)) runs++;
            else if ("attach-artifact".equals(name)) artifacts++;
        }

        // Recovery for the "narrated tool call" failure mode — symmetrical to
        // TestAuthorAgent. Some Claude releases (and any model when the
        // context grows large) describe their tool invocations as plain text
        // — either as ```json {"name":"pr-test-run","parameters":{...}}```
        // blocks or as the leaked Claude XML form
        // (<function_calls><invoke name="pr-test-run">…). In both cases the
        // runner sees stopReason=END_TURN, toolCalls=0 and we would otherwise
        // execute zero tests — every PrTestCase row stays PENDING and the
        // suite is reported as ERROR even though the model "claimed" (in
        // text) to have run the suite, often with fabricated results.
        //
        // We re-execute only `pr-test-run` calls — that tool is idempotent
        // (it just shells out to the test framework and updates the PrTestCase
        // rows from the real JSON reporter output) and it is the call that
        // populates the database. `attach-artifact` is intentionally skipped
        // because the artifact paths are almost always fabricated when the
        // model is in the narrated-call failure mode, and `preview-url` /
        // `preview-status` are informational only.
        if (runs == 0) {
            List<NarratedToolCallParser.Call> recovered =
                    NarratedToolCallParser.parse(raw.lastAssistantText());
            int recoveredRuns = 0;
            for (NarratedToolCallParser.Call call : recovered) {
                if (!"pr-test-run".equalsIgnoreCase(call.name())) continue;
                String result = toolExecutor.execute(call.name(), call.args(), toolContext);
                recoveredRuns++;
                log.warn("TestRunnerAgent: re-executed narrated `pr-test-run` call args={} -> {}",
                        call.args(), abbreviate(result));
            }
            if (recoveredRuns > 0) {
                log.warn("TestRunnerAgent: recovered {} narrated `pr-test-run` call(s) from"
                                + " assistant text after native tool_use produced 0 calls"
                                + " (likely Claude regression to legacy tool-call syntax)",
                        recoveredRuns);
                runs = recoveredRuns;
            } else if (looksLikeNarratedToolCall(raw.lastAssistantText())) {
                log.warn("TestRunnerAgent: assistant text contains narrated tool-call markers"
                        + " but no `pr-test-run` calls could be recovered — see DEBUG-level"
                        + " AI response dump");
            }
        }

        if (raw.budgetExhausted()) {
            log.warn("TestRunnerAgent: budget exhausted after {} pr-test-run / {} attach-artifact calls",
                    runs, artifacts);
        }
        return new Result(runs, artifacts, raw.lastAssistantText(), raw.budgetExhausted());
    }

    /**
     * Heuristic counterpart to {@link TestAuthorAgent#looksLikeNarratedToolCall(String)} —
     * used purely for diagnostic logging when recovery yielded zero usable
     * calls. The authoritative recovery is performed by {@link NarratedToolCallParser}.
     */
    static boolean looksLikeNarratedToolCall(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) return false;
        String lower = assistantText.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("pr-test-run")) return false;
        boolean jsonForm = (lower.contains("\"name\"") || lower.contains("'name'"))
                && (lower.contains("\"parameters\"")
                    || lower.contains("\"arguments\"")
                    || lower.contains("\"input\""));
        boolean xmlForm = lower.contains("<invoke") && lower.contains("<parameter");
        boolean toolCallTag = lower.contains("<tool_call>");
        return jsonForm || xmlForm || toolCallTag;
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
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
