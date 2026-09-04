package org.remus.giteabot.mcp;

import org.remus.giteabot.secret.SecretTemplate;

import java.util.Map;

public record McpServerDefinition(
        String name,
        String type,
        String url,
        SecretTemplate authorizationToken,
        Map<String, SecretTemplate> headers
) {
}

