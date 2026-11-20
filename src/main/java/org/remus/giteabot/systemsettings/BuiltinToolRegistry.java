package org.remus.giteabot.systemsettings;

import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.tools.ToolKind;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the built-in tools currently registered in
 * {@link ToolCatalog} into a flat, stable list with display metadata.
 *
 * <p>The catalog itself organises tools by purpose (file/context/validation/
 * writer-repository) and by role; for the admin UI and the
 * {@link BotToolConfiguration} whitelist we need a single flat view with a
 * stable lower-case identifier. The order of {@link #builtinTools()} is the
 * one used in the editor: file → context → validation → repository → agent-control.</p>
 *
 * <p>The operator-facing description is resolved from the message bundle
 * ({@code tool.<name>.description}) and falls back to the English native-API
 * description from {@link ToolCatalog} when no translation exists. The
 * descriptions sent to the LLM are untouched.</p>
 */
@Component
public class BuiltinToolRegistry {

    private final ToolCatalog toolCatalog;
    private final MessageSource messageSource;

    public BuiltinToolRegistry(ToolCatalog toolCatalog, MessageSource messageSource) {
        this.toolCatalog = toolCatalog;
        this.messageSource = messageSource;
    }

    public List<BuiltinTool> builtinTools() {
        // LinkedHashMap so that duplicates between groups are filtered to the
        // first occurrence while preserving deterministic display order.
        Map<String, BuiltinTool> byName = new LinkedHashMap<>();
        addAll(byName, toolCatalog.fileToolNames(),             ToolKind.FILE,       ToolCatalog.Role.CODING);
        addAll(byName, toolCatalog.contextToolNames(),          ToolKind.CONTEXT,    ToolCatalog.Role.CODING);
        addAll(byName, toolCatalog.validationToolNames(),       ToolKind.VALIDATION, ToolCatalog.Role.CODING);
        addAll(byName, toolCatalog.writerRepositoryToolNames(), ToolKind.REPOSITORY, ToolCatalog.Role.WRITER);
        // Without this line the tool is registered but never appears in the admin
        // tool-configuration UI, so no bot can put it on its whitelist.
        addAll(byName, toolCatalog.agentControlToolNames(),      ToolKind.AGENT_CONTROL, ToolCatalog.Role.CODING);
        return List.copyOf(byName.values());
    }

    private void addAll(Map<String, BuiltinTool> sink, List<String> names,
                        ToolKind kind, ToolCatalog.Role role) {
        for (String name : names) {
            String normalized = name == null ? "" : name.trim().toLowerCase();
            if (normalized.isEmpty() || sink.containsKey(normalized)) {
                continue;
            }
            String english = toolCatalog.describeFor(role, normalized).orElse("");
            String description;
            try {
                description = messageSource.getMessage(
                        "tool." + normalized + ".description", null, LocaleContextHolder.getLocale());
            } catch (org.springframework.context.NoSuchMessageException e) {
                description = english;
            }
            String missing = toolCatalog.missingConfiguration(normalized).orElse(null);
            if (missing != null) {
                description = description + " " + messageSource.getMessage("tool.notConfigured",
                        new Object[]{missing}, "Disabled until " + missing + " is configured.",
                        LocaleContextHolder.getLocale());
            }
            sink.put(normalized, new BuiltinTool(normalized, kind, description));
        }
    }

    public record BuiltinTool(String name, ToolKind kind, String description) { }
}
