package org.remus.giteabot.agent.shared;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolPromptRenderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Assembles the final system prompt sent to the AI client for an agent run.
 *
 * <p>The DB-stored / filesystem-stored base prompt only carries the agent's
 * mode-neutral role description. The transport-specific tool-use guidance is
 * appended here at runtime depending on the resolved {@link ToolingMode}:</p>
 *
 * <ul>
 *   <li><b>LEGACY</b> &mdash; appends the JSON-envelope (`runTools` /
 *       `requestTools` / `requestFiles`) protocol <em>rendered dynamically</em>
 *       from the bot's {@link ToolCatalog} filtered by its built-in tool
 *       whitelist (see {@link LegacyToolProtocolRenderer}), plus the MCP tool
 *       catalog inline.</li>
 *   <li><b>NATIVE</b> &mdash; appends the protocol for the given
 *       {@link PromptKind} and skips the inline MCP block (the catalog is
 *       forwarded through the API instead). One template per agent stage lives
 *       under {@code /prompts/native/{kind}-tool-protocol.md}; each carries a
 *       {@link #TOOL_STRATEGY_MARKER} that is replaced with the usage hints of
 *       the tools that stage may actually call.</li>
 * </ul>
 *
 * <p>Any stray {@code <!-- BEGIN_LEGACY_TOOL_PROTOCOL --> ... <!-- END_LEGACY_TOOL_PROTOCOL -->}
 * block left over from older base prompts is stripped before appending the
 * generated protocol so deployments where Flyway has wrapped (but not removed)
 * the legacy block still produce a clean, non-contradictory prompt.</p>
 */
@Slf4j
public class SystemPromptAssembler {

    public static final String BEGIN_MARKER = "<!-- BEGIN_LEGACY_TOOL_PROTOCOL -->";
    public static final String END_MARKER = "<!-- END_LEGACY_TOOL_PROTOCOL -->";

    /**
     * Identifies which agent stage the system prompt is assembled for. Each constant names its own
     * native-protocol resource ({@code /prompts/native/{fileBase}-tool-protocol.md}) and the
     * {@link ToolCatalog.Role} whose tools that stage's agent may call, so the tool-selection
     * strategy is rendered from exactly the tools the stage can reach — and a stage whose agent has
     * none of them simply gets no strategy section.
     */
    public enum PromptKind {
        ISSUE_AGENT("issue-agent", ToolCatalog.Role.CODING),
        WRITER_AGENT("writer-agent", ToolCatalog.Role.WRITER),
        TRIAGE_AGENT("triage-agent", ToolCatalog.Role.WRITER),
        E2E_TEST_AUTHOR("e2e-test-author", ToolCatalog.Role.PR_WORKFLOW),
        E2E_TEST_RUNNER("e2e-test-runner", ToolCatalog.Role.PR_WORKFLOW),
        README_SYNC_AGENT("readme-sync", ToolCatalog.Role.PR_WORKFLOW),
        I18N_COVERAGE_AGENT("i18n-coverage", ToolCatalog.Role.PR_WORKFLOW),
        UNIT_TEST_AUTHOR_AGENT("unit-test-author", ToolCatalog.Role.PR_WORKFLOW),
        /** Structured agentic review: findings/assessment are recorded through the review tools. */
        AGENT_REVIEW_AGENT("agentic-review", ToolCatalog.Role.WRITER),
        /** Clarification/Q&A about a reviewed pull request: a plain answer, no review tools. */
        AGENT_REVIEW_QA("agentic-review-qa", ToolCatalog.Role.WRITER);

        private final String fileBase;
        private final ToolCatalog.Role role;

        PromptKind(String fileBase, ToolCatalog.Role role) {
            this.fileBase = fileBase;
            this.role = role;
        }

        public String fileBase() { return fileBase; }

        /** The catalogue role whose tools this stage's agent may call. */
        public ToolCatalog.Role role() { return role; }
    }

    /** Pattern matching a legacy block, including markers and surrounding whitespace. */
    private static final Pattern LEGACY_BLOCK = Pattern.compile(
            "(?s)\\s*" + Pattern.quote(BEGIN_MARKER) + ".*?" + Pattern.quote(END_MARKER) + "\\s*");

    /** Cache loaded native resource templates so we hit the classpath only once per JVM. */
    private static final Map<String, String> NATIVE_TEMPLATE_CACHE = new ConcurrentHashMap<>();

    private final McpToolPromptRenderer mcpToolPromptRenderer;
    private final LegacyToolProtocolRenderer legacyRenderer;

    public SystemPromptAssembler() {
        this(new McpToolPromptRenderer());
    }

    public SystemPromptAssembler(McpToolPromptRenderer mcpToolPromptRenderer) {
        this.mcpToolPromptRenderer = mcpToolPromptRenderer;
        this.legacyRenderer = new LegacyToolProtocolRenderer();
    }

    /**
     * Build the system prompt for the given mode and prompt kind.
     *
     * @param basePrompt           the operator-edited role description (may be {@code null})
     * @param toolCatalog          the catalog used to render the legacy tool protocol;
     *                             must be non-null in LEGACY mode
     * @param allowedBuiltinTools  whitelist of built-in tool names the bot may invoke;
     *                             a {@code null} set means "no whitelist configured —
     *                             render every catalog tool" (test paths only)
     * @param mcpToolCatalog       the MCP tool catalog to inline in LEGACY mode
     * @param mode                 LEGACY or NATIVE
     * @param kind                 ISSUE_AGENT or WRITER_AGENT
     */
    public String assemble(String basePrompt,
                           ToolCatalog toolCatalog,
                           Set<String> allowedBuiltinTools,
                           McpToolCatalog mcpToolCatalog,
                           ToolingMode mode,
                           PromptKind kind) {
        if (basePrompt == null) {
            return "";
        }
        String stripped = stripLegacyBlock(basePrompt).stripTrailing();
        StringBuilder sb = new StringBuilder(stripped.length() + 4096);
        sb.append(stripped);

        String protocol = mode == ToolingMode.NATIVE
                ? nativeProtocol(toolCatalog, allowedBuiltinTools, kind)
                : renderLegacyProtocol(toolCatalog, allowedBuiltinTools, kind);
        if (!protocol.isEmpty()) {
            if (!stripped.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(protocol);
        }
        if (mode != ToolingMode.NATIVE) {
            sb.append(mcpToolPromptRenderer.render(mcpToolCatalog));
        }
        if (offersCodeExecution(allowedBuiltinTools)) {
            sb.append("\n\n").append(CODE_EXECUTION_GUIDANCE);
        }
        return sb.toString();
    }

    /** Where a native template wants the tool-selection strategy rendered in. */
    static final String TOOL_STRATEGY_MARKER = "{{TOOL_STRATEGY}}";

    /**
     * The native block: this stage's template with the tool-selection strategy rendered in. A template
     * that carries {@link #TOOL_STRATEGY_MARKER} gets the strategy there; one that does not (written
     * before the marker existed) gets it appended, so a template can never silently lose the strategy.
     */
    private String nativeProtocol(ToolCatalog toolCatalog, Set<String> allowedBuiltinTools,
                                  PromptKind kind) {
        String template = loadNativeTemplate(kind);
        String strategy = renderToolStrategy(toolCatalog, allowedBuiltinTools, kind);
        if (strategy.isEmpty()) {
            return template;
        }
        if (template.contains(TOOL_STRATEGY_MARKER)) {
            return template.replace(TOOL_STRATEGY_MARKER, strategy.stripTrailing());
        }
        return template.stripTrailing() + "\n\n### Tool Selection Strategy\n" + strategy;
    }

    /**
     * The strategy bullets for one stage: the tools its {@link PromptKind#role()} exposes, filtered by
     * the bot's whitelist, that carry a usage hint — in the catalogue's display order, so the text
     * follows the definitions rather than a list kept here. The validation rule is the one line that is
     * policy rather than a hint; it only applies to the coding role, whose surface is the only one with
     * validation tools, and it names the configured validators that survived the whitelist. A
     * {@code null} whitelist means every tool, as everywhere else in this class.
     */
    private String renderToolStrategy(ToolCatalog toolCatalog, Set<String> allowedBuiltinTools,
                                      PromptKind kind) {
        StringBuilder out = new StringBuilder();
        for (String name : toolCatalog.builtinToolNames(kind.role())) {
            if (!offers(allowedBuiltinTools, name)) {
                continue;
            }
            toolCatalog.usageHint(name)
                    .ifPresent(hint -> out.append("- ").append(hint).append('\n'));
        }
        if (kind.role() == ToolCatalog.Role.CODING) {
            List<String> validators = toolCatalog.validationToolNames(allowedBuiltinTools);
            if (!validators.isEmpty()) {
                out.append("- **After a change, validation is mandatory**: run one of ")
                        .append(validators.stream().map(name -> "`" + name + "`")
                                .collect(Collectors.joining(", ")))
                        .append('.')
                        .append('\n');
            }
        }
        return out.toString();
    }

    private static boolean offers(Set<String> allowedBuiltinTools, String tool) {
        return allowedBuiltinTools == null || allowedBuiltinTools.contains(tool);
    }

    /** The tool this guidance is about; named here because the assembler sits above the catalog. */
    private static final String CODE_EXECUTION_TOOL = "execute-code";

    /**
     * Appended only when the bot has {@code execute-code} on its whitelist.
     *
     * <p>The tool's own description carries its API. This carries when to reach for it, which is a
     * strategy question rather than an interface one, and it is paid for on every round of every run
     * that has the tool — so it stays short and it does not sell the tool.</p>
     *
     * <p>Deliberately capability-neutral: "the tools you can see" is the program's actual surface on
     * a read-only run and on a writable one alike, so this text never promises a reach the run's role
     * does not have.</p>
     */
    private static final String CODE_EXECUTION_GUIDANCE = """
            ## Running code

            The `execute-code` tool runs a Python program that can call the tools you can see. Reach for it when the work is iterative or would otherwise take several tool calls: gather and filter inside the program, and only what it prints enters the conversation.

            A tool you cannot call is one the program cannot call either, and its output is size-capped — so print what matters.""";

    /**
     * Whether the bot has the code-execution tool selected. A {@code null} set means "no whitelist
     * configured — render every catalog tool" (see {@link #assemble}), which includes this one.
     */
    private static boolean offersCodeExecution(Set<String> allowedBuiltinTools) {
        return allowedBuiltinTools == null || allowedBuiltinTools.contains(CODE_EXECUTION_TOOL);
    }

    private String renderLegacyProtocol(ToolCatalog toolCatalog,
                                        Set<String> allowedBuiltinTools,
                                        PromptKind kind) {
        if (toolCatalog == null) {
            log.warn("LEGACY mode requested without a ToolCatalog — emitting empty protocol; "
                    + "tool usage hints will be missing from the system prompt");
            return "";
        }
        return switch (kind) {
            case ISSUE_AGENT -> legacyRenderer.renderIssueAgent(toolCatalog, allowedBuiltinTools);
            case AGENT_REVIEW_AGENT -> legacyRenderer.renderReviewAgent(toolCatalog, allowedBuiltinTools);
            case WRITER_AGENT, TRIAGE_AGENT, AGENT_REVIEW_QA ->
                    legacyRenderer.renderWriterAgent(toolCatalog, allowedBuiltinTools);
            case E2E_TEST_AUTHOR, E2E_TEST_RUNNER, README_SYNC_AGENT,
                 I18N_COVERAGE_AGENT, UNIT_TEST_AUTHOR_AGENT ->
                    legacyRenderer.renderE2eAgent(toolCatalog, allowedBuiltinTools);
        };
    }

    private String stripLegacyBlock(String basePrompt) {
        if (basePrompt.contains(BEGIN_MARKER) && basePrompt.contains(END_MARKER)) {
            return LEGACY_BLOCK.matcher(basePrompt).replaceAll("\n\n");
        }
        return basePrompt;
    }

    private String loadNativeTemplate(PromptKind kind) {
        String resourcePath = "/prompts/native/" + kind.fileBase() + "-tool-protocol.md";
        return NATIVE_TEMPLATE_CACHE.computeIfAbsent(resourcePath, this::readResource);
    }

    private String readResource(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("System-prompt template not found on classpath: {}", resourcePath);
                return "";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n")).stripTrailing();
            }
        } catch (IOException e) {
            log.warn("Failed to read system-prompt template {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }
}

