package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTruncationTest {

    @TempDir
    Path workspace;

    private final AgentConfigProperties config = new AgentConfigProperties();
    private final ToolExecutionService service = new ToolExecutionService(config,
            new ToolCatalog(config), new WorkspaceService());

    @ParameterizedTest
    @ValueSource(strings = {"ctags-signatures", "ctags-deps"})
    void formattedCtagsResultsRetainTheCaptureTruncationWarning(String tool) throws Exception {
        Assumptions.assumeTrue(jsonCtagsAvailable(), "Universal Ctags with JSON support is not installed");
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            source.append("import aaa.pkg.Dependency").append(i).append(";\n");
        }
        source.append("public class Many {\n");
        for (int i = 0; i < 200; i++) {
            source.append("public void method").append(i).append("() {}\n");
        }
        source.append("}\n");
        Files.writeString(workspace.resolve("Many.java"), source);

        ToolResult result = service.executeContextTool(workspace, tool, List.of("Many.java", "500"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains(tool.equals("ctags-deps") ? "Dependency" : "class Many");
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.formatForAi()).contains("not complete evidence");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ctags-signatures", "ctags-deps"})
    void completeCtagsResultsDoNotReceiveATruncationWarning(String tool) throws Exception {
        Assumptions.assumeTrue(jsonCtagsAvailable(), "Universal Ctags with JSON support is not installed");
        Files.writeString(workspace.resolve("Small.java"), "import java.util.List;\npublic class Small {}\n");

        ToolResult result = service.executeContextTool(workspace, tool, List.of("Small.java"));

        assertThat(result.success()).isTrue();
        assertThat(result.outputTruncated()).isFalse();
        assertThat(result.formatForAi()).doesNotContain("not complete evidence");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contextToolsReportCharacterPresentationTruncation(boolean truncated) throws Exception {
        Files.writeString(workspace.resolve("Content.txt"), truncated ? "x".repeat(11_000) : "complete content");

        ToolResult result = service.executeContextTool(workspace, "cat", List.of("Content.txt"));

        assertThat(result.success()).isTrue();
        assertThat(result.outputTruncated()).isEqualTo(truncated);
        assertThat(result.formatForAi().contains("not complete evidence")).isEqualTo(truncated);
    }

    @Test
    void processToolsRetainByteTruncationBelowTheCharacterPresentationCap() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not installed");

        ToolResult result = service.executeTool(workspace, "python3",
                List.of("-c", "print('x' + chr(0x4e00) * 4000, end='')"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).hasSizeLessThan(10_000).doesNotContain("output truncated");
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.formatForAi()).contains("not complete evidence");
    }

    private static boolean jsonCtagsAvailable() throws IOException, InterruptedException {
        try {
            Process process = new ProcessBuilder("ctags", "--list-features").redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).contains("json");
        } catch (IOException e) {
            return false;
        }
    }
}
