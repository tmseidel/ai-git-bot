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
import org.remus.giteabot.systemsettings.SystemPrompt;
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
    private final SystemPromptAssembler promptAssembler;

    public TestAuthorAgent(ToolCatalog toolCatalog,
                           PrWorkflowToolExecutor toolExecutor) {
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
        this.promptAssembler = new SystemPromptAssembler();
    }

    public Result write(AiClient aiClient,
                        PrWorkflowToolContext toolContext,
                        TestPlan plan,
                        SystemPrompt systemPrompt) {
        if (aiClient == null) {
            return new Result(0, "AI client unavailable", true);
        }
        if (plan == null || plan.isEmpty()) {
            return new Result(0, "No journeys in the plan", false);
        }
        Set<String> allowed = Set.of("pr-test-write");
        List<ToolDescriptor> descriptors = toolCatalog.nativeDescriptors(
                ToolCatalog.Role.E2E, null, allowed);

        // Build the system prompt the same way the issue / writer agents do:
        // role description (from E2ePromptLibrary or the operator-edited
        // SystemPrompt) + tool protocol section rendered from the ToolCatalog
        // by SystemPromptAssembler. The mode chosen here matches the one
        // E2eAgentRunner will resolve so the prompt and the actual dispatch
        // path stay aligned.
        ToolingMode mode = ToolingMode.resolve(ToolingMode.NATIVE,
                aiClient.supportsNativeTools(), !descriptors.isEmpty());
        String systemPromptText = promptAssembler.assemble(
                E2ePromptLibrary.authorSystemPromptOrDefault(systemPrompt, toolContext.framework()),
                toolCatalog, allowed, null, mode,
                SystemPromptAssembler.PromptKind.E2E_AGENT);

        int maxRounds = Math.max(BASELINE_ROUNDS, plan.journeys().size() + 2);
        E2eAgentRunner runner = new E2eAgentRunner(
                aiClient, toolExecutor, toolContext, descriptors,
                systemPromptText,
                maxRounds, DEFAULT_MAX_TOKENS, 120_000, "test-author");

        String userMessage = renderUserMessage(toolContext.framework(), plan);
        E2eAgentRunner.Result raw = runner.run(userMessage);
        int writes = countWrites(raw);

        // Recovery for the "narrated tool call" failure mode:
        //
        //   Some Claude releases (and any model when the prompt grows large)
        //   describe their tool invocations as plain text — either as
        //   ```json {"name":"pr-test-write","parameters":{...}}``` blocks or
        //   as the leaked Claude XML form:
        //
        //       <function_calls>
        //         <invoke name="pr-test-write">
        //           <parameter name="path">tests/foo.spec.ts</parameter>
        //           <parameter name="content">…</parameter>
        //         </invoke>
        //       </function_calls>
        //
        //   In both cases the runner sees stopReason=END_TURN, toolCalls=0 and
        //   we would otherwise write zero files. A second LLM round can fail
        //   exactly the same way (and costs another ~4k output tokens), so we
        //   parse the narrated calls and execute them directly through the
        //   same PrWorkflowToolExecutor. This is content-preserving: the
        //   path/content payloads are fully present in the assistant text.
        if (writes == 0) {
            List<NarratedToolCallParser.Call> recovered =
                    NarratedToolCallParser.parse(raw.lastAssistantText());
            int recoveredWrites = 0;
            for (NarratedToolCallParser.Call call : recovered) {
                if (!"pr-test-write".equalsIgnoreCase(call.name())) continue;
                String result = toolExecutor.execute(call.name(), call.args(), toolContext);
                if (result != null && result.startsWith("OK:")) {
                    recoveredWrites++;
                } else {
                    log.warn("TestAuthorAgent: recovered narrated `pr-test-write` call for path={}"
                                    + " failed: {}",
                            call.args().get("path"), result);
                }
            }
            if (recoveredWrites > 0) {
                log.warn("TestAuthorAgent: recovered {} narrated `pr-test-write` call(s) from"
                                + " assistant text after native tool_use produced 0 calls"
                                + " (likely Claude regression to legacy tool-call syntax)",
                        recoveredWrites);
                writes = recoveredWrites;
            } else if (looksLikeNarratedToolCall(raw.lastAssistantText())) {
                log.warn("TestAuthorAgent: assistant text contains narrated tool-call markers"
                        + " but no calls could be recovered — see DEBUG-level AI response dump");
            }
        }

        boolean exhausted = raw.budgetExhausted() && writes < plan.journeys().size();
        if (exhausted) {
            log.warn("TestAuthorAgent: budget exhausted after writing {} / {} files",
                    writes, plan.journeys().size());
        }
        return new Result(writes, raw.lastAssistantText(), exhausted);
    }

    private static int countWrites(E2eAgentRunner.Result raw) {
        int writes = 0;
        for (E2eAgentRunner.ToolInvocation inv : raw.toolInvocations()) {
            if ("pr-test-write".equalsIgnoreCase(inv.toolName())
                    && inv.result() != null
                    && inv.result().startsWith("OK:")) {
                writes++;
            }
        }
        return writes;
    }

    /**
     * Best-effort heuristic to detect either of the two known "narrated tool
     * call" failure modes — JSON-fenced blocks or leaked Claude XML
     * ({@code <function_calls><invoke name="..."><parameter ...>}). Used only
     * to decide whether a "no calls recovered" situation deserves a WARN log;
     * the actual recovery is performed by {@link NarratedToolCallParser}.
     */
    static boolean looksLikeNarratedToolCall(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) return false;
        String lower = assistantText.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("pr-test-write")) return false;
        boolean jsonForm = (lower.contains("\"name\"") || lower.contains("'name'"))
                && (lower.contains("\"parameters\"")
                    || lower.contains("\"arguments\"")
                    || lower.contains("\"input\""));
        boolean xmlForm = lower.contains("<invoke") && lower.contains("<parameter");
        boolean toolCallTag = lower.contains("<tool_call>");
        return jsonForm || xmlForm || toolCallTag;
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
