package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.mcp.McpToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    private ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new ToolCatalog(new AgentConfigProperties());
    }

    @Test
    void contextToolsAreClassifiedAsContext() {
        for (String name : catalog.contextToolNames()) {
            assertThat(catalog.kindOf(name)).as(name).isEqualTo(ToolKind.CONTEXT);
            assertThat(catalog.isContext(name)).isTrue();
            assertThat(catalog.isFile(name)).isFalse();
            assertThat(catalog.isValidation(name)).isFalse();
            assertThat(catalog.isSilent(name)).isTrue();
        }
    }

    @Test
    void fileToolsAreClassifiedAsFile() {
        for (String name : catalog.fileToolNames()) {
            assertThat(catalog.kindOf(name)).as(name).isEqualTo(ToolKind.FILE);
            assertThat(catalog.isFile(name)).isTrue();
            assertThat(catalog.isSilent(name)).isTrue();
        }
    }

    @Test
    void validationToolsAreClassifiedAsValidation() {
        // mvn / gradle / npm / dotnet etc. come from AgentConfigProperties defaults
        assertThat(catalog.kindOf("mvn")).isEqualTo(ToolKind.VALIDATION);
        assertThat(catalog.isValidation("mvn")).isTrue();
        assertThat(catalog.isSilent("mvn")).isFalse();
    }

    @Test
    void mcpPrefixedToolIsMcpKind() {
        assertThat(catalog.kindOf("mcp:github:list_issues")).isEqualTo(ToolKind.MCP);
        assertThat(catalog.isMcp("mcp:github:list_issues")).isTrue();
        assertThat(catalog.isSilent("mcp:github:list_issues")).isTrue();
    }

    @Test
    void writerRepositoryToolsAreClassifiedAsRepository() {
        assertThat(catalog.kindOf("get-issue")).isEqualTo(ToolKind.REPOSITORY);
        assertThat(catalog.kindOf("search-issues")).isEqualTo(ToolKind.REPOSITORY);
        assertThat(catalog.isSilent("get-issue")).isTrue();
    }

    @Test
    void unknownToolIsUnknown() {
        assertThat(catalog.kindOf("does-not-exist")).isEqualTo(ToolKind.UNKNOWN);
        assertThat(catalog.kindOf(null)).isEqualTo(ToolKind.UNKNOWN);
        assertThat(catalog.kindOf("  ")).isEqualTo(ToolKind.UNKNOWN);
        // Important: unknown is NOT silent (we want to surface it, e.g. as "Will run").
        assertThat(catalog.isSilent("does-not-exist")).isFalse();
    }

    @Test
    void bucketOfMapsCorrectly() {
        assertThat(catalog.bucketOf("cat")).isEqualTo(ToolCatalog.DisplayBucket.CONTEXT);
        assertThat(catalog.bucketOf("mcp:github:list_issues")).isEqualTo(ToolCatalog.DisplayBucket.CONTEXT);
        assertThat(catalog.bucketOf("get-issue")).isEqualTo(ToolCatalog.DisplayBucket.CONTEXT);
        assertThat(catalog.bucketOf("write-file")).isEqualTo(ToolCatalog.DisplayBucket.MUTATION);
        assertThat(catalog.bucketOf("patch-file")).isEqualTo(ToolCatalog.DisplayBucket.MUTATION);
        assertThat(catalog.bucketOf("mvn")).isEqualTo(ToolCatalog.DisplayBucket.VALIDATION);
        assertThat(catalog.bucketOf("does-not-exist")).isEqualTo(ToolCatalog.DisplayBucket.VALIDATION);
    }

    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(catalog.kindOf(" WRITE-FILE ")).isEqualTo(ToolKind.FILE);
        assertThat(catalog.kindOf("CAT")).isEqualTo(ToolKind.CONTEXT);
    }

    // ---------- Native-descriptor surface (moved here from the deleted AgentNativeToolsTest) ----------

    @Test
    void nativeDescriptors_coding_exposesFullCodingSurface() {
        List<ToolDescriptor> tools = catalog.nativeDescriptors(ToolCatalog.Role.CODING, McpToolCatalog.empty(), null);
        Set<String> names = tools.stream().map(ToolDescriptor::name).collect(Collectors.toSet());
        assertThat(names).contains("write-file", "patch-file", "mkdir", "delete-file");
        assertThat(names).contains("branch-switcher", "rg", "find", "cat", "git-log", "git-blame", "tree");
        // Validation tools come from AgentConfigProperties defaults.
        for (String t : new AgentConfigProperties().getValidation().getAvailableTools()) {
            assertThat(names).as("validation tool exposed: %s", t).contains(t);
        }
        // Every descriptor has a non-null object schema.
        for (ToolDescriptor d : tools) {
            assertThat(d.jsonSchema()).as("schema for %s", d.name()).isNotNull();
            assertThat(d.jsonSchema().get("type").asString()).isEqualTo("object");
        }
    }

    @Test
    void nativeDescriptors_writer_isReadOnlySurface() {
        List<ToolDescriptor> tools = catalog.nativeDescriptors(ToolCatalog.Role.WRITER, McpToolCatalog.empty(), null);
        Set<String> names = tools.stream().map(ToolDescriptor::name).collect(Collectors.toSet());
        assertThat(names).contains("branch-switcher", "rg", "find", "cat", "git-log", "git-blame", "tree",
                "get-issue", "search-issues");
        // No mutations, no validation on writer.
        assertThat(names).doesNotContain("write-file", "patch-file", "mkdir", "delete-file",
                "mvn", "gradle", "npm", "dotnet", "cargo", "go", "python3", "make", "cmake");
    }

    @Test
    void nativeDescriptors_patchFileSchemaHasRequiredTypedProperties() {
        ToolDescriptor patch = catalog.nativeDescriptors(ToolCatalog.Role.CODING, McpToolCatalog.empty(), null)
                .stream().filter(d -> d.name().equals("patch-file")).findFirst().orElseThrow();
        assertThat(patch.jsonSchema().get("properties").get("path").get("type").asString()).isEqualTo("string");
        assertThat(patch.jsonSchema().get("properties").get("search").get("type").asString()).isEqualTo("string");
        assertThat(patch.jsonSchema().get("properties").get("replacement").get("type").asString()).isEqualTo("string");
        var required = patch.jsonSchema().get("required");
        assertThat(required.isArray()).isTrue();
        assertThat(required.size()).isEqualTo(3);
    }

    @Test
    void nativeDescriptors_varargsToolHasArgsArray() {
        ToolDescriptor mvn = catalog.nativeDescriptors(ToolCatalog.Role.CODING, McpToolCatalog.empty(), null)
                .stream().filter(d -> d.name().equals("mvn")).findFirst().orElseThrow();
        assertThat(mvn.jsonSchema().get("properties").get("args").get("type").asString()).isEqualTo("array");
        assertThat(mvn.jsonSchema().get("properties").get("args").get("items").get("type").asString())
                .isEqualTo("string");
    }

    @Test
    void nativeDescriptors_mcpToolsAreMergedIntoBothSurfaces() {
        McpToolDefinition mcpDef = new McpToolDefinition(
                "fake-server", "do-thing", "Do Thing", "Does a thing",
                Map.of("type", "object"), "fake-server.do-thing");
        McpToolCatalog mcp = new McpToolCatalog(List.of(mcpDef));
        Set<String> coding = catalog.nativeDescriptors(ToolCatalog.Role.CODING, mcp, null).stream()
                .map(ToolDescriptor::name).collect(Collectors.toSet());
        Set<String> writer = catalog.nativeDescriptors(ToolCatalog.Role.WRITER, mcp, null).stream()
                .map(ToolDescriptor::name).collect(Collectors.toSet());
        assertThat(coding).contains("fake-server.do-thing");
        assertThat(writer).contains("fake-server.do-thing");
    }

    @Test
    void nativeDescriptors_unknownValidationToolGetsGenericDescription() {
        AgentConfigProperties cfg = new AgentConfigProperties();
        cfg.getValidation().setAvailableTools(List.of("kubectl"));
        ToolCatalog c2 = new ToolCatalog(cfg);
        ToolDescriptor kubectl = c2.nativeDescriptors(ToolCatalog.Role.CODING, McpToolCatalog.empty(), null)
                .stream().filter(d -> d.name().equals("kubectl")).findFirst().orElseThrow();
        assertThat(kubectl.description()).contains("kubectl");
    }

    // ---------- Built-in tool whitelist filtering (PR 4) ----------

    @Test
    void nativeDescriptors_whitelist_filtersBuiltinAndValidationButKeepsMcp() {
        McpToolDefinition mcpDef = new McpToolDefinition(
                "fake-server", "do-thing", "Do Thing", "Does a thing",
                Map.of("type", "object"), "fake-server.do-thing");
        McpToolCatalog mcp = new McpToolCatalog(List.of(mcpDef));
        Set<String> allowed = Set.of("cat", "rg", "mvn"); // arbitrary subset
        List<ToolDescriptor> coding = catalog.nativeDescriptors(ToolCatalog.Role.CODING, mcp, allowed);
        Set<String> names = coding.stream().map(ToolDescriptor::name).collect(Collectors.toSet());
        assertThat(names).contains("cat", "rg", "mvn", "fake-server.do-thing");
        assertThat(names).doesNotContain("write-file", "patch-file", "mkdir", "delete-file",
                "find", "tree", "git-log", "git-blame", "branch-switcher", "gradle", "npm");
    }

    @Test
    void nativeDescriptors_emptyWhitelist_returnsOnlyMcp() {
        McpToolDefinition mcpDef = new McpToolDefinition(
                "srv", "tool", "T", "d", Map.of("type", "object"), "srv.tool");
        McpToolCatalog mcp = new McpToolCatalog(List.of(mcpDef));
        List<ToolDescriptor> coding = catalog.nativeDescriptors(
                ToolCatalog.Role.CODING, mcp, Set.of());
        assertThat(coding).extracting(ToolDescriptor::name).containsExactly("srv.tool");
    }

    @Test
    void nameAccessors_whitelist_intersectAgainstAllowedSet() {
        Set<String> allowed = Set.of("write-file", "cat", "mvn", "get-issue");
        assertThat(catalog.fileToolNames(allowed)).containsExactly("write-file");
        assertThat(catalog.contextToolNames(allowed)).containsExactly("cat");
        assertThat(catalog.validationToolNames(allowed)).containsExactly("mvn");
        assertThat(catalog.writerRepositoryToolNames(allowed)).containsExactly("get-issue");
    }

    @Test
    void nameAccessors_nullWhitelist_returnsAllNames() {
        assertThat(catalog.fileToolNames(null)).isEqualTo(catalog.fileToolNames());
        assertThat(catalog.contextToolNames(null)).isEqualTo(catalog.contextToolNames());
        assertThat(catalog.validationToolNames(null)).isEqualTo(catalog.validationToolNames());
        assertThat(catalog.writerRepositoryToolNames(null)).isEqualTo(catalog.writerRepositoryToolNames());
    }
}
