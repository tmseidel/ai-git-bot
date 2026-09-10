package org.remus.giteabot.mcp;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.secret.FakeSecretSource;
import org.remus.giteabot.secret.KeyResolveException;
import org.remus.giteabot.secret.SecretSourceRegistry;
import org.remus.giteabot.secret.SecretTemplateParser;
import org.remus.giteabot.systemsettings.McpConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOrchestrationServiceTest {

    /** A secret the JDK refuses in a header - a line break survives {@code strip()} only inside the value. */
    private static final String SECRET_WITH_LINE_BREAK = "s3cret-first-line\nsecond-line";

    private final EncryptionService encryptionService = new EncryptionService("test-key");
    private final SecretTemplateParser secretTemplateParser = new SecretTemplateParser(new SecretSourceRegistry(List.of(FakeSecretSource.of("env", Map.of(
            "MCP_TOKEN", "s3cret",
            "MCP_HEADER", "header-value",
            "MCP_PADDED", "  padded-value\n",
            "MCP_BROKEN", SECRET_WITH_LINE_BREAK)))));
    private final McpConfigurationParser configurationParser = new McpConfigurationParser(secretTemplateParser);
    private final McpServerDiscovery serverDiscovery = new McpServerDiscovery(configurationParser);
    private final McpOrchestrationService service =
            new McpOrchestrationService(serverDiscovery, encryptionService);

    @Test
    void discoverServers_decryptsPersistedConfiguration() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setJsonContent(encryptionService.encrypt("""
                [{"name":"github","type":"url","url":"https://example.test/mcp"}]
                """));

        List<McpServerDefinition> servers = service.discoverServers(configuration);

        assertEquals(1, servers.size());
        assertEquals("github", servers.getFirst().name());
    }

    @Test
    void applyHeaders_resolvesSecretReferencesOfTokenAndHeaders() {
        McpServerDefinition server = server("""
                [{
                  "name":"github",
                  "type":"url",
                  "url":"https://example.test/mcp",
                  "authorization_token":"${env:MCP_TOKEN}",
                  "headers":{"X-Api-Key":"prefix-${env:MCP_HEADER}"}
                }]
                """);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://example.test/mcp")).GET();

        service.applyHeaders(request, server);

        HttpRequest built = request.build();
        assertEquals("Bearer s3cret", built.headers().firstValue("Authorization").orElseThrow());
        assertEquals("prefix-header-value", built.headers().firstValue("X-Api-Key").orElseThrow());
    }

    @Test
    void applyHeaders_keepsLiteralTokenWithoutSecretReference() {
        McpServerDefinition server = server("""
                [{"name":"github","type":"url","url":"https://example.test/mcp","authorization_token":"Bearer plain"}]
                """);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://example.test/mcp")).GET();

        service.applyHeaders(request, server);

        assertEquals("Bearer plain", request.build().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void applyHeaders_sendsRawReferenceWhenSecretCannotBeResolved() {
        McpServerDefinition server = server("""
                [{"name":"github","type":"url","url":"https://example.test/mcp","authorization_token":"${env:UNKNOWN}"}]
                """);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://example.test/mcp")).GET();

        service.applyHeaders(request, server);

        assertEquals("Bearer ${env:UNKNOWN}", request.build().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void applyHeaders_stripsWhitespaceAroundAResolvedHeaderValue() {
        // an environment variable read from a file commonly ends with a newline, which the JDK
        // would reject as a header value
        McpServerDefinition server = server("""
                [{"name":"github","type":"url","url":"https://example.test/mcp",
                  "headers":{"X-Api-Key":"${env:MCP_PADDED}"}}]
                """);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://example.test/mcp")).GET();

        service.applyHeaders(request, server);

        assertEquals("padded-value", request.build().headers().firstValue("X-Api-Key").orElseThrow());
    }

    @Test
    void applyHeaders_doesNotLeakTheSecretWhenAResolvedHeaderValueIsInvalid() {
        McpServerDefinition server = server("""
                [{"name":"github","type":"url","url":"https://example.test/mcp",
                  "headers":{"X-Api-Key":"${env:MCP_BROKEN}"}}]
                """);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://example.test/mcp")).GET();

        // the JDK's own IllegalArgumentException quotes the rejected value, and that message reaches
        // the log and the tool result - neither the message nor a cause may carry the secret
        assertThatThrownBy(() -> service.applyHeaders(request, server))
                .isInstanceOf(KeyResolveException.class)
                .hasMessageContaining("X-Api-Key")
                .hasMessageNotContaining(SECRET_WITH_LINE_BREAK)
                .hasMessageNotContaining("s3cret-first-line")
                .hasNoCause();
    }

    @Test
    void applyHeaders_doesNotLeakTheSecretWhenAResolvedAuthorizationTokenIsInvalid() {
        McpServerDefinition server = server("""
                [{"name":"github","type":"url","url":"https://example.test/mcp",
                  "authorization_token":"${env:MCP_BROKEN}"}]
                """);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://example.test/mcp")).GET();

        assertThatThrownBy(() -> service.applyHeaders(request, server))
                .isInstanceOf(KeyResolveException.class)
                .hasMessageContaining("Authorization")
                .hasMessageNotContaining("s3cret-first-line")
                .hasNoCause();
    }

    @Test
    void resolveTransportEndpoint_streamableHttpUsesConfiguredMcpEndpointWithoutTrailingSlash() {
        McpOrchestrationService.TransportEndpoint endpoint = service.resolveTransportEndpoint(
                new McpServerDefinition("github", "url", "https://api.githubcopilot.com/mcp/", null, Map.of()));

        assertFalse(endpoint.sse());
        assertEquals("https://api.githubcopilot.com", endpoint.baseUri());
        assertEquals("/mcp", endpoint.endpoint());
    }

    @Test
    void resolveTransportEndpoint_streamableHttpPreservesNestedEndpointPath() {
        McpOrchestrationService.TransportEndpoint endpoint = service.resolveTransportEndpoint(
                new McpServerDefinition("docs", "streamable-http", "https://example.test/api/mcp", null, Map.of()));

        assertFalse(endpoint.sse());
        assertEquals("https://example.test", endpoint.baseUri());
        assertEquals("/api/mcp", endpoint.endpoint());
    }

    @Test
    void resolveTransportEndpoint_sseUsesExplicitSseEndpointPath() {
        McpOrchestrationService.TransportEndpoint endpoint = service.resolveTransportEndpoint(
                new McpServerDefinition("docs", "sse", "https://example.test/api/sse", null, Map.of()));

        assertTrue(endpoint.sse());
        assertEquals("https://example.test", endpoint.baseUri());
        assertEquals("/api/sse", endpoint.endpoint());
    }

    @Test
    void resolveTransportEndpoint_sseAppendsDefaultEndpointUnderConfiguredBasePath() {
        McpOrchestrationService.TransportEndpoint endpoint = service.resolveTransportEndpoint(
                new McpServerDefinition("docs", "sse", "https://example.test/api", null, Map.of()));

        assertTrue(endpoint.sse());
        assertEquals("https://example.test", endpoint.baseUri());
        assertEquals("/api/sse", endpoint.endpoint());
    }

    @Test
    void connectionAttempts_streamableHttpTriesConfiguredTrailingSlashBeforeNormalizedEndpoint() {
        List<McpOrchestrationService.McpConnectionAttempt> attempts = service.connectionAttempts(
                new McpServerDefinition("github", "url", "https://api.githubcopilot.com/mcp/", null, Map.of()));

        assertEquals("/mcp/", attempts.get(0).endpoint().endpoint());
        assertEquals("https://api.githubcopilot.com", attempts.get(0).endpoint().baseUri());
        assertEquals("/mcp/", attempts.get(1).endpoint().endpoint());
        assertEquals(HttpClient.Version.HTTP_1_1, attempts.get(1).httpVersion());
        assertTrue(attempts.stream().anyMatch(attempt -> "/mcp".equals(attempt.endpoint().endpoint())));
    }

    @Test
    void connectionAttempts_streamableHttpFallsBackToOlderProtocolVersions() {
        List<McpOrchestrationService.McpConnectionAttempt> attempts = service.connectionAttempts(
                new McpServerDefinition("github", "url", "https://api.githubcopilot.com/mcp/", null, Map.of()));

        assertEquals(List.of("2024-11-05", "2025-03-26", "2025-06-18"), attempts.get(0).protocolVersions());
        assertTrue(attempts.stream().anyMatch(attempt -> attempt.protocolVersions().equals(List.of("2024-11-05", "2025-03-26"))));
        assertTrue(attempts.stream().anyMatch(attempt -> attempt.protocolVersions().equals(List.of("2024-11-05"))));
    }

    /** Runs the JSON through the same encrypt/parse path the orchestration uses at call time. */
    private McpServerDefinition server(String json) {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setJsonContent(encryptionService.encrypt(json));
        return service.discoverServers(configuration).getFirst();
    }
}
