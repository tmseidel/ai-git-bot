package org.remus.giteabot.prworkflow.deployment.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed view of a {@code MCP} deployment-target {@code configJson} document.
 *
 * <p>Schema (cleartext, before encryption):</p>
 * <pre>{@code
 * {
 *   "mcpConfigurationId": 7,
 *   "deployTool":   "platform_deploy_preview",
 *   "statusTool":   "platform_get_preview_status",     // optional
 *   "teardownTool": "platform_teardown_preview",       // optional
 *   "argsTemplate": {                                  // optional, free-form JSON
 *     "project": "shop-web",
 *     "branch":  "{branch}",
 *     "ref":     "{sha}"
 *   }
 * }
 * }</pre>
 *
 * <p>The {@code argsTemplate} value is the raw argument document passed to
 * the MCP tool call. Any string leaf is run through
 * {@link McpDeploymentTemplating#substitute(String, Map)} so the operator can
 * thread {@code {prNumber}}, {@code {sha}}, {@code {branch}},
 * {@code {repoOwner}}, {@code {repoName}}, {@code {runId}},
 * {@code {callbackUrl}} and {@code {callbackSecret}} into the call without
 * touching Java.</p>
 *
 * <p>If {@code argsTemplate} is omitted, the strategy passes a default
 * envelope containing the standard placeholders so trivial servers work
 * out-of-the-box.</p>
 */
public record McpDeploymentConfig(
        Long mcpConfigurationId,
        String deployTool,
        String statusTool,
        String teardownTool,
        Map<String, Object> argsTemplate) {

    public static final String FIELD_MCP_CONFIG_ID  = "mcpConfigurationId";
    public static final String FIELD_DEPLOY_TOOL    = "deployTool";
    public static final String FIELD_STATUS_TOOL    = "statusTool";
    public static final String FIELD_TEARDOWN_TOOL  = "teardownTool";
    public static final String FIELD_ARGS_TEMPLATE  = "argsTemplate";

    public McpDeploymentConfig {
        argsTemplate = argsTemplate == null ? Map.of() : Map.copyOf(argsTemplate);
    }

    public Optional<String> optionalStatusTool() {
        return statusTool == null || statusTool.isBlank() ? Optional.empty() : Optional.of(statusTool);
    }

    public Optional<String> optionalTeardownTool() {
        return teardownTool == null || teardownTool.isBlank() ? Optional.empty() : Optional.of(teardownTool);
    }

    /**
     * Parses one cleartext {@code configJson} document. Throws
     * {@link IllegalArgumentException} on missing/invalid required fields so
     * {@code MCPDeploymentStrategy} can surface the error as a
     * {@code REJECTED} deployment result.
     */
    public static McpDeploymentConfig parse(String configJson, ObjectMapper objectMapper) {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("MCP deployment target config is empty");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(configJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP deployment target config is not valid JSON: " + e.getMessage(), e);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("MCP deployment target config must be a JSON object");
        }
        JsonNode idNode = node.get(FIELD_MCP_CONFIG_ID);
        if (idNode == null || !idNode.canConvertToLong()) {
            throw new IllegalArgumentException("MCP deployment target config is missing numeric '" + FIELD_MCP_CONFIG_ID + "'");
        }
        Long mcpConfigurationId = idNode.asLong();
        String deployTool = textOrNull(node, FIELD_DEPLOY_TOOL);
        if (deployTool == null || deployTool.isBlank()) {
            throw new IllegalArgumentException("MCP deployment target config is missing '" + FIELD_DEPLOY_TOOL + "'");
        }
        String statusTool   = textOrNull(node, FIELD_STATUS_TOOL);
        String teardownTool = textOrNull(node, FIELD_TEARDOWN_TOOL);

        Map<String, Object> argsTemplate = Map.of();
        JsonNode argsNode = node.get(FIELD_ARGS_TEMPLATE);
        if (argsNode != null && !argsNode.isNull()) {
            if (!argsNode.isObject()) {
                throw new IllegalArgumentException("'" + FIELD_ARGS_TEMPLATE + "' must be a JSON object when present");
            }
            argsTemplate = objectMapper.convertValue(argsNode, Map.class);
            if (argsTemplate == null) argsTemplate = Map.of();
        }
        return new McpDeploymentConfig(mcpConfigurationId, deployTool, statusTool, teardownTool,
                Collections.unmodifiableMap(new LinkedHashMap<>(argsTemplate)));
    }

    /**
     * Serialises a strategy-side handle (the JSON the orchestrator
     * round-trips back into {@code poll()} / {@code teardown()}). Always
     * carries the MCP configuration id and the tool names so a later
     * status / teardown call works even if the operator edits the
     * deployment-target config in the meantime.
     */
    public String handleJson(ObjectMapper objectMapper, Map<String, Object> remoteHandle) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("strategy", "MCP");
            node.put(FIELD_MCP_CONFIG_ID, mcpConfigurationId);
            node.put(FIELD_DEPLOY_TOOL, deployTool);
            if (statusTool != null && !statusTool.isBlank()) {
                node.put(FIELD_STATUS_TOOL, statusTool);
            }
            if (teardownTool != null && !teardownTool.isBlank()) {
                node.put(FIELD_TEARDOWN_TOOL, teardownTool);
            }
            if (remoteHandle != null && !remoteHandle.isEmpty()) {
                node.set("remote", objectMapper.valueToTree(remoteHandle));
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
