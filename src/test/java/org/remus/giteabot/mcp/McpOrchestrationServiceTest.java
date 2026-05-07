package org.remus.giteabot.mcp;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOrchestrationServiceTest {

    private final McpOrchestrationService service = new McpOrchestrationService();

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
}

