package org.remus.giteabot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class McpConfigurationParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<McpServerDefinition> parse(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            List<McpServerDefinition> servers = new ArrayList<>();
            collectServers(root, servers);
            return servers;
        } catch (Exception e) {
            log.warn("Unable to parse MCP configuration: {}", e.getMessage());
            return List.of();
        }
    }

    private void collectServers(JsonNode node, List<McpServerDefinition> servers) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectServers(child, servers));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (node.has("mcpServers") && node.get("mcpServers").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.get("mcpServers").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                servers.add(toServerDefinition(entry.getKey(), entry.getValue()));
            }
            return;
        }
        if (node.has("servers")) {
            collectServers(node.get("servers"), servers);
            return;
        }
        servers.add(toServerDefinition(null, node));
    }

    private McpServerDefinition toServerDefinition(String fallbackName, JsonNode node) {
        String name = text(node, "name", fallbackName != null ? fallbackName : "mcp");
        String type = text(node, "type", text(node, "transport", "url"));
        String url = text(node, "url", text(node, "endpoint", text(node, "serverUrl", null)));
        String token = text(node, "authorization_token", text(node, "authorizationToken", text(node, "token", null)));
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headersNode = node.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            headersNode.properties().forEach(field -> {
                if (field.getValue().isTextual()) {
                    headers.put(field.getKey(), field.getValue().asText());
                }
            });
        }
        return new McpServerDefinition(name, type, url, token, headers);
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            return value.asText().strip();
        }
        return defaultValue;
    }
}

