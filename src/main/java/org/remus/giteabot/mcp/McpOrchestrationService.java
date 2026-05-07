package org.remus.giteabot.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class McpOrchestrationService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_TOOL_OUTPUT_CHARS = 10_000;
    private static final List<String> STREAMABLE_HTTP_PROTOCOL_VERSIONS = List.of(
            ProtocolVersions.MCP_2024_11_05,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2025_06_18);
    private static final List<List<String>> STREAMABLE_HTTP_PROTOCOL_FALLBACKS = List.of(
            STREAMABLE_HTTP_PROTOCOL_VERSIONS,
            List.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26),
            List.of(ProtocolVersions.MCP_2024_11_05));

    private final McpConfigurationParser configurationParser = new McpConfigurationParser();
    private final McpServerDiscovery serverDiscovery = new McpServerDiscovery(configurationParser);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<CacheKey, McpToolCatalog> toolCache = new ConcurrentHashMap<>();

    public McpToolCatalog discoverTools(McpConfiguration configuration) {
        if (configuration == null) {
            return McpToolCatalog.empty();
        }
        CacheKey cacheKey = CacheKey.from(configuration);
        return toolCache.computeIfAbsent(cacheKey, ignored -> fetchToolCatalog(configuration));
    }

    public boolean isMcpTool(McpToolCatalog catalog, String toolName) {
        return catalog != null && catalog.isMcpTool(toolName);
    }

    public ToolResult executeTool(McpConfiguration configuration, McpToolCatalog catalog,
                                  String qualifiedToolName, List<String> args) {
        if (configuration == null) {
            return new ToolResult(false, -1, "", "No active MCP configuration assigned to this bot");
        }
        McpToolCatalog effectiveCatalog = catalog != null ? catalog : discoverTools(configuration);
        McpToolDefinition toolDefinition = effectiveCatalog.find(qualifiedToolName).orElse(null);
        if (toolDefinition == null) {
            return new ToolResult(false, -1, "", "MCP tool '" + qualifiedToolName + "' is not available");
        }
        McpServerDefinition server = serverDiscovery.discover(configuration).stream()
                .filter(definition -> sanitizeName(definition.name()).equals(toolDefinition.serverName()))
                .findFirst()
                .orElse(null);
        if (server == null) {
            return new ToolResult(false, -1, "", "MCP server '" + toolDefinition.serverName() + "' is not configured");
        }
        Exception lastException = null;
        String diagnostic = null;
        for (McpConnectionAttempt attempt : connectionAttempts(server)) {
            try (McpSyncClient client = createClient(server, attempt)) {
                client.initialize();
                Map<String, Object> arguments = parseArguments(args);
                McpSchema.CallToolResult result = client.callTool(
                        new McpSchema.CallToolRequest(toolDefinition.name(), arguments));
                boolean isError = Boolean.TRUE.equals(result.isError());
                String output = formatToolResult(result);
                return new ToolResult(!isError, isError ? 1 : 0, truncateOutput(output), isError ? output : "");
            } catch (Exception e) {
                lastException = e;
                if (diagnostic == null && isBadRequestTransportFailure(e)) {
                    diagnostic = diagnoseInitializeFailure(server, attempt);
                    if (isAuthorizationFormattingDiagnostic(diagnostic)) {
                        log.warn("MCP tool '{}' failed on server '{}': {}", qualifiedToolName, server.name(), diagnostic);
                        return new ToolResult(false, -1, "", "MCP tool failed: " + diagnostic);
                    }
                }
                log.debug("MCP tool '{}' attempt failed on server '{}': {} ({})", qualifiedToolName, server.name(),
                        attempt.describe(), e.getMessage(), e);
            }
        }
        String message = lastException != null ? lastException.getMessage() : "No MCP connection attempts were available";
        if (diagnostic != null) {
            message += "; " + diagnostic;
        }
        log.warn("MCP tool '{}' failed on server '{}' after all connection attempts: {}",
                qualifiedToolName, server.name(), message, lastException);
        return new ToolResult(false, -1, "", "MCP tool failed: " + message);
    }

    private McpToolCatalog fetchToolCatalog(McpConfiguration configuration) {
        List<McpToolDefinition> tools = serverDiscovery.discover(configuration).stream()
                .flatMap(server -> fetchServerTools(server).stream())
                .toList();
        log.info("Discovered {} MCP tools for configuration '{}'", tools.size(), configuration.getName());
        return new McpToolCatalog(tools);
    }

    private List<McpToolDefinition> fetchServerTools(McpServerDefinition server) {
        List<String> failures = new ArrayList<>();
        String diagnostic = null;
        for (McpConnectionAttempt attempt : connectionAttempts(server)) {
            try (McpSyncClient client = createClient(server, attempt)) {
                client.initialize();
                McpSchema.ListToolsResult result = client.listTools();
                if (result == null || result.tools() == null) {
                    return List.of();
                }
                String serverName = sanitizeName(server.name());
                return result.tools().stream()
                        .filter(tool -> tool != null && tool.name() != null && !tool.name().isBlank())
                        .map(tool -> toDefinition(serverName, tool))
                        .toList();
            } catch (Exception e) {
                String failure = attempt.describe() + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage();
                if (diagnostic == null && isBadRequestTransportFailure(e)) {
                    diagnostic = diagnoseInitializeFailure(server, attempt);
                    failure += "; " + diagnostic;
                    if (isAuthorizationFormattingDiagnostic(diagnostic)) {
                        failures.add(failure);
                        log.warn("MCP server discovery failed for '{}' because the Authorization header was rejected: {}",
                                server.name(), diagnostic);
                        return List.of();
                    }
                }
                failures.add(failure);
                log.debug("MCP server discovery attempt failed for '{}': {}", server.name(), failure, e);
            }
        }
        log.warn("MCP server discovery failed for '{}' after {} attempts: {}", server.name(), failures.size(), failures);
        return List.of();
    }

    private McpToolDefinition toDefinition(String serverName, McpSchema.Tool tool) {
        Map<String, Object> inputSchema = tool.inputSchema() == null
                ? Map.of()
                : objectMapper.convertValue(tool.inputSchema(), new TypeReference<>() {});
        return new McpToolDefinition(
                serverName,
                tool.name(),
                tool.title(),
                tool.description(),
                inputSchema,
                "mcp:" + serverName + ":" + tool.name());
    }

    private McpSyncClient createClient(McpServerDefinition server, McpConnectionAttempt attempt) {
        McpClientTransport transport = createTransport(server, attempt);
        return McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .loggingConsumer(notification -> log.debug(notification.data()))
                .initializationTimeout(REQUEST_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("ai-git-bot", "1.4.0-SNAPSHOT"))
                .build();
    }

    private McpClientTransport createTransport(McpServerDefinition server, McpConnectionAttempt attempt) {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT);
        if (attempt.httpVersion() != null) {
            clientBuilder.version(attempt.httpVersion());
        }
        TransportEndpoint endpoint = attempt.endpoint();
        log.debug("Creating MCP {} transport for server '{}' at {}{}", endpoint.sse() ? "SSE" : "streamable HTTP",
                server.name(), endpoint.baseUri(), endpoint.endpoint());
        if (endpoint.sse()) {
            return HttpClientSseClientTransport.builder(endpoint.baseUri())
                    .sseEndpoint(endpoint.endpoint())
                    .clientBuilder(clientBuilder)
                    .httpRequestCustomizer((request, method, uri, body, context) -> applyHeaders(request, server))
                    .build();
        }
        return HttpClientStreamableHttpTransport.builder(endpoint.baseUri())
                .endpoint(endpoint.endpoint())
                .clientBuilder(clientBuilder)
                .supportedProtocolVersions(attempt.protocolVersions())
                .httpRequestCustomizer((request, method, uri, body, context) -> applyHeaders(request, server))
                .build();
    }

    private boolean isBadRequestTransportFailure(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Bad Request. Status code:400")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String diagnoseInitializeFailure(McpServerDefinition server, McpConnectionAttempt attempt) {
        if (attempt.endpoint().sse()) {
            return "diagnostic skipped for SSE transport";
        }
        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
            if (attempt.httpVersion() != null) {
                clientBuilder.version(attempt.httpVersion());
            }
            String protocolVersion = attempt.protocolVersions().getLast();
            String body = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", McpSchema.METHOD_INITIALIZE,
                    "params", Map.of(
                            "protocolVersion", protocolVersion,
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "ai-git-bot", "version", "1.4.0-SNAPSHOT"))));
            URI uri = URI.create(attempt.endpoint().baseUri()).resolve(attempt.endpoint().endpoint());
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", protocolVersion)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyHeaders(request, server);
            HttpResponse<String> response = clientBuilder.build().send(request.build(), HttpResponse.BodyHandlers.ofString());
            String wwwAuthenticate = response.headers().firstValue("www-authenticate").orElse("");
            String responseBody = response.body() == null ? "" : response.body().strip();
            return "diagnostic initialize POST " + uri + " returned HTTP " + response.statusCode()
                    + formatDiagnosticPart("www-authenticate", wwwAuthenticate)
                    + formatDiagnosticPart("body", responseBody);
        } catch (Exception diagnosticException) {
            return "diagnostic initialize POST failed: " + diagnosticException.getMessage();
        }
    }

    private String formatDiagnosticPart(String label, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return ", " + label + "='" + truncateDiagnostic(value) + "'";
    }

    private String truncateDiagnostic(String value) {
        if (value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "...";
    }

    private boolean isAuthorizationFormattingDiagnostic(String diagnostic) {
        if (diagnostic == null) {
            return false;
        }
        String lower = diagnostic.toLowerCase();
        return lower.contains("authorization header is badly formatted")
                || lower.contains("invalid_token")
                || lower.contains("missing required authorization header");
    }

    List<McpConnectionAttempt> connectionAttempts(McpServerDefinition server) {
        TransportEndpoint primary = resolveTransportEndpoint(server);
        if (primary.sse()) {
            return List.of(
                    new McpConnectionAttempt(primary, STREAMABLE_HTTP_PROTOCOL_VERSIONS, null),
                    new McpConnectionAttempt(primary, STREAMABLE_HTTP_PROTOCOL_VERSIONS, HttpClient.Version.HTTP_1_1));
        }

        List<McpConnectionAttempt> attempts = new ArrayList<>();
        for (TransportEndpoint endpoint : streamableHttpEndpointCandidates(server)) {
            for (List<String> protocolVersions : STREAMABLE_HTTP_PROTOCOL_FALLBACKS) {
                attempts.add(new McpConnectionAttempt(endpoint, protocolVersions, null));
                attempts.add(new McpConnectionAttempt(endpoint, protocolVersions, HttpClient.Version.HTTP_1_1));
            }
        }
        return attempts;
    }

    private List<TransportEndpoint> streamableHttpEndpointCandidates(McpServerDefinition server) {
        URI uri = URI.create(server.url().strip());
        String baseUri = uri.getScheme() + "://" + uri.getRawAuthority();
        String query = uri.getRawQuery();
        Set<String> endpoints = new LinkedHashSet<>();
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isBlank() && !"/".equals(rawPath)) {
            endpoints.add(withQuery(rawPath, query));
        }
        endpoints.add(resolveTransportEndpoint(server).endpoint());
        if (endpoints.stream().noneMatch(endpoint -> endpoint.startsWith("/mcp"))) {
            endpoints.add(withQuery("/mcp", query));
        }
        return endpoints.stream()
                .map(endpoint -> new TransportEndpoint(false, baseUri, endpoint))
                .toList();
    }

    TransportEndpoint resolveTransportEndpoint(McpServerDefinition server) {
        URI uri = URI.create(server.url().strip());
        String type = server.type() != null ? server.type().toLowerCase() : "";
        boolean sse = type.contains("sse");
        String baseUri = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        String endpoint;
        if (sse) {
            endpoint = normalizeSseEndpoint(path);
        } else {
            endpoint = normalizeStreamableHttpEndpoint(path);
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            endpoint = withQuery(endpoint, uri.getRawQuery());
        }
        return new TransportEndpoint(sse, baseUri, endpoint);
    }

    private String withQuery(String endpoint, String query) {
        if (query == null || query.isBlank() || endpoint.contains("?")) {
            return endpoint;
        }
        return endpoint + "?" + query;
    }

    private String normalizeSseEndpoint(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/sse";
        }
        String normalized = trimTrailingSlash(path);
        if (normalized.endsWith("/sse")) {
            return normalized;
        }
        return normalized + "/sse";
    }

    private String normalizeStreamableHttpEndpoint(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/mcp";
        }
        return trimTrailingSlash(path);
    }

    private String trimTrailingSlash(String path) {
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void applyHeaders(java.net.http.HttpRequest.Builder request, McpServerDefinition server) {
        if (server.headers() != null) {
            server.headers().forEach(request::header);
        }
        if (!hasConfiguredHeader(server, "user-agent")) {
            request.header("User-Agent", "ai-git-bot/1.4.0-SNAPSHOT");
        }
        if (server.authorizationToken() != null && !server.authorizationToken().isBlank()
                && !hasConfiguredHeader(server, "authorization")) {
            request.header("Authorization", formatAuthorizationHeader(server.authorizationToken()));
        }
    }

    private boolean hasConfiguredHeader(McpServerDefinition server, String headerName) {
        return server.headers() != null && server.headers().keySet().stream()
                .anyMatch(headerName::equalsIgnoreCase);
    }

    private String formatAuthorizationHeader(String token) {
        String trimmed = token.strip();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())
                || trimmed.regionMatches(true, 0, "Basic ", 0, "Basic ".length())) {
            return trimmed;
        }
        return "Bearer " + trimmed;
    }

    private Map<String, Object> parseArguments(List<String> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        if (args.size() == 1) {
            String raw = args.getFirst();
            if (raw != null && !raw.isBlank()) {
                try {
                    return objectMapper.readValue(raw, new TypeReference<>() {});
                } catch (Exception ignored) {
                    return Map.of("value", raw);
                }
            }
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            arguments.put("arg" + i, args.get(i));
        }
        return arguments;
    }

    private String formatToolResult(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.structuredContent() != null) {
            appendJson(sb, result.structuredContent());
        }
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    sb.append(textContent.text()).append("\n");
                } else {
                    appendJson(sb, content);
                }
            }
        }
        if (sb.isEmpty()) {
            return "(no output)";
        }
        return sb.toString().strip();
    }

    private void appendJson(StringBuilder sb, Object value) {
        try {
            sb.append(objectMapper.writeValueAsString(value)).append("\n");
        } catch (Exception e) {
            sb.append(value).append("\n");
        }
    }

    private String truncateOutput(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= MAX_TOOL_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_TOOL_OUTPUT_CHARS) + "\n... (output truncated)";
    }

    private String sanitizeName(String name) {
        String value = name == null || name.isBlank() ? "mcp" : name.strip();
        String sanitized = value.replaceAll("[^A-Za-z0-9_.-]", "-");
        return sanitized.isBlank() ? "mcp" : sanitized;
    }

    private record CacheKey(Long id, Instant updatedAt, int jsonHash) {
        static CacheKey from(McpConfiguration configuration) {
            return new CacheKey(configuration.getId(), configuration.getUpdatedAt(),
                    Objects.hashCode(configuration.getJsonContent()));
        }
    }

    record TransportEndpoint(boolean sse, String baseUri, String endpoint) {
    }

    record McpConnectionAttempt(TransportEndpoint endpoint, List<String> protocolVersions,
                                HttpClient.Version httpVersion) {

        String describe() {
            return (endpoint.sse() ? "sse" : "streamable-http")
                    + " " + endpoint.baseUri() + endpoint.endpoint()
                    + ", protocols=" + protocolVersions
                    + ", httpVersion=" + (httpVersion == null ? "default" : httpVersion);
        }
    }
}


