package org.remus.giteabot.mcp;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.systemsettings.McpConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigurationParserTest {

    private final McpConfigurationParser parser = new McpConfigurationParser();

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
        assertEquals("token", servers.getFirst().authorizationToken());
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
        assertEquals("true", servers.getFirst().headers().get("X-Test"));
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

        List<McpServerDefinition> servers = discovery.discover(configuration);

        assertEquals(1, servers.size());
        assertEquals("remote", servers.getFirst().name());
    }
}

