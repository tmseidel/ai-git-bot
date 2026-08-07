package org.remus.giteabot.mcp;

import java.util.List;

public class McpServerDiscovery {

    private final McpConfigurationParser configurationParser;

    public McpServerDiscovery(McpConfigurationParser configurationParser) {
        this.configurationParser = configurationParser;
    }

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

