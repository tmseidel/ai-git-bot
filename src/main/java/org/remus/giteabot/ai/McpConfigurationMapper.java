package org.remus.giteabot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.StreamSupport;

@Slf4j
public final class McpConfigurationMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private McpConfigurationMapper() {
    }

    public static List<String> extractNamesFromMcpJson(String mcpJson) {
        try {
            var jsonNode = OBJECT_MAPPER.readTree(mcpJson);
            return StreamSupport.stream(jsonNode.spliterator(), false).map(node -> node.path("name").asText()).filter(s -> !s.isBlank()).toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public static String toMcpServers(McpConfigurationData mcpConfiguration, String providerName) {
        if (mcpConfiguration == null || mcpConfiguration.json() == null || mcpConfiguration.json().isBlank()) {
            return null;
        }
        try {
            OBJECT_MAPPER.readTree(mcpConfiguration.json());
            return mcpConfiguration.json();
        } catch (Exception e) {
            log.error("MCP configuration '{}' is not valid for {} requests; continuing without MCP: {}",
                    mcpConfiguration.name(), providerName, e.getMessage(), e);
            return null;
        }
    }
}
