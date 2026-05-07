package org.remus.giteabot.mcp;

import java.util.Map;

public record McpToolDefinition(
        String serverName,
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        String qualifiedName
) {
}

