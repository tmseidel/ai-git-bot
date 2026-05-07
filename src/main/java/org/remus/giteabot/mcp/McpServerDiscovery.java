package org.remus.giteabot.mcp;

import org.remus.giteabot.systemsettings.McpConfiguration;

import java.util.List;

public class McpServerDiscovery {

    private final McpConfigurationParser configurationParser;

    public McpServerDiscovery(McpConfigurationParser configurationParser) {
        this.configurationParser = configurationParser;
    }

    public List<McpServerDefinition> discover(McpConfiguration configuration) {
        if (configuration == null) {
            return List.of();
        }
        return configurationParser.parse(configuration.getJsonContent()).stream()
                .filter(server -> server.url() != null && !server.url().isBlank())
                .toList();
    }
}

