package org.remus.giteabot.systemsettings;

import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class McpConfigurationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpConfigurationRepository mcpConfigurationRepository;
    private final BotRepository botRepository;

    @Transactional(readOnly = true)
    public List<McpConfiguration> findAll() {
        return mcpConfigurationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<McpConfiguration> findById(Long id) {
        return mcpConfigurationRepository.findById(id);
    }

    public McpConfiguration save(McpConfiguration mcpConfiguration) {
        if (mcpConfiguration.getName() == null || mcpConfiguration.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        mcpConfiguration.setName(mcpConfiguration.getName().trim());
        if (mcpConfiguration.getJsonContent() == null || mcpConfiguration.getJsonContent().isBlank()) {
            throw new IllegalArgumentException("MCP JSON content is required");
        }
        validateJson(mcpConfiguration.getJsonContent());
        boolean duplicateName = mcpConfiguration.getId() == null
                ? mcpConfigurationRepository.existsByName(mcpConfiguration.getName())
                : mcpConfigurationRepository.existsByNameAndIdNot(mcpConfiguration.getName(), mcpConfiguration.getId());
        if (duplicateName) {
            throw new IllegalArgumentException("An MCP configuration with this name already exists");
        }
        return mcpConfigurationRepository.save(mcpConfiguration);
    }

    public void deleteById(Long id) {
        McpConfiguration mcpConfiguration = mcpConfigurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP configuration not found"));
        List<Bot> bots = botRepository.findByMcpConfigurationId(id);
        if (!bots.isEmpty()) {
            String botNames = bots.stream().map(Bot::getName).toList().toString();
            throw new IllegalStateException("MCP configuration is used by bot(s): " + botNames);
        }
        mcpConfigurationRepository.delete(mcpConfiguration);
    }

    void validateJson(String jsonContent) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonContent);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP configuration must be valid JSON", e);
        }
        if (containsStdioTransport(root)) {
            throw new IllegalArgumentException("stdio MCP transport is not supported");
        }
        if (!containsRemoteEndpoint(root)) {
            throw new IllegalArgumentException("MCP configuration must contain a remote HTTP, HTTPS, WS, WSS, or SSE endpoint");
        }
    }

    private boolean containsStdioTransport(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (("transport".equalsIgnoreCase(field.getKey()) || "type".equalsIgnoreCase(field.getKey()))
                        && field.getValue().isTextual()
                        && "stdio".equalsIgnoreCase(field.getValue().asText())) {
                    return true;
                }
                if ("command".equalsIgnoreCase(field.getKey())) {
                    return true;
                }
                if (containsStdioTransport(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsStdioTransport(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsRemoteEndpoint(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            String value = node.asText().toLowerCase();
            return value.startsWith("http://")
                    || value.startsWith("https://")
                    || value.startsWith("ws://")
                    || value.startsWith("wss://")
                    || value.startsWith("sse://");
        }
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (containsRemoteEndpoint(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsRemoteEndpoint(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
