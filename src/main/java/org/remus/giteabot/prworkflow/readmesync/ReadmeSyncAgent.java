package org.remus.giteabot.prworkflow.readmesync;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.SystemPromptAssembler;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.prworkflow.e2e.agents.NarratedToolCallParser;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The single agent stage of the readme-sync workflow. Given the PR diff and
 * the current content of the in-scope documentation files, it applies the
 * necessary Markdown documentation changes directly into the repository
 * checkout via the {@code doc-write} / {@code doc-delete} tools.
 *
 * <p>Mirrors {@code UnitTestAuthorAgent} — including the "narrated tool call"
 * recovery for models that describe their tool calls as plain text instead of
 * emitting native {@code tool_use} blocks — but operates on documentation
 * rather than tests.</p>
 */
@Slf4j
@Component
public class ReadmeSyncAgent {

    public static final int BASELINE_ROUNDS = 6;
    public static final int DEFAULT_MAX_TOKENS = 8_192;

    private static final Set<String> ALLOWED_TOOLS = Set.of("doc-write", "doc-delete");

    private final ToolCatalog toolCatalog;
    private final ReadmeSyncToolExecutor toolExecutor;
    private final SystemPromptAssembler promptAssembler;

    public ReadmeSyncAgent(ToolCatalog toolCatalog, ReadmeSyncToolExecutor toolExecutor) {
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
        this.promptAssembler = new SystemPromptAssembler();
    }

    /**
     * @param changesApplied   number of successful {@code doc-write} /
     *                         {@code doc-delete} calls
     * @param finalAssistantText the model's final text turn (diagnostics)
     * @param budgetExhausted  whether the agent ran out of rounds
     */
    public record Result(int changesApplied, String finalAssistantText, boolean budgetExhausted) {
        public boolean changedAnything() {
            return changesApplied > 0;
        }
    }

    /**
     * Runs the readme-sync agent.
     *
     * @param aiClient     the resolved AI client for the bot
     * @param toolContext  checkout + include-patterns binding for the doc tools
     * @param userMessage  the kickoff message (PR metadata + diff + in-scope docs)
     * @param systemPrompt operator-edited prompts (may be {@code null} → built-in default)
     * @param maxToolRounds operator-tunable cap on the number of explore/write rounds
     */
    public Result write(AiClient aiClient,
                        ReadmeSyncToolContext toolContext,
                        String userMessage,
                        SystemPrompt systemPrompt,
                        int maxToolRounds) {
        if (aiClient == null) {
            return new Result(0, "AI client unavailable", true);
        }
        List<ToolDescriptor> descriptors = toolCatalog.nativeDescriptors(
                ToolCatalog.Role.PR_WORKFLOW, null, ALLOWED_TOOLS);

        ToolingMode mode = ToolingMode.resolve(ToolingMode.NATIVE,
                aiClient.supportsNativeTools(), !descriptors.isEmpty());
        String systemPromptText = promptAssembler.assemble(
                ReadmeSyncPromptLibrary.systemPrompt(systemPrompt, toolContext.includePatterns()),
                toolCatalog, ALLOWED_TOOLS, null, mode,
                SystemPromptAssembler.PromptKind.E2E_AGENT);

        int maxRounds = Math.max(BASELINE_ROUNDS, Math.min(maxToolRounds, 30) + 2);
        ReadmeSyncAgentRunner runner = new ReadmeSyncAgentRunner(
                aiClient, toolExecutor, toolContext, descriptors,
                systemPromptText, maxRounds, DEFAULT_MAX_TOKENS, "readme-sync");

        ReadmeSyncAgentRunner.Result raw = runner.run(userMessage);
        int applied = countApplied(raw);

        // Recovery for the "narrated tool call" failure mode (see TestAuthorAgent).
        if (applied == 0) {
            int recovered = 0;
            for (NarratedToolCallParser.Call call : NarratedToolCallParser.parse(raw.lastAssistantText())) {
                String name = call.name() == null ? "" : call.name().toLowerCase(java.util.Locale.ROOT);
                if (!ALLOWED_TOOLS.contains(name)) {
                    continue;
                }
                String result = toolExecutor.execute(name, call.args(), toolContext);
                if (result != null && result.startsWith("OK")) {
                    recovered++;
                } else {
                    log.warn("ReadmeSyncAgent: recovered narrated `{}` for path={} failed: {}",
                            name, call.args().get("path"), result);
                }
            }
            if (recovered > 0) {
                log.warn("ReadmeSyncAgent: recovered {} narrated doc tool call(s) from assistant text "
                        + "after native tool_use produced 0 calls", recovered);
                applied = recovered;
            }
        }

        boolean exhausted = raw.budgetExhausted() && applied == 0;
        return new Result(applied, raw.lastAssistantText(), exhausted);
    }

    private static int countApplied(ReadmeSyncAgentRunner.Result raw) {
        return (int) raw.toolInvocations().stream()
                .filter(i -> i.result() != null && i.result().startsWith("OK"))
                .count();
    }
}
