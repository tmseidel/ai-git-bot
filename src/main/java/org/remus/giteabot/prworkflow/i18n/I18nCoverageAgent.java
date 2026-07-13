package org.remus.giteabot.prworkflow.i18n;

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
import java.util.Locale;
import java.util.Set;

/**
 * The single agent stage of the i18n-coverage workflow. Given the PR diff, the
 * configured baseline locale and a per-locale coverage report, it drafts the
 * missing translations and removes stale keys directly in the repository
 * checkout via the {@code i18n-write} / {@code i18n-delete} tools.
 *
 * <p>Mirrors {@code ReadmeSyncAgent} — including the "narrated tool call"
 * recovery for models that describe their tool calls as plain text instead of
 * emitting native {@code tool_use} blocks — but operates on locale files rather
 * than documentation.</p>
 */
@Slf4j
@Component
public class I18nCoverageAgent {

    public static final int BASELINE_ROUNDS = 6;
    public static final int DEFAULT_MAX_TOKENS = 8_192;

    private static final Set<String> ALLOWED_TOOLS = Set.of("i18n-write", "i18n-delete");

    private final ToolCatalog toolCatalog;
    private final I18nToolExecutor toolExecutor;
    private final SystemPromptAssembler promptAssembler;

    public I18nCoverageAgent(ToolCatalog toolCatalog, I18nToolExecutor toolExecutor) {
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
        this.promptAssembler = new SystemPromptAssembler();
    }

    /**
     * @param changesApplied     number of successful {@code i18n-write} /
     *                           {@code i18n-delete} calls
     * @param finalAssistantText the model's final text turn (diagnostics)
     * @param budgetExhausted    whether the agent ran out of rounds
     */
    public record Result(int changesApplied, String finalAssistantText, boolean budgetExhausted) {
    }

    /**
     * Runs the i18n-coverage agent.
     *
     * @param aiClient       the resolved AI client for the bot
     * @param toolContext    checkout + include-patterns binding for the i18n tools
     * @param userMessage    the kickoff message (PR metadata + diff + coverage report)
     * @param systemPrompt   operator-edited prompts (may be {@code null} → built-in default)
     * @param includePatterns the configured i18n include patterns (for the protocol suffix)
     * @param baselineLocale the configured baseline locale (for the protocol suffix)
     * @param maxToolRounds  operator-tunable cap on the number of explore/write rounds
     */
    public Result generate(AiClient aiClient,
                           I18nCoverageToolContext toolContext,
                           String userMessage,
                           SystemPrompt systemPrompt,
                           List<String> includePatterns,
                           String baselineLocale,
                           int maxToolRounds) {
        if (aiClient == null) {
            return new Result(0, "AI client unavailable", true);
        }
        List<ToolDescriptor> descriptors = toolCatalog.nativeDescriptors(
                ToolCatalog.Role.PR_WORKFLOW, null, ALLOWED_TOOLS);

        ToolingMode mode = ToolingMode.resolve(ToolingMode.NATIVE,
                aiClient.supportsNativeTools(), !descriptors.isEmpty());
        String systemPromptText = promptAssembler.assemble(
                I18nCoveragePromptLibrary.systemPrompt(systemPrompt, includePatterns, baselineLocale),
                toolCatalog, ALLOWED_TOOLS, null, mode,
                SystemPromptAssembler.PromptKind.E2E_AGENT);

        int maxRounds = Math.max(BASELINE_ROUNDS, Math.min(maxToolRounds, 30) + 2);
        I18nCoverageAgentRunner runner = new I18nCoverageAgentRunner(
                aiClient, toolExecutor, toolContext, descriptors,
                systemPromptText, maxRounds, DEFAULT_MAX_TOKENS, "i18n-coverage");

        I18nCoverageAgentRunner.Result raw = runner.run(userMessage);
        int applied = countApplied(raw);

        // Recovery for the "narrated tool call" failure mode (see ReadmeSyncAgent).
        if (applied == 0) {
            int recovered = 0;
            for (NarratedToolCallParser.Call call : NarratedToolCallParser.parse(raw.lastAssistantText())) {
                String name = call.name() == null ? "" : call.name().toLowerCase(Locale.ROOT);
                if (!ALLOWED_TOOLS.contains(name)) {
                    continue;
                }
                String result = toolExecutor.execute(name, call.args(), toolContext);
                if (result != null && result.startsWith("OK")) {
                    recovered++;
                } else {
                    log.warn("I18nCoverageAgent: recovered narrated `{}` for path={} failed: {}",
                            name, call.args().get("path"), result);
                }
            }
            if (recovered > 0) {
                log.warn("I18nCoverageAgent: recovered {} narrated i18n tool call(s) from assistant text "
                        + "after native tool_use produced 0 calls", recovered);
                applied = recovered;
            }
        }

        boolean exhausted = raw.budgetExhausted() && applied == 0;
        return new Result(applied, raw.lastAssistantText(), exhausted);
    }

    private static int countApplied(I18nCoverageAgentRunner.Result raw) {
        return (int) raw.toolInvocations().stream()
                .filter(i -> i.result() != null && i.result().startsWith("OK"))
                .count();
    }
}
