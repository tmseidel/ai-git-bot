package org.remus.giteabot.agent.shared;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JSON-schema surface published to the AI provider for native
 * tool calling (Step 6 follow-up). Acts as a regression net so a future
 * addition / rename of a tool can't silently disappear from the API surface.
 */
class AgentNativeToolsTest {

    @Test
    void codingTools_exposeFullCodingSurface() {
        List<ToolDescriptor> tools = AgentNativeTools.codingTools(McpToolCatalog.empty());
        Set<String> names = tools.stream().map(ToolDescriptor::name).collect(Collectors.toSet());
        // File-mutation
        assertThat(names).contains("write-file", "patch-file", "mkdir", "delete-file");
        // Repository exploration (shared with writer)
        assertThat(names).contains("branch-switcher", "rg", "find", "cat", "git-log", "git-blame", "tree");
        // Validation
        assertThat(names).contains("mvn", "gradle", "npm", "dotnet", "cargo", "go", "python3", "make", "cmake");
        // Every descriptor has a non-null object-typed schema.
        for (ToolDescriptor d : tools) {
            assertThat(d.jsonSchema()).as("schema for %s", d.name()).isNotNull();
            assertThat(d.jsonSchema().get("type").asString()).isEqualTo("object");
        }
    }

    @Test
    void writerTools_areReadOnlySurface() {
        List<ToolDescriptor> tools = AgentNativeTools.writerTools(McpToolCatalog.empty());
        Set<String> names = tools.stream().map(ToolDescriptor::name).collect(Collectors.toSet());
        // Read-only repo exploration + issue lookup
        assertThat(names).contains("branch-switcher", "rg", "find", "cat", "git-log", "git-blame", "tree",
                "get-issue", "search-issues");
        // MUST NOT expose file mutations or validation runners — the writer is read-only.
        assertThat(names).doesNotContain("write-file", "patch-file", "mkdir", "delete-file",
                "mvn", "gradle", "npm", "dotnet", "cargo", "go", "python3", "make", "cmake");
    }

    @Test
    void patchFile_schemaHasRequiredTypedProperties() {
        ToolDescriptor patch = findByName(AgentNativeTools.codingTools(McpToolCatalog.empty()), "patch-file");
        assertThat(patch.jsonSchema().get("properties").get("path").get("type").asString()).isEqualTo("string");
        assertThat(patch.jsonSchema().get("properties").get("search").get("type").asString()).isEqualTo("string");
        assertThat(patch.jsonSchema().get("properties").get("replacement").get("type").asString()).isEqualTo("string");
        List<String> required = StreamSupport.toStringList(patch.jsonSchema().get("required"));
        assertThat(required).containsExactlyInAnyOrder("path", "search", "replacement");
    }

    @Test
    void varargsTool_hasArgsArrayProperty() {
        ToolDescriptor mvn = findByName(AgentNativeTools.codingTools(McpToolCatalog.empty()), "mvn");
        assertThat(mvn.jsonSchema().get("properties").get("args").get("type").asString()).isEqualTo("array");
        assertThat(mvn.jsonSchema().get("properties").get("args").get("items").get("type").asString())
                .isEqualTo("string");
    }

    @Test
    void mcpCatalog_isMergedIntoBothSurfaces() {
        McpToolDefinition mcpDef = new McpToolDefinition(
                "fake-server", "do-thing", "Do Thing", "Does a thing",
                Map.of("type", "object"), "fake-server.do-thing");
        McpToolCatalog catalog = new McpToolCatalog(List.of(mcpDef));

        Set<String> codingNames = AgentNativeTools.codingTools(catalog).stream()
                .map(ToolDescriptor::name).collect(Collectors.toSet());
        Set<String> writerNames = AgentNativeTools.writerTools(catalog).stream()
                .map(ToolDescriptor::name).collect(Collectors.toSet());

        assertThat(codingNames).contains("fake-server.do-thing");
        assertThat(writerNames).contains("fake-server.do-thing");
    }

    private static ToolDescriptor findByName(List<ToolDescriptor> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Tool " + name + " not in descriptor list"));
    }

    /** Tiny helper to convert a Jackson array node of strings into a List<String>. */
    private static final class StreamSupport {
        static List<String> toStringList(tools.jackson.databind.JsonNode arrayNode) {
            List<String> out = new java.util.ArrayList<>();
            if (arrayNode != null && arrayNode.isArray()) {
                arrayNode.forEach(n -> out.add(n.asString()));
            }
            return out;
        }
    }
}

