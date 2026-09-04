package org.remus.giteabot.mcp;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.secret.FakeSecretSource;
import org.remus.giteabot.secret.SecretSourceRegistry;
import org.remus.giteabot.secret.SecretTemplateParser;
import org.remus.giteabot.systemsettings.McpConfiguration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigurationParserTest {

    private final McpConfigurationParser parser = new McpConfigurationParser(
            new SecretTemplateParser(new SecretSourceRegistry(List.of(FakeSecretSource.of("env", Map.of("MCP_TOKEN", "s3cret", "MCP_HEADER", "header-value"))))));

    @Test
    void parse_supportsUiArrayFormatWithAuthorizationToken() {
        List<McpServerDefinition> servers = parser.parse("""
                [
                  {
                    "name": "github",
                    "type": "url",
                    "url": "https://api.githubcopilot.com/mcp/",
                    "authorization_token": "token"
                  }
                ]
                """);

        assertEquals(1, servers.size());
        assertEquals("github", servers.getFirst().name());
        assertEquals("url", servers.getFirst().type());
        assertEquals("https://api.githubcopilot.com/mcp/", servers.getFirst().url());
        assertEquals("token", servers.getFirst().authorizationToken().expose());
    }

    @Test
    void parse_supportsMcpServersObjectFormatAndHeaders() {
        List<McpServerDefinition> servers = parser.parse("""
                {
                  "mcpServers": {
                    "docs": {
                      "transport": "sse",
                      "endpoint": "https://example.test/sse",
                      "headers": {"X-Test": "true"}
                    }
                  }
                }
                """);

        assertEquals(1, servers.size());
        assertEquals("docs", servers.getFirst().name());
        assertEquals("sse", servers.getFirst().type());
        assertEquals("https://example.test/sse", servers.getFirst().url());
        assertEquals("true", servers.getFirst().headers().get("X-Test").expose());
    }

    @Test
    void parse_keepsSecretReferencesUnresolvedUntilTheyAreExposed() {
        List<McpServerDefinition> servers = parser.parse("""
                [
                  {
                    "name": "github",
                    "type": "url",
                    "url": "https://example.test/mcp",
                    "authorization_token": "${env:MCP_TOKEN}",
                    "headers": {"X-Api-Key": "prefix-${env:MCP_HEADER}"}
                  }
                ]
                """);

        McpServerDefinition server = servers.getFirst();
        assertEquals("${env:MCP_TOKEN}", server.authorizationToken().raw());
        assertEquals("s3cret", server.authorizationToken().expose());
        assertEquals("prefix-${env:MCP_HEADER}", server.headers().get("X-Api-Key").raw());
        assertEquals("prefix-header-value", server.headers().get("X-Api-Key").expose());
    }

    @Test
    void parse_keepsLiteralValueOfEscapedSecretReference() {
        List<McpServerDefinition> servers = parser.parse("""
                [
                  {
                    "name": "github",
                    "type": "url",
                    "url": "https://example.test/mcp",
                    "authorization_token": "$${env:MCP_TOKEN}"
                  }
                ]
                """);

        assertEquals("${env:MCP_TOKEN}", servers.getFirst().authorizationToken().expose());
    }

    @Test
    void serverDiscovery_filtersDefinitionsWithoutRemoteUrl() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setJsonContent("""
                [
                  {"name":"missing-url","type":"url"},
                  {"name":"remote","type":"url","url":"https://example.test/mcp"}
                ]
                """);
        McpServerDiscovery discovery = new McpServerDiscovery(parser);

        List<McpServerDefinition> servers = discovery.discover(configuration.getJsonContent());

        assertEquals(1, servers.size());
        assertEquals("remote", servers.getFirst().name());
    }
}
