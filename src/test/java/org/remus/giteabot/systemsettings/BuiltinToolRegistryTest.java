package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.tools.ToolKind;
import org.remus.giteabot.config.AgentConfigProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinToolRegistryTest {

    private BuiltinToolRegistry registry() {
        AgentConfigProperties agentConfig = new AgentConfigProperties();
        agentConfig.getValidation().setAvailableTools(List.of("mvn", "npm"));
        return new BuiltinToolRegistry(new ToolCatalog(agentConfig));
    }

    @Test
    void builtinTools_containsFileContextValidationAndWriterRepositoryTools() {
        List<BuiltinToolRegistry.BuiltinTool> tools = registry().builtinTools();
        assertNotNull(tools);
        List<String> names = tools.stream().map(BuiltinToolRegistry.BuiltinTool::name).toList();

        assertTrue(names.contains("write-file"));
        assertTrue(names.contains("cat"));
        assertTrue(names.contains("mvn"));
        assertTrue(names.contains("get-issue"));
    }

    @Test
    void builtinTools_namesAreLowercaseAndUnique() {
        List<BuiltinToolRegistry.BuiltinTool> tools = registry().builtinTools();
        long distinct = tools.stream().map(BuiltinToolRegistry.BuiltinTool::name).distinct().count();
        assertEquals(tools.size(), distinct, "names must be unique");
        tools.forEach(t -> assertEquals(t.name(), t.name().toLowerCase()));
    }

    @Test
    void builtinTools_classifyByKind() {
        List<BuiltinToolRegistry.BuiltinTool> tools = registry().builtinTools();
        BuiltinToolRegistry.BuiltinTool mvn = tools.stream()
                .filter(t -> t.name().equals("mvn")).findFirst().orElseThrow();
        assertEquals(ToolKind.VALIDATION, mvn.kind());

        BuiltinToolRegistry.BuiltinTool writeFile = tools.stream()
                .filter(t -> t.name().equals("write-file")).findFirst().orElseThrow();
        assertEquals(ToolKind.FILE, writeFile.kind());

        BuiltinToolRegistry.BuiltinTool getIssue = tools.stream()
                .filter(t -> t.name().equals("get-issue")).findFirst().orElseThrow();
        assertEquals(ToolKind.REPOSITORY, getIssue.kind());
    }
}
