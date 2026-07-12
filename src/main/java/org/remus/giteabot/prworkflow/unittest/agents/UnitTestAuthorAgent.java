package org.remus.giteabot.prworkflow.unittest.agents;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.prworkflow.e2e.agents.NarratedToolCallParser;
import org.remus.giteabot.prworkflow.unittest.tools.UnitTestToolContext;
import org.remus.giteabot.prworkflow.unittest.tools.UnitTestToolExecutor;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The single agent stage of the unit-test-author workflow. Given the PR diff
 * and the changed production files, it writes focused unit tests directly into
 * the repository checkout via the {@code unit-test-write} tool.
 *
 * <p>Mirrors {@code TestAuthorAgent} (the E2E author) — including the
 * "narrated tool call" recovery for models that describe their tool calls as
 * plain text instead of emitting native {@code tool_use} blocks — but operates
 * on the real checkout and persists {@code UnitTestCase} rows.</p>
 */
@Slf4j
@Component
public class UnitTestAuthorAgent {

    public static final int BASELINE_ROUNDS = 6;
    public static final int DEFAULT_MAX_TOKENS = 8_192;

    private static final Set<String> ALLOWED_TOOLS = Set.of("unit-test-write");

    private final ToolCatalog toolCatalog;
    private final UnitTestToolExecutor toolExecutor;
    private final SystemPromptAssembler promptAssembler;

    public UnitTestAuthorAgent(ToolCatalog toolCatalog, UnitTestToolExecutor toolExecutor) {
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
        this.promptAssembler = new SystemPromptAssembler();
    }

    /**
     * @param filesWritten       number of successful {@code unit-test-write} calls
     * @param finalAssistantText the model's final text turn (diagnostics)
     * @param budgetExhausted    whether the agent ran out of rounds
     */
    public record Result(int filesWritten, String finalAssistantText, boolean budgetExhausted) {
        public boolean wroteAnything() {
            return filesWritten > 0;
        }
    }

    /**
     * Runs the author agent.
     *
     * @param aiClient     the resolved AI client for the bot
     * @param toolContext  suite + checkout + framework binding for the writer tool
     * @param userMessage  the kickoff message (PR metadata + diff + changed files)
     * @param systemPrompt operator-edited prompts (may be {@code null} → defaults)
     * @param maxTestCases hard upper bound on the number of files to generate
     */
    public Result write(AiClient aiClient,
                        UnitTestToolContext toolContext,
                        String userMessage,
                        SystemPrompt systemPrompt,
                        int maxTestCases) {
        if (aiClient == null) {
            return new Result(0, "AI client unavailable", true);
        }
        List<ToolDescriptor> descriptors = toolCatalog.nativeDescriptors(
                ToolCatalog.Role.PR_WORKFLOW, null, ALLOWED_TOOLS);

        ToolingMode mode = ToolingMode.resolve(ToolingMode.NATIVE,
                aiClient.supportsNativeTools(), !descriptors.isEmpty());
        String systemPromptText = promptAssembler.assemble(
                UnitTestPromptLibrary.authorSystemPromptOrDefault(systemPrompt, toolContext.framework()),
                toolCatalog, ALLOWED_TOOLS, null, mode,
                SystemPromptAssembler.PromptKind.E2E_AGENT);

        int maxRounds = Math.max(BASELINE_ROUNDS, Math.min(maxTestCases, 12) + 2);
        UnitTestAgentRunner runner = new UnitTestAgentRunner(
                aiClient, toolExecutor, toolContext, descriptors,
                systemPromptText, maxRounds, DEFAULT_MAX_TOKENS, "unit-test-author");

        UnitTestAgentRunner.Result raw = runner.run(userMessage);
        int writes = (int) raw.writeCount();

        // Recovery for the "narrated tool call" failure mode (see TestAuthorAgent).
        if (writes == 0) {
            int recovered = 0;
            for (NarratedToolCallParser.Call call : NarratedToolCallParser.parse(raw.lastAssistantText())) {
                if (!"unit-test-write".equalsIgnoreCase(call.name())) {
                    continue;
                }
                String result = toolExecutor.execute(call.name(), call.args(), toolContext);
                if (result != null && result.startsWith("OK")) {
                    recovered++;
                } else {
                    log.warn("UnitTestAuthorAgent: recovered narrated `unit-test-write` for path={} failed: {}",
                            call.args().get("path"), result);
                }
            }
            if (recovered > 0) {
                log.warn("UnitTestAuthorAgent: recovered {} narrated `unit-test-write` call(s) from"
                        + " assistant text after native tool_use produced 0 calls", recovered);
                writes = recovered;
            }
        }

        boolean exhausted = raw.budgetExhausted() && writes == 0;
        return new Result(writes, raw.lastAssistantText(), exhausted);
    }
}

