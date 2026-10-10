package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.agent.codeexecution.ProcessPythonExecutionService;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRouterTruncationTest {

    @TempDir
    Path workspace;

    @Test
    void executeCodeRetainsByteTruncationAfterUtf8BoundaryTrimming() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not installed");
        AgentConfigProperties config = new AgentConfigProperties();
        config.getBudget().setMaxToolResultChars(8_000);

        ToolResult result = executeCode(config, "print('x' + chr(0x4e00) * 4000, end='')");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).startsWith("x一");
        assertThat(result.output().contains("[output truncated at 9024 bytes]"))
                .as("byte truncation remains visible after UTF-8 boundary trimming").isTrue();
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.formatForAi()).contains("not complete evidence");
    }

    @Test
    void executeCodeDoesNotMarkExactByteLimitOutputAsTruncated() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not installed");
        AgentConfigProperties config = new AgentConfigProperties();
        config.getBudget().setMaxToolResultChars(8_000);

        ToolResult result = executeCode(config, "print(chr(0x4e00) * 3008, end='')");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("一".repeat(3008));
        assertThat(result.outputTruncated()).isFalse();
        assertThat(result.formatForAi()).doesNotContain("not complete evidence");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executeCodePreservesCharacterTruncationOnBothSuccessAndFailure(boolean failed) {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not installed");
        AgentConfigProperties config = new AgentConfigProperties();
        config.getBudget().setMaxToolResultChars(8_000);

        ToolResult result = executeCode(config,
                "print('x' * 12000, end='')" + (failed ? "; raise SystemExit(7)" : ""));

        assertThat(result.success()).isEqualTo(!failed);
        assertThat(result.exitCode()).isEqualTo(failed ? 7 : 0);
        assertThat(result.output()).contains("[output truncated at 8000 chars]");
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.formatForAi()).contains("not complete evidence");
    }

    @Test
    void aRealProgramSeesTheNestedLocalTruncationFlagAndWarning() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/python3")), "python3 is not installed");
        Files.writeString(workspace.resolve("Large.txt"), "x".repeat(11_000));
        AgentConfigProperties config = new AgentConfigProperties();
        config.getBudget().setMaxToolResultChars(20_000);

        ToolResult result = executeCode(config, """
                result = tools.call("cat", {"path": "Large.txt"})
                print("truncated", result["outputTruncated"])
                print(result["output"])
                """);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("truncated True", "not complete evidence");
        assertThat(result.outputTruncated()).isFalse();
    }

    private ToolResult executeCode(AgentConfigProperties config, String code) {
        ToolCatalog catalog = new ToolCatalog(config);
        ToolExecutionService tools = new ToolExecutionService(config, catalog, new WorkspaceService());
        AgentToolRouter router = new AgentToolRouter(tools, catalog, null, null, McpToolCatalog.empty(),
                null, Set.of("execute-code", "cat"), new ProcessPythonExecutionService(config));
        ToolCallContext context = new ToolCallContext("owner", "repo", 1L, workspace,
                ImplementationPlan.ToolRequest.builder().id("run-1").tool("execute-code")
                        .args(List.of(code)).build(), null);
        return router.execute(AgentToolRouter.Mode.CODING, context);
    }
}
