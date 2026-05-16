package org.remus.giteabot.agent.shared;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolPromptRenderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Assembles the final system prompt sent to the AI client for an agent run.
 *
 * <p>The DB-stored / filesystem-stored base prompt only carries the agent's
 * mode-neutral role description (see Flyway V13). The transport-specific
 * tool-use guidance lives in resource templates under
 * {@code /prompts/{legacy|native}/{kind}-tool-protocol.md} and is appended
 * here at runtime depending on the resolved {@link ToolingMode}:</p>
 *
 * <ul>
 *   <li><b>LEGACY</b> &mdash; appends the JSON-envelope (`runTools` /
 *       `requestTools` / `requestFiles`) protocol template <em>and</em> the
 *       MCP tool catalog inline.</li>
 *   <li><b>NATIVE</b> &mdash; appends a short hint that tools are exposed via
 *       the provider's function-calling API and skips the inline MCP block
 *       (the catalog is forwarded through the API instead).</li>
 * </ul>
 *
 * <p>For backwards compatibility the assembler strips any stray
 * {@code <!-- BEGIN_LEGACY_TOOL_PROTOCOL --> ... <!-- END_LEGACY_TOOL_PROTOCOL -->}
 * block from the base prompt before appending the template, so deployments
 * where V12 has run but V13 has not (or where an operator restored the
 * wrapped block manually) still produce a clean, non-contradictory prompt.</p>
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

    /** Cache loaded resource templates so we hit the classpath only once per JVM. */
    private static final Map<String, String> TEMPLATE_CACHE = new ConcurrentHashMap<>();

    private final McpToolPromptRenderer mcpToolPromptRenderer;

    public SystemPromptAssembler() {
        this(new McpToolPromptRenderer());
    }

    public SystemPromptAssembler(McpToolPromptRenderer mcpToolPromptRenderer) {
        this.mcpToolPromptRenderer = mcpToolPromptRenderer;
    }

    /**
     * Build the system prompt for the given mode and prompt kind.
     */
    public String assemble(String basePrompt, McpToolCatalog mcpToolCatalog,
                           ToolingMode mode, PromptKind kind) {
        if (basePrompt == null) {
            return "";
        }
        String stripped = stripLegacyBlock(basePrompt).stripTrailing();
        String template = loadTemplate(mode, kind);
        StringBuilder sb = new StringBuilder(stripped.length() + template.length() + 256);
        sb.append(stripped);
        if (!template.isEmpty()) {
            if (!stripped.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(template);
        }
        if (mode != ToolingMode.NATIVE) {
            sb.append(mcpToolPromptRenderer.render(mcpToolCatalog));
        }
        return sb.toString();
    }

    /**
     * Backwards-compatible 3-arg overload that defaults to {@link PromptKind#ISSUE_AGENT}.
     *
     * @deprecated prefer {@link #assemble(String, McpToolCatalog, ToolingMode, PromptKind)}.
     */
    @Deprecated
    public String assemble(String basePrompt, McpToolCatalog mcpToolCatalog, ToolingMode mode) {
        return assemble(basePrompt, mcpToolCatalog, mode, PromptKind.ISSUE_AGENT);
    }

    private String stripLegacyBlock(String basePrompt) {
        if (basePrompt.contains(BEGIN_MARKER) && basePrompt.contains(END_MARKER)) {
            return LEGACY_BLOCK.matcher(basePrompt).replaceAll("\n\n");
        }
        return basePrompt;
    }

    private String loadTemplate(ToolingMode mode, PromptKind kind) {
        String resourcePath = "/prompts/" + (mode == ToolingMode.NATIVE ? "native" : "legacy")
                + "/" + kind.fileBase() + "-tool-protocol.md";
        return TEMPLATE_CACHE.computeIfAbsent(resourcePath, this::readResource);
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

    /** Visible for tests that want a clean cache state. */
    static void clearTemplateCacheForTests() {
        TEMPLATE_CACHE.clear();
    }
}
