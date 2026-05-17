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
 *   <li><b>NATIVE</b> &mdash; appends a short hint that tools are exposed via
 *       the provider's function-calling API and skips the inline MCP block
 *       (the catalog is forwarded through the API instead). The native hint
 *       still comes from a static template under
 *       {@code /prompts/native/{kind}-tool-protocol.md} because it does not
 *       enumerate tools.</li>
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

    /** Identifies which template family to load. */
    public enum PromptKind {
        ISSUE_AGENT("issue-agent"),
        WRITER_AGENT("writer-agent");

        private final String fileBase;
        PromptKind(String fileBase) { this.fileBase = fileBase; }
        public String fileBase() { return fileBase; }
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
                ? loadNativeTemplate(kind)
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
        return sb.toString();
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
            case ISSUE_AGENT  -> legacyRenderer.renderIssueAgent(toolCatalog, allowedBuiltinTools);
            case WRITER_AGENT -> legacyRenderer.renderWriterAgent(toolCatalog, allowedBuiltinTools);
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

