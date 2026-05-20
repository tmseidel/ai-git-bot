package org.remus.giteabot.prworkflow.deployment.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpDeploymentConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesMinimalConfig() {
        McpDeploymentConfig cfg = McpDeploymentConfig.parse(
                "{ \"mcpConfigurationId\": 7, \"deployTool\": \"platform_deploy\" }", objectMapper);

        assertThat(cfg.mcpConfigurationId()).isEqualTo(7L);
        assertThat(cfg.deployTool()).isEqualTo("platform_deploy");
        assertThat(cfg.optionalStatusTool()).isEmpty();
        assertThat(cfg.optionalTeardownTool()).isEmpty();
        assertThat(cfg.argsTemplate()).isEmpty();
    }

    @Test
    void parsesFullConfig() {
        String json = """
                {
                  "mcpConfigurationId": 9,
                  "deployTool": "deploy",
                  "statusTool": "status",
                  "teardownTool": "teardown",
                  "argsTemplate": { "branch": "{branch}", "sha": "{sha}" }
                }
                """;

        McpDeploymentConfig cfg = McpDeploymentConfig.parse(json, objectMapper);

        assertThat(cfg.optionalStatusTool()).contains("status");
        assertThat(cfg.optionalTeardownTool()).contains("teardown");
        assertThat(cfg.argsTemplate()).containsEntry("branch", "{branch}");
    }

    @Test
    void rejectsMissingMcpConfigurationId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpDeploymentConfig.parse(
                        "{ \"deployTool\": \"x\" }", objectMapper))
                .withMessageContaining("mcpConfigurationId");
    }

    @Test
    void rejectsMissingDeployTool() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpDeploymentConfig.parse(
                        "{ \"mcpConfigurationId\": 1 }", objectMapper))
                .withMessageContaining("deployTool");
    }

    @Test
    void rejectsArgsTemplateThatIsNotAnObject() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpDeploymentConfig.parse(
                        "{ \"mcpConfigurationId\": 1, \"deployTool\": \"x\", \"argsTemplate\": [] }",
                        objectMapper))
                .withMessageContaining("argsTemplate");
    }

    @Test
    void rejectsInvalidJson() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpDeploymentConfig.parse("not-json", objectMapper));
    }

    @Test
    void rejectsBlankConfig() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpDeploymentConfig.parse("", objectMapper));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> McpDeploymentConfig.parse(null, objectMapper));
    }

    @Test
    void handleJsonRoundTripsToolNamesAndRemoteHandle() {
        McpDeploymentConfig cfg = new McpDeploymentConfig(
                42L, "deploy", "status", "teardown", Map.of());

        String json = cfg.handleJson(objectMapper, Map.of("opaque", "value"));

        assertThat(json).contains("\"strategy\":\"MCP\"")
                .contains("\"mcpConfigurationId\":42")
                .contains("\"deployTool\":\"deploy\"")
                .contains("\"statusTool\":\"status\"")
                .contains("\"teardownTool\":\"teardown\"")
                .contains("\"remote\":{\"opaque\":\"value\"}");
    }

    @Test
    void handleJsonOmitsOptionalToolsWhenAbsent() {
        McpDeploymentConfig cfg = new McpDeploymentConfig(
                1L, "deploy", null, "", Map.of());

        String json = cfg.handleJson(objectMapper, Map.of());

        assertThat(json).contains("\"deployTool\":\"deploy\"")
                .doesNotContain("statusTool")
                .doesNotContain("teardownTool")
                .doesNotContain("remote");
    }
}

