package org.remus.giteabot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class McpToolPromptRenderer {

    private static final int MAX_SCHEMA_CHARS = 2_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String render(McpToolCatalog catalog) {
        if (catalog == null || !catalog.hasTools()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n**Available MCP tools** (silent; results go back to you only):\n");
        sb.append("Call MCP tools via `requestTools`/`toolRequests` using the exact tool name `mcp:<server>:<tool>`. ");
        sb.append("Pass one JSON object as the first arg when arguments are needed, e.g. `args: [\"{\\\"query\\\":\\\"text\\\"}\"]`.\n");
        for (McpToolDefinition tool : catalog.tools()) {
            sb.append("- `").append(tool.qualifiedName()).append("`");
            if (tool.description() != null && !tool.description().isBlank()) {
                sb.append(": ").append(tool.description().strip());
            }
            String schema = schemaToString(tool.inputSchema());
            if (!schema.isBlank()) {
                sb.append("\n  inputSchema: `").append(schema).append("`");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String schemaToString(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(schema);
            if (json.length() <= MAX_SCHEMA_CHARS) {
                return json;
            }
            return json.substring(0, MAX_SCHEMA_CHARS) + "…";
        } catch (Exception e) {
            log.debug("Failed to render MCP tool schema: {}", e.getMessage());
            return "";
        }
    }
}

