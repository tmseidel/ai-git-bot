package org.remus.giteabot.systemsettings;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the bot built-in tool catalog for the admin UI and persists the
 * per-configuration selection. Mirrors {@link McpToolSelectionService} for
 * MCP tools.
 *
 * <p>Tools that are no longer registered in the {@link BuiltinToolRegistry}
 * (e.g. removed from {@code ToolCatalog} in a release) are kept in the
 * configuration so manual selections survive upgrades, but they are surfaced
 * separately in {@link #loadAvailableTools(Long)} with their persisted kind
 * snapshot.</p>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BotToolSelectionService {

    private final BotToolConfigurationRepository configurationRepository;
    private final BotToolSelectionRepository selectionRepository;
    private final BuiltinToolRegistry builtinToolRegistry;

    @Transactional(readOnly = true)
    public List<BotToolSelectionRow> loadAvailableTools(Long configurationId) {
        requireConfiguration(configurationId);
        Set<String> selected = selectedToolNameSet(configurationId);

        Map<String, BotToolSelectionRow> rows = new LinkedHashMap<>();
        for (BuiltinToolRegistry.BuiltinTool tool : builtinToolRegistry.builtinTools()) {
            rows.put(tool.name(), new BotToolSelectionRow(
                    tool.name(),
                    tool.kind().name(),
                    tool.description(),
                    selected.contains(tool.name())));
        }
        // Persisted-but-unknown tools — keep them visible so admins can drop them.
        for (BotToolSelection persisted : selectionRepository.findByConfigurationId(configurationId)) {
            String name = persisted.getToolName();
            if (!rows.containsKey(name)) {
                rows.put(name, new BotToolSelectionRow(
                        name,
                        persisted.getToolKind() != null ? persisted.getToolKind() : "UNKNOWN",
                        "",
                        true));
            }
        }
        return List.copyOf(rows.values());
    }

    @Transactional(readOnly = true)
    public List<BotToolSelectionRow> loadSelectedTools(Long configurationId) {
        return selectionRepository.findByConfigurationIdOrderByToolKindAscToolNameAsc(configurationId).stream()
                .map(tool -> new BotToolSelectionRow(
                        tool.getToolName(),
                        tool.getToolKind() != null ? tool.getToolKind() : "UNKNOWN",
                        "",
                        true))
                .toList();
    }

    public void saveSelection(Long configurationId, List<String> selectedToolNames) {
        BotToolConfiguration configuration = requireConfiguration(configurationId);
        if (configuration.isDefaultEntry()) {
            throw new IllegalStateException("The default tool configuration is managed automatically and cannot be edited");
        }

        Map<String, BuiltinToolRegistry.BuiltinTool> registry = new LinkedHashMap<>();
        for (BuiltinToolRegistry.BuiltinTool tool : builtinToolRegistry.builtinTools()) {
            registry.put(tool.name(), tool);
        }
        Map<String, BotToolSelection> existing = new LinkedHashMap<>();
        for (BotToolSelection row : selectionRepository.findByConfigurationId(configurationId)) {
            existing.put(row.getToolName(), row);
        }

        Set<String> requested = new LinkedHashSet<>();
        if (selectedToolNames != null) {
            for (String raw : selectedToolNames) {
                if (raw == null) {
                    continue;
                }
                String normalized = raw.trim().toLowerCase();
                if (!normalized.isEmpty()) {
                    requested.add(normalized);
                }
            }
        }

        List<BotToolSelection> replacement = new ArrayList<>();
        for (String name : requested) {
            BotToolSelection row = new BotToolSelection();
            row.setConfiguration(configuration);
            row.setToolName(name);
            BuiltinToolRegistry.BuiltinTool known = registry.get(name);
            if (known != null) {
                row.setToolKind(known.kind().name());
            } else if (existing.containsKey(name) && existing.get(name).getToolKind() != null) {
                row.setToolKind(existing.get(name).getToolKind());
            } else {
                // Unknown tool — silently drop instead of poisoning the table.
                continue;
            }
            replacement.add(row);
        }

        selectionRepository.deleteByConfigurationId(configurationId);
        // Force the DELETE to hit the DB before the inserts; otherwise
        // Hibernate's action-queue ordering can run INSERTs first and trip
        // the (configuration_id, tool_key) UNIQUE index when the new
        // selection re-includes a previously-persisted tool key.
        selectionRepository.flush();
        selectionRepository.saveAll(replacement);
    }

    /**
     * Whitelist of built-in tool identifiers (lower-case) allowed by the given
     * configuration. The set is used at runtime to filter prompt exposure and
     * to gate tool execution.
     *
     * <p>A {@code null} configuration (defensive fallback for pre-migration
     * data) yields an empty set, i.e. <em>no</em> built-in tool is allowed.
     * Callers in the runtime path are expected to never pass {@code null}
     * once the bot FK is mandatory.</p>
     */
    @Transactional(readOnly = true)
    public Set<String> allowedBuiltinTools(BotToolConfiguration configuration) {
        if (configuration == null || configuration.getId() == null) {
            return Set.of();
        }
        return selectedToolNameSet(configuration.getId());
    }

    private Set<String> selectedToolNameSet(Long configurationId) {
        return selectionRepository.findByConfigurationId(configurationId).stream()
                .map(BotToolSelection::getToolName)
                .filter(Objects::nonNull)
                .map(name -> name.trim().toLowerCase())
                .filter(name -> !name.isEmpty())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private BotToolConfiguration requireConfiguration(Long id) {
        return configurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tool configuration not found"));
    }
}
