package org.remus.giteabot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.secret.SecretTemplate;
import org.remus.giteabot.secret.SecretTemplateParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpConfigurationParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SecretTemplateParser secretTemplateParser;

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
            for (Map.Entry<String, JsonNode> entry : node.get("mcpServers").properties()) {
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
        Map<String, SecretTemplate> headers = new LinkedHashMap<>();
        JsonNode headersNode = node.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            headersNode.properties().forEach(field -> {
                if (field.getValue().isTextual()) {
                    headers.put(field.getKey(), secretTemplateParser.parse(field.getValue().asText()));
                }
            });
        }
        return new McpServerDefinition(name, type, url, secretTemplate(token), headers);
    }

    /** Credentials stay as templates, so a {@code ${env:NAME}} reference is resolved at call time only. */
    private SecretTemplate secretTemplate(String value) {
        return value == null ? null : secretTemplateParser.parse(value);
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            return value.asText().strip();
        }
        return defaultValue;
    }
}

