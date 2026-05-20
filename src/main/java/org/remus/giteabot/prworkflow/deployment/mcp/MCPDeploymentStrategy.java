package org.remus.giteabot.prworkflow.deployment.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.mcp.McpOrchestrationService;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.deployment.DeploymentRequest;
import org.remus.giteabot.prworkflow.deployment.DeploymentResult;
import org.remus.giteabot.prworkflow.deployment.DeploymentStatus;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategy;
import org.remus.giteabot.prworkflow.deployment.DeploymentStrategyType;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpConfigurationRepository;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * M5 deployment strategy that delegates {@code trigger} / {@code poll} /
 * {@code teardown} to operator-configured tools on an {@link McpConfiguration}
 * (e.g. an internal platform server exposing {@code deploy-pr-preview},
 * {@code get-preview-status} and {@code teardown-preview}).
 *
 * <p>Strategy configuration JSON (see {@link McpDeploymentConfig} for the
 * full schema). All three tool names must be entries in the configured
 * MCP's <em>whitelist</em> ({@link McpToolSelectionService}) — calls to
 * non-whitelisted tools are rejected before any network traffic, mirroring
 * the agent-side enforcement.</p>
 *
 * <p>The strategy is <em>synchronous</em> with respect to the orchestrator:
 * the deploy tool may either return a ready preview URL (in which case the
 * result is {@link DeploymentStatus#READY}) or hand back an opaque
 * {@code handle} for later polling ({@link DeploymentStatus#PENDING}). The
 * strategy does <em>not</em> set {@link #awaitsCallback()} because MCP
 * servers do not deliver HTTP callbacks into the bot — the orchestrator
 * polls via {@link #poll(PrWorkflowRun)} on a schedule.</p>
 */
@Slf4j
@Component
public class MCPDeploymentStrategy implements DeploymentStrategy {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpOrchestrationService mcpOrchestrationService;
    private final McpConfigurationRepository mcpConfigurationRepository;
    private final McpToolSelectionService mcpToolSelectionService;

    public MCPDeploymentStrategy(McpOrchestrationService mcpOrchestrationService,
                                 McpConfigurationRepository mcpConfigurationRepository,
                                 McpToolSelectionService mcpToolSelectionService) {
        this.mcpOrchestrationService = mcpOrchestrationService;
        this.mcpConfigurationRepository = mcpConfigurationRepository;
        this.mcpToolSelectionService = mcpToolSelectionService;
    }

    @Override
    public DeploymentStrategyType typeKey() {
        return DeploymentStrategyType.MCP;
    }

    @Override
    public DeploymentResult trigger(DeploymentRequest request) {
        McpDeploymentConfig config;
        try {
            config = McpDeploymentConfig.parse(request.target().getConfigJson(), OBJECT_MAPPER);
        } catch (IllegalArgumentException e) {
            return DeploymentResult.rejected(e.getMessage());
        }

        Optional<McpConfiguration> mcpConfig = mcpConfigurationRepository.findById(config.mcpConfigurationId());
        if (mcpConfig.isEmpty()) {
            return DeploymentResult.rejected(
                    "MCP configuration id=" + config.mcpConfigurationId() + " not found");
        }
        McpConfiguration configuration = mcpConfig.get();

        Set<String> allowed = mcpToolSelectionService.selectedQualifiedToolNameSet(configuration.getId());
        Optional<String> notWhitelisted = whitelistRejection(allowed, config);
        if (notWhitelisted.isPresent()) {
            return DeploymentResult.rejected(notWhitelisted.get());
        }

        Map<String, Object> arguments = renderArguments(config, request);
        McpToolCatalog catalog = mcpOrchestrationService.discoverTools(configuration);
        ToolResult result = invokeTool(configuration, catalog, config.deployTool(), arguments);
        if (!result.success()) {
            return DeploymentResult.failed(
                    "MCP deploy tool '" + config.deployTool() + "' failed: " + describeError(result),
                    config.handleJson(OBJECT_MAPPER, Map.of()));
        }

        return interpretDeployResult(config, result);
    }

    @Override
    public DeploymentResult poll(PrWorkflowRun run) {
        if (run == null || run.getDeploymentHandleJson() == null || run.getDeploymentHandleJson().isBlank()) {
            return DeploymentStrategy.super.poll(run);
        }
        HandleSummary summary = parseHandle(run.getDeploymentHandleJson());
        if (summary == null || summary.mcpConfigurationId() == null || summary.statusTool() == null) {
            // No statusTool configured (or no MCP-shaped handle) — defer to the default polling behaviour
            // which trusts the inbound callback / known preview URL.
            return DeploymentStrategy.super.poll(run);
        }
        Optional<McpConfiguration> mcpConfig =
                mcpConfigurationRepository.findById(summary.mcpConfigurationId());
        if (mcpConfig.isEmpty()) {
            log.warn("MCPDeploymentStrategy.poll: MCP configuration id={} no longer exists for run id={}",
                    summary.mcpConfigurationId(), run.getId());
            return DeploymentStrategy.super.poll(run);
        }
        Map<String, Object> args = baseArgumentsForPoll(run, summary);
        McpToolCatalog catalog = mcpOrchestrationService.discoverTools(mcpConfig.get());
        ToolResult result = invokeTool(mcpConfig.get(), catalog, summary.statusTool(), args);
        if (!result.success()) {
            log.warn("MCPDeploymentStrategy.poll: status tool '{}' failed for run id={}: {}",
                    summary.statusTool(), run.getId(), describeError(result));
            return DeploymentStrategy.super.poll(run);
        }
        return interpretPollResult(run, summary, result);
    }

    @Override
    public void teardown(PrWorkflowRun run) {
        if (run == null || run.getDeploymentHandleJson() == null || run.getDeploymentHandleJson().isBlank()) {
            return;
        }
        HandleSummary summary = parseHandle(run.getDeploymentHandleJson());
        if (summary == null || summary.mcpConfigurationId() == null || summary.teardownTool() == null) {
            return;
        }
        Optional<McpConfiguration> mcpConfig =
                mcpConfigurationRepository.findById(summary.mcpConfigurationId());
        if (mcpConfig.isEmpty()) {
            log.debug("MCPDeploymentStrategy.teardown: MCP configuration id={} no longer exists for run id={}",
                    summary.mcpConfigurationId(), run.getId());
            return;
        }
        McpToolCatalog catalog = mcpOrchestrationService.discoverTools(mcpConfig.get());
        ToolResult result = invokeTool(mcpConfig.get(), catalog,
                summary.teardownTool(), baseArgumentsForPoll(run, summary));
        if (!result.success()) {
            log.warn("MCPDeploymentStrategy.teardown: tool '{}' failed for run id={}: {}",
                    summary.teardownTool(), run.getId(), describeError(result));
            return;
        }
        log.info("MCPDeploymentStrategy.teardown: tool '{}' succeeded for run id={}",
                summary.teardownTool(), run.getId());
    }

    // ---- internals ----

    Map<String, Object> renderArguments(McpDeploymentConfig config, DeploymentRequest request) {
        Map<String, Object> placeholders = buildPlaceholders(request);
        Map<String, Object> rendered;
        if (config.argsTemplate() == null || config.argsTemplate().isEmpty()) {
            rendered = new LinkedHashMap<>(placeholders);
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> substituted = (Map<String, Object>)
                    McpDeploymentTemplating.substituteDeep(config.argsTemplate(), placeholders);
            rendered = substituted;
        }
        return rendered;
    }

    Map<String, Object> buildPlaceholders(DeploymentRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("prNumber", request.prNumber());
        values.put("sha", request.sha());
        values.put("branch", request.branch());
        values.put("repoOwner", request.repoOwner());
        values.put("repoName", request.repoName());
        values.put("runId", request.run().getId());
        values.put("callbackUrl", request.callbackUrl());
        values.put("callbackSecret", request.run().getCallbackSecret());
        return values;
    }

    private ToolResult invokeTool(McpConfiguration configuration, McpToolCatalog catalog,
                                  String qualifiedToolName, Map<String, Object> arguments) {
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(arguments);
        } catch (Exception e) {
            return new ToolResult(false, -1, "", "Failed to serialise tool arguments: " + e.getMessage());
        }
        return mcpOrchestrationService.executeTool(configuration, catalog, qualifiedToolName, List.of(json));
    }

    DeploymentResult interpretDeployResult(McpDeploymentConfig config, ToolResult result) {
        Map<String, Object> remoteHandle = Map.of();
        String previewUrl = null;
        String status = null;
        String error = null;
        Map<String, Object> parsed = tryParseObject(result.output());
        if (parsed != null) {
            Object urlValue = firstNonNull(parsed.get("previewUrl"), parsed.get("preview_url"), parsed.get("url"));
            if (urlValue instanceof String s && !s.isBlank()) {
                previewUrl = s;
            }
            Object handleValue = parsed.get("handle");
            if (handleValue instanceof Map<?, ?> handleMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) handleMap;
                remoteHandle = casted;
            } else if (handleValue != null) {
                remoteHandle = Map.of("value", handleValue);
            }
            Object statusValue = parsed.get("status");
            if (statusValue instanceof String s) {
                status = s.toLowerCase(java.util.Locale.ROOT);
            }
            Object errorValue = parsed.get("error");
            if (errorValue instanceof String s) {
                error = s;
            }
        }

        String handleJson = config.handleJson(OBJECT_MAPPER, remoteHandle);
        if ("failed".equals(status) || "error".equals(status)) {
            return DeploymentResult.failed(
                    error != null ? error : "MCP deploy tool reported status=" + status, handleJson);
        }
        if (previewUrl != null) {
            return DeploymentResult.ready(previewUrl, handleJson);
        }
        return DeploymentResult.pending(handleJson);
    }

    DeploymentResult interpretPollResult(PrWorkflowRun run, HandleSummary summary, ToolResult result) {
        Map<String, Object> parsed = tryParseObject(result.output());
        String previewUrl = run.getPreviewUrl();
        String status = null;
        String error = null;
        Map<String, Object> remoteHandle = summary.remoteHandle();
        if (parsed != null) {
            Object urlValue = firstNonNull(parsed.get("previewUrl"), parsed.get("preview_url"), parsed.get("url"));
            if (urlValue instanceof String s && !s.isBlank()) {
                previewUrl = s;
            }
            Object statusValue = parsed.get("status");
            if (statusValue instanceof String s) {
                status = s.toLowerCase(java.util.Locale.ROOT);
            }
            Object errorValue = parsed.get("error");
            if (errorValue instanceof String s) {
                error = s;
            }
            Object handleValue = parsed.get("handle");
            if (handleValue instanceof Map<?, ?> handleMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) handleMap;
                remoteHandle = casted;
            }
        }
        String handleJson = new McpDeploymentConfig(summary.mcpConfigurationId(),
                summary.deployTool(), summary.statusTool(), summary.teardownTool(), Map.of())
                .handleJson(OBJECT_MAPPER, remoteHandle);

        if ("failed".equals(status) || "error".equals(status)) {
            return DeploymentResult.failed(
                    error != null ? error : "MCP status tool reported status=" + status, handleJson);
        }
        if (previewUrl != null && !previewUrl.isBlank()
                && (status == null || "ready".equals(status) || "succeeded".equals(status))) {
            return DeploymentResult.ready(previewUrl, handleJson);
        }
        return DeploymentResult.pending(handleJson);
    }

    private Map<String, Object> baseArgumentsForPoll(PrWorkflowRun run, HandleSummary summary) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("runId", run.getId());
        args.put("prNumber", run.getPrNumber());
        args.put("repoOwner", run.getRepoOwner());
        args.put("repoName", run.getRepoName());
        if (summary.remoteHandle() != null && !summary.remoteHandle().isEmpty()) {
            args.put("handle", summary.remoteHandle());
        }
        return args;
    }

    private static Optional<String> whitelistRejection(Set<String> allowed, McpDeploymentConfig config) {
        if (!allowed.contains(config.deployTool())) {
            return Optional.of("MCP tool '" + config.deployTool()
                    + "' is not whitelisted on the configured MCP server");
        }
        if (config.optionalStatusTool().isPresent()
                && !allowed.contains(config.statusTool())) {
            return Optional.of("MCP tool '" + config.statusTool()
                    + "' (statusTool) is not whitelisted on the configured MCP server");
        }
        if (config.optionalTeardownTool().isPresent()
                && !allowed.contains(config.teardownTool())) {
            return Optional.of("MCP tool '" + config.teardownTool()
                    + "' (teardownTool) is not whitelisted on the configured MCP server");
        }
        return Optional.empty();
    }

    private static String describeError(ToolResult result) {
        if (result.error() != null && !result.error().isBlank()) {
            return truncate(result.error());
        }
        if (result.output() != null && !result.output().isBlank()) {
            return truncate(result.output());
        }
        return "exit=" + result.exitCode();
    }

    private static String truncate(String s) {
        return s.length() <= 256 ? s : s.substring(0, 256) + "…";
    }

    private static Object firstNonNull(Object a, Object b, Object c) {
        return a != null ? a : (b != null ? b : c);
    }

    private static Map<String, Object> tryParseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            if (!node.isObject()) {
                return null;
            }
            return OBJECT_MAPPER.convertValue(node, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    /** Parsed view of a handle JSON written earlier by {@link McpDeploymentConfig#handleJson}. */
    record HandleSummary(Long mcpConfigurationId, String deployTool, String statusTool,
                         String teardownTool, Map<String, Object> remoteHandle) {
    }

    static HandleSummary parseHandle(String handleJson) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(handleJson);
            if (!node.isObject()) return null;
            JsonNode idNode = node.get(McpDeploymentConfig.FIELD_MCP_CONFIG_ID);
            if (idNode == null || !idNode.canConvertToLong()) return null;
            String deployTool   = textOrNull(node, McpDeploymentConfig.FIELD_DEPLOY_TOOL);
            String statusTool   = textOrNull(node, McpDeploymentConfig.FIELD_STATUS_TOOL);
            String teardownTool = textOrNull(node, McpDeploymentConfig.FIELD_TEARDOWN_TOOL);
            Map<String, Object> remoteHandle = Map.of();
            JsonNode remote = node.get("remote");
            if (remote != null && remote.isObject()) {
                remoteHandle = OBJECT_MAPPER.convertValue(remote, new TypeReference<>() {});
            }
            return new HandleSummary(idNode.asLong(), deployTool, statusTool, teardownTool, remoteHandle);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}

