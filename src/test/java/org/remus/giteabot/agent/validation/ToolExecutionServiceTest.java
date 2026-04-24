package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

    private ToolExecutionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ToolExecutionService(new AgentConfigProperties());
    }

    @Test
    void getAvailableContextTools_containsExpectedTools() {
        assertThat(service.getAvailableContextTools())
                .contains("rg", "grep", "find", "cat", "git-log", "git-blame", "tree");
    }

    @Test
    void executeContextTool_cat_readsRequestedRangeWithLineNumbers() throws IOException {
        Path file = tempDir.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                line 1
                line 2
                line 3
                """);

        ToolResult result = service.executeContextTool(tempDir, "cat", List.of("src/Main.java", "2", "3"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("2 | line 2");
        assertThat(result.output()).contains("3 | line 3");
        assertThat(result.output()).doesNotContain("1 | line 1");
    }

    @Test
    void executeContextTool_rg_searchesWorkspace() throws IOException {
        Path file = tempDir.resolve("src/Config.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                class Config {
                    private ConfigService service;
                }
                """);

        ToolResult result = service.executeContextTool(tempDir, "rg", List.of("ConfigService", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/Config.java:2:     private ConfigService service;");
    }

    @Test
    void executeContextTool_find_matchesGlobPattern() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/app.yml"), "name: app");
        Files.writeString(tempDir.resolve("src/Config.java"), "class Config {}");

        ToolResult result = service.executeContextTool(tempDir, "find", List.of("*.yml", "src"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("src/app.yml");
    }
}
