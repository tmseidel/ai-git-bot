package org.remus.giteabot.mcp;

import java.util.Map;

public record McpServerDefinition(
        String name,
        String type,
        String url,
        String authorizationToken,
        Map<String, String> headers
) {
}

