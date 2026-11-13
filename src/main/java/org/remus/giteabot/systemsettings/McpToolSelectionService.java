package org.remus.giteabot.systemsettings;

import lombok.RequiredArgsConstructor;

import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class McpToolSelectionService {

    private final McpConfigurationRepository mcpConfigurationRepository;
    private final McpSelectedToolRepository mcpSelectedToolRepository;
    private final McpOrchestrationService mcpOrchestrationService;
    private final McpConfigurationService mcpConfigurationService;

    public List<McpToolSelectionRow> loadAvailableTools(Long mcpConfigurationId) {
        return loadSelectionSnapshot(mcpConfigurationId).tools();
    }

    /** Captures displayed rows and server-held approval evidence from the same configuration/catalog read. */
    public SelectionSnapshot loadSelectionSnapshot(Long mcpConfigurationId) {
        McpConfiguration observed = captureConfiguration(mcpConfigurationId);
        McpToolCatalog catalog = mcpOrchestrationService.discoverTools(observed);
        McpConfiguration configuration = lockObservedConfiguration(observed);
        List<McpSelectedTool> selected = mcpConfigurationService.refreshSelectedTools(mcpConfigurationId);
        Map<String, ReviewReadOnlyApproval> candidates = approvalCandidates(configuration, catalog);
        Set<String> approved = validatedApprovals(selected, candidates).stream()
                .map(ReviewReadOnlyApproval::qualifiedName).collect(Collectors.toSet());
        Set<String> selectedQualifiedNames = selected.stream().map(McpSelectedTool::getQualifiedName).collect(Collectors.toSet());
        List<McpToolSelectionRow> rows = catalog.tools().stream()
                .sorted(Comparator
                        .comparing(McpToolDefinition::serverName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(McpToolDefinition::name, String.CASE_INSENSITIVE_ORDER))
                .map(tool -> new McpToolSelectionRow(
                        tool.qualifiedName(),
                        tool.serverName(),
                        tool.name(),
                        tool.title(),
                        tool.description(),
                        selectedQualifiedNames.contains(tool.qualifiedName()),
                        approved.contains(tool.qualifiedName())
                ))
                .toList();
        return new SelectionSnapshot(rows, candidates);
    }

    /** Persisted selection details only; use readReviewReadOnlyApprovals for catalog-validated evidence. */
    @Transactional(readOnly = true)
    public List<McpToolSelectionRow> loadSelectedTools(Long mcpConfigurationId) {
        return mcpSelectedToolRepository.findByMcpConfigurationIdOrderByServerNameAscToolNameAsc(mcpConfigurationId).stream()
                .map(tool -> new McpToolSelectionRow(
                        tool.getQualifiedName(),
                        tool.getServerName(),
                        tool.getToolName(),
                        tool.getTitle(),
                        tool.getDescription(),
                        true,
                        tool.isReviewReadOnlyApproved()
                ))
                .toList();
    }

    public void saveSelection(Long mcpConfigurationId, List<String> selectedQualifiedNames) {
        replaceSelection(mcpConfigurationId, selectedQualifiedNames, List.of(), List.of(), Map.of());
    }

    /** Applies explicit grant/revoke edits; an unchanged checked row is preservation, never a new grant. */
    public void saveSelection(Long mcpConfigurationId, List<String> selectedQualifiedNames,
                              List<String> reviewReadOnlyApprovedQualifiedNames, List<String> revokedQualifiedNames,
                              Map<String, ReviewReadOnlyApproval> reviewedEvidence) {
        Objects.requireNonNull(reviewedEvidence, "Server-held approval evidence is required");
        replaceSelection(mcpConfigurationId, selectedQualifiedNames, reviewReadOnlyApprovedQualifiedNames,
                revokedQualifiedNames, reviewedEvidence);
    }

    private void replaceSelection(Long mcpConfigurationId, List<String> selectedQualifiedNames,
                                  List<String> reviewReadOnlyApprovedQualifiedNames,
                                  List<String> revokedQualifiedNames,
                                  Map<String, ReviewReadOnlyApproval> reviewedEvidence) {
        McpConfiguration observed = captureConfiguration(mcpConfigurationId);
        Set<String> selected = new LinkedHashSet<>(selectedQualifiedNames == null ? List.of() : selectedQualifiedNames);
        Set<String> approved = new LinkedHashSet<>(reviewReadOnlyApprovedQualifiedNames == null
                ? List.of() : reviewReadOnlyApprovedQualifiedNames);
        Set<String> revoked = new LinkedHashSet<>(revokedQualifiedNames == null ? List.of() : revokedQualifiedNames);

        McpToolCatalog catalog = mcpOrchestrationService.discoverTools(observed);
        McpConfiguration configuration = lockObservedConfiguration(observed);
        Map<String, McpSelectedTool> existingByQualifiedName = mcpConfigurationService.refreshSelectedTools(mcpConfigurationId)
                .stream()
                .collect(HashMap::new, (map, entry) -> map.put(entry.getQualifiedName(), entry), HashMap::putAll);

        Map<String, McpToolDefinition> discoveredByQualifiedName = catalog.tools().stream()
                .collect(HashMap::new, (map, entry) -> map.put(entry.qualifiedName(), entry), HashMap::putAll);
        Map<String, ReviewReadOnlyApproval> currentEvidence = approvalCandidates(configuration, catalog);
        for (String name : approved) {
            if (revoked.contains(name) || !selected.contains(name) || !currentEvidence.containsKey(name)
                    || !currentEvidence.get(name).equals(reviewedEvidence.get(name))) {
                throw new IllegalArgumentException("Read-only approval requires a selected, available, unchanged tool; reload the tool form");
            }
        }
        for (String name : revoked) {
            if (!existingByQualifiedName.containsKey(name) && !reviewedEvidence.containsKey(name)) {
                throw new IllegalArgumentException("Cannot revoke read-only approval for an unknown tool");
            }
        }

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
            ReviewReadOnlyApproval evidence = currentEvidence.get(qualifiedName);
            boolean keepApproval = !revoked.contains(qualifiedName)
                    && (matchesApproval(existing, evidence) || approved.contains(qualifiedName));
            if (keepApproval) {
                selectedTool.setReviewReadOnlyApproved(true);
                selectedTool.setReviewApprovalSchemaHash(evidence.schemaHash());
                selectedTool.setReviewApprovalConfigurationHash(evidence.configurationHash());
            }
            replacement.add(selectedTool);
        }

        mcpSelectedToolRepository.deleteByMcpConfigurationId(mcpConfigurationId);
        mcpSelectedToolRepository.flush();
        mcpSelectedToolRepository.saveAll(replacement);
    }

    /**
     * Reads selected admin approvals matching a captured configuration and its trusted catalog,
     * invalidating stale bindings. Does not discover, authorize execution or freeze a run.
     * A future protocol caller owns catalog freshness and freezing the matching definitions/configuration.
     */
    public List<ReviewReadOnlyApproval> readReviewReadOnlyApprovals(McpConfiguration observedConfiguration, McpToolCatalog catalog) {
        McpConfiguration configuration = lockObservedConfiguration(observedConfiguration);
        return validatedApprovals(mcpConfigurationService.refreshSelectedTools(configuration.getId()),
                approvalCandidates(configuration, catalog));
    }

    private List<ReviewReadOnlyApproval> validatedApprovals(List<McpSelectedTool> selectedTools,
                                                          Map<String, ReviewReadOnlyApproval> current) {
        List<ReviewReadOnlyApproval> result = new ArrayList<>();
        for (McpSelectedTool selected : selectedTools) {
            ReviewReadOnlyApproval evidence = current.get(selected.getQualifiedName());
            if (matchesApproval(selected, evidence)) {
                result.add(evidence);
            } else {
                selected.clearReviewReadOnlyApproval();
            }
        }
        return List.copyOf(result);
    }

    private Map<String, ReviewReadOnlyApproval> approvalCandidates(McpConfiguration configuration, McpToolCatalog catalog) {
        String configurationHash = mcpConfigurationService.configurationHash(configuration);
        Map<String, ReviewReadOnlyApproval> result = new LinkedHashMap<>();
        if (catalog != null) {
            for (McpToolDefinition tool : catalog.tools()) {
                if (tool.inputSchema() == null || tool.serverName() == null || tool.name() == null
                        || !("mcp:" + tool.serverName() + ":" + tool.name()).equals(tool.qualifiedName())) {
                    continue;
                }
                ReviewReadOnlyApproval evidence = new ReviewReadOnlyApproval(configuration.getId(), tool.qualifiedName(),
                        tool.serverName(), tool.name(), McpConfigurationService.approvalHash(tool), configurationHash);
                if (result.putIfAbsent(tool.qualifiedName(), evidence) != null) {
                    throw new IllegalArgumentException("Ambiguous MCP tool identity cannot be approved");
                }
            }
        }
        return Map.copyOf(result);
    }

    private boolean matchesApproval(McpSelectedTool selected, ReviewReadOnlyApproval evidence) {
        return selected != null && selected.isReviewReadOnlyApproved() && evidence != null
                && Objects.equals(selected.getServerName(), evidence.serverName())
                && Objects.equals(selected.getToolName(), evidence.toolName())
                && evidence.schemaHash().equals(selected.getReviewApprovalSchemaHash())
                && evidence.configurationHash().equals(selected.getReviewApprovalConfigurationHash());
    }

    /** Immutable configuration evidence for the future review protocol, not an execution permit. */
    public record ReviewReadOnlyApproval(Long mcpConfigurationId, String qualifiedName, String serverName,
                                         String toolName, String schemaHash, String configurationHash) {
    }

    /** Configuration-form snapshot; its evidence stays in the server session, never in request-bound fields. */
    public record SelectionSnapshot(List<McpToolSelectionRow> tools, Map<String, ReviewReadOnlyApproval> approvalCandidates) {
        public SelectionSnapshot {
            tools = List.copyOf(tools);
            approvalCandidates = Map.copyOf(approvalCandidates);
        }
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

    private McpConfiguration captureConfiguration(Long id) {
        McpConfiguration stored = mcpConfigurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
        McpConfiguration captured = new McpConfiguration();
        captured.setId(stored.getId());
        captured.setName(stored.getName());
        captured.setJsonContent(stored.getJsonContent());
        captured.setCreatedAt(stored.getCreatedAt());
        captured.setUpdatedAt(stored.getUpdatedAt());
        captured.setReviewApprovalRevision(stored.getReviewApprovalRevision());
        return captured;
    }

    private McpConfiguration lockObservedConfiguration(McpConfiguration observed) {
        String binding = mcpConfigurationService.configurationHash(observed);
        McpConfiguration current = mcpConfigurationService.lockForApprovalUpdate(observed.getId());
        if (!binding.equals(mcpConfigurationService.configurationHash(current))) {
            throw new IllegalArgumentException("MCP configuration changed during discovery; reload the tool form");
        }
        return current;
    }
}
