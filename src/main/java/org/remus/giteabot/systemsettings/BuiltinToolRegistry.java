package org.remus.giteabot.systemsettings;

import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.tools.ToolKind;
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
 * one used in the editor: file → context → validation → repository.</p>
 */
@Component
public class BuiltinToolRegistry {

    private final ToolCatalog toolCatalog;

    public BuiltinToolRegistry(ToolCatalog toolCatalog) {
        this.toolCatalog = toolCatalog;
    }

    public List<BuiltinTool> builtinTools() {
        // LinkedHashMap so that duplicates between groups are filtered to the
        // first occurrence while preserving deterministic display order.
        Map<String, BuiltinTool> byName = new LinkedHashMap<>();
        addAll(byName, toolCatalog.fileToolNames(),             ToolKind.FILE,       ToolCatalog.Role.CODING);
        addAll(byName, toolCatalog.contextToolNames(),          ToolKind.CONTEXT,    ToolCatalog.Role.CODING);
        addAll(byName, toolCatalog.validationToolNames(),       ToolKind.VALIDATION, ToolCatalog.Role.CODING);
        addAll(byName, toolCatalog.writerRepositoryToolNames(), ToolKind.REPOSITORY, ToolCatalog.Role.WRITER);
        return List.copyOf(byName.values());
    }

    private void addAll(Map<String, BuiltinTool> sink, List<String> names,
                        ToolKind kind, ToolCatalog.Role role) {
        for (String name : names) {
            String normalized = name == null ? "" : name.trim().toLowerCase();
            if (normalized.isEmpty() || sink.containsKey(normalized)) {
                continue;
            }
            String description = toolCatalog.describeFor(role, normalized).orElse("");
            sink.put(normalized, new BuiltinTool(normalized, kind, description));
        }
    }

    public record BuiltinTool(String name, ToolKind kind, String description) { }
}
