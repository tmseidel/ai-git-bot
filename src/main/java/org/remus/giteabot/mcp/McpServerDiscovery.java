package org.remus.giteabot.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class McpServerDiscovery {

    private final McpConfigurationParser configurationParser;

    /**
     * Parses the (already decrypted) MCP configuration JSON into server
     * definitions with a non-blank URL.
     */
    public List<McpServerDefinition> discover(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return List.of();
        }
        return configurationParser.parse(jsonContent).stream()
                .filter(server -> server.url() != null && !server.url().isBlank())
                .toList();
    }
}

