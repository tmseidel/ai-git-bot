package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.remus.giteabot.agent.issueimpl.AiResponseParser;
import org.remus.giteabot.agent.loop.AgentRunContext;
import org.remus.giteabot.agent.loop.StepDecision;
import org.remus.giteabot.agent.loop.ToolingMode;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.tools.AgentToolRouter;
import org.remus.giteabot.agent.tools.ToolCatalog;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.mcp.McpToolCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewToolTruncationTest {

    @TempDir
    Path workspace;

    static Stream<Arguments> outputModes() {
        return Stream.of(Arguments.of(ToolingMode.NATIVE, false), Arguments.of(ToolingMode.NATIVE, true),
                Arguments.of(ToolingMode.LEGACY, false), Arguments.of(ToolingMode.LEGACY, true));
    }

    @ParameterizedTest
    @MethodSource("outputModes")
    void onlyShortenedToolOutputIsMarkedIncompleteInReviewContext(ToolingMode mode, boolean truncated)
            throws Exception {
        Files.writeString(workspace.resolve("Content.txt"), truncated ? "x".repeat(11_000) : "complete content");
        AgentConfigProperties config = new AgentConfigProperties();
        ToolCatalog catalog = new ToolCatalog(config);
        ToolExecutionService tools = new ToolExecutionService(config, catalog, new WorkspaceService());
        Set<String> allowed = Set.of("cat");
        AgentToolRouter router = new AgentToolRouter(tools, catalog, null, null, McpToolCatalog.empty(),
                null, allowed, null);
        ReviewAgentStrategy strategy = new ReviewAgentStrategy("system", router, catalog, McpToolCatalog.empty(),
                allowed, new AiResponseParser(), null, null, 5, 2);
        AgentRunContext context = new AgentRunContext(null, "owner", "repo", 1L, workspace, "main");

        String rendered;
        if (mode == ToolingMode.NATIVE) {
            ChatTurn turn = new ChatTurn("", List.of(new ToolCall("read-1", "cat",
                    AgentJackson.mapper().createObjectNode().put("path", "Content.txt"))), StopReason.TOOL_USE, 0, 0);
            StepDecision decision = strategy.step(context, turn, 1);
            assertThat(decision).isInstanceOf(StepDecision.ContinueWithToolResults.class);
            rendered = ((StepDecision.ContinueWithToolResults) decision).results().getFirst().resultText();
        } else {
            ChatTurn turn = ChatTurn.text("""
                    {"requestTools":[{"tool":"cat","args":["Content.txt"]}]}
                    """);
            StepDecision decision = strategy.stepLegacy(context, turn, 1);
            assertThat(decision).isInstanceOf(StepDecision.Continue.class);
            rendered = ((StepDecision.Continue) decision).nextUserMessage();
        }

        assertThat(rendered).contains("1 | ");
        assertThat(rendered.contains("not complete evidence")).isEqualTo(truncated);
        if (!truncated) {
            assertThat(rendered).contains("complete content");
        }
    }
}
