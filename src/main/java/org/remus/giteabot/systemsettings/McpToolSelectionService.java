package org.remus.giteabot.systemsettings;

import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class McpToolSelectionService {

    private final McpConfigurationRepository mcpConfigurationRepository;
    private final McpSelectedToolRepository mcpSelectedToolRepository;
    private final McpOrchestrationService mcpOrchestrationService;

    public McpToolSelectionService(McpConfigurationRepository mcpConfigurationRepository,
                                   McpSelectedToolRepository mcpSelectedToolRepository,
                                   McpOrchestrationService mcpOrchestrationService) {
        this.mcpConfigurationRepository = mcpConfigurationRepository;
        this.mcpSelectedToolRepository = mcpSelectedToolRepository;
        this.mcpOrchestrationService = mcpOrchestrationService;
    }

    @Transactional(readOnly = true)
    public List<McpToolSelectionRow> loadAvailableTools(Long mcpConfigurationId) {
        McpConfiguration configuration = requireConfiguration(mcpConfigurationId);
        McpToolCatalog catalog = mcpOrchestrationService.discoverTools(configuration);
        Set<String> selectedQualifiedNames = selectedQualifiedToolNameSet(mcpConfigurationId);
        return catalog.tools().stream()
                .sorted(Comparator
                        .comparing(McpToolDefinition::serverName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(McpToolDefinition::name, String.CASE_INSENSITIVE_ORDER))
                .map(tool -> new McpToolSelectionRow(
                        tool.qualifiedName(),
                        tool.serverName(),
                        tool.name(),
                        tool.title(),
                        tool.description(),
                        selectedQualifiedNames.contains(tool.qualifiedName())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<McpToolSelectionRow> loadSelectedTools(Long mcpConfigurationId) {
        return mcpSelectedToolRepository.findByMcpConfigurationIdOrderByServerNameAscToolNameAsc(mcpConfigurationId).stream()
                .map(tool -> new McpToolSelectionRow(
                        tool.getQualifiedName(),
                        tool.getServerName(),
                        tool.getToolName(),
                        tool.getTitle(),
                        tool.getDescription(),
                        true
                ))
                .toList();
    }

    public void saveSelection(Long mcpConfigurationId, List<String> selectedQualifiedNames) {
        McpConfiguration configuration = requireConfiguration(mcpConfigurationId);
        Set<String> selected = new LinkedHashSet<>(selectedQualifiedNames == null ? List.of() : selectedQualifiedNames);

        Map<String, McpSelectedTool> existingByQualifiedName = mcpSelectedToolRepository.findByMcpConfigurationId(mcpConfigurationId)
                .stream()
                .collect(HashMap::new, (map, entry) -> map.put(entry.getQualifiedName(), entry), HashMap::putAll);

        Map<String, McpToolDefinition> discoveredByQualifiedName = mcpOrchestrationService.discoverTools(configuration).tools().stream()
                .collect(HashMap::new, (map, entry) -> map.put(entry.qualifiedName(), entry), HashMap::putAll);

        List<McpSelectedTool> replacement = new ArrayList<>();
        for (String qualifiedName : selected) {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                continue;
            }
            McpToolDefinition discovered = discoveredByQualifiedName.get(qualifiedName);
            McpSelectedTool existing = existingByQualifiedName.get(qualifiedName);
            McpSelectedTool selectedTool = new McpSelectedTool();
            selectedTool.setMcpConfiguration(configuration);
            selectedTool.setQualifiedName(qualifiedName);
            if (discovered != null) {
                selectedTool.setServerName(discovered.serverName());
                selectedTool.setToolName(discovered.name());
                selectedTool.setTitle(discovered.title());
                selectedTool.setDescription(discovered.description());
            } else if (existing != null) {
                selectedTool.setServerName(existing.getServerName());
                selectedTool.setToolName(existing.getToolName());
                selectedTool.setTitle(existing.getTitle());
                selectedTool.setDescription(existing.getDescription());
            } else {
                // Ignore unknown entries that are neither currently discoverable nor persisted.
                continue;
            }
            replacement.add(selectedTool);
        }

        mcpSelectedToolRepository.deleteByMcpConfigurationId(mcpConfigurationId);
        mcpSelectedToolRepository.saveAll(replacement);
    }

    @Transactional(readOnly = true)
    public McpToolCatalog filterCatalogForPrompt(McpConfiguration configuration, McpToolCatalog discoveredCatalog) {
        if (configuration == null || discoveredCatalog == null || !discoveredCatalog.hasTools()) {
            return discoveredCatalog == null ? McpToolCatalog.empty() : discoveredCatalog;
        }
        Set<String> selected = selectedQualifiedToolNameSet(configuration.getId());
        if (selected.isEmpty()) {
            return McpToolCatalog.empty();
        }
        return discoveredCatalog.filterByQualifiedNames(selected);
    }

    @Transactional(readOnly = true)
    public Set<String> selectedQualifiedToolNameSet(Long mcpConfigurationId) {
        if (mcpConfigurationId == null) {
            return Set.of();
        }
        return mcpSelectedToolRepository.findByMcpConfigurationId(mcpConfigurationId).stream()
                .map(McpSelectedTool::getQualifiedName)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private McpConfiguration requireConfiguration(Long id) {
        return mcpConfigurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
    }
}

