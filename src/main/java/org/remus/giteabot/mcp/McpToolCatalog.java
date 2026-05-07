package org.remus.giteabot.mcp;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public record McpToolCatalog(List<McpToolDefinition> tools) {

    public static McpToolCatalog empty() {
        return new McpToolCatalog(List.of());
    }

    public McpToolCatalog {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public boolean isMcpTool(String toolName) {
        return find(toolName).isPresent();
    }

    public Optional<McpToolDefinition> find(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return Optional.empty();
        }
        return tools.stream()
                .filter(tool -> qualifiedName.equals(tool.qualifiedName()))
                .findFirst();
    }

    public McpToolCatalog filterByQualifiedNames(Set<String> qualifiedNames) {
        if (qualifiedNames == null || qualifiedNames.isEmpty()) {
            return McpToolCatalog.empty();
        }
        return new McpToolCatalog(tools.stream()
                .filter(tool -> qualifiedNames.contains(tool.qualifiedName()))
                .toList());
    }
}

