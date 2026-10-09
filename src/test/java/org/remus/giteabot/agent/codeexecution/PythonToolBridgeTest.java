package org.remus.giteabot.agent.codeexecution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.agent.validation.ToolResult;
import org.remus.giteabot.ai.ToolDescriptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The control channel: the protocol, the budget, and the fact that it holds no policy of its own —
 * every call reaches the surface's executor, refusals included, and comes back as the tool result the
 * model would have seen.
 */
class PythonToolBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static CodeExecutionLimits limits(int maxToolCalls, int maxNestedChars) {
        return new CodeExecutionLimits(Duration.ofSeconds(60), maxToolCalls, maxNestedChars,
                100_000, 256L * 1024 * 1024, 120, 10L * 1024 * 1024, 64, "python3", "", "");
    }

    private static CodeExecutionLimits limits() {
        return limits(50, 50_000);
    }

    private static List<ToolDescriptor> advertised(String... names) {
        return Arrays.stream(names)
                .map(name -> new ToolDescriptor(name, "Description of " + name + ". More sentences.",
                        JSON.createObjectNode().put("type", "object")))
                .toList();
    }

    private static ToolResult ok(String output) {
        return new ToolResult(true, 0, output, "");
    }

    private static PythonToolExecutor returns(ToolResult result) {
        return (tool, arguments) -> result;
    }

    private static PythonToolBridge bridge(List<ToolDescriptor> available, PythonToolExecutor executor,
                                           CodeExecutionLimits limits) {
        return new PythonToolBridge(available, executor, limits, JSON);
    }

    private static String call(String name) {
        return "{\"type\":\"tool_call\",\"id\":\"1\",\"name\":\"" + name + "\",\"arguments\":{}}";
    }

    @Test
    void toolCall_returnsTheEnvelopePythonExpects() {
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("file content")), limits());

        JsonNode response = bridge.handle(
                "{\"type\":\"tool_call\",\"id\":\"7\",\"name\":\"cat\",\"arguments\":{\"path\":\"a\"}}");

        assertThat(response.path("type").asString()).isEqualTo("tool_result");
        assertThat(response.path("id").asString()).isEqualTo("7");
        JsonNode result = response.path("result");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("exitCode").asInt()).isZero();
        assertThat(result.path("output").asString()).isEqualTo("file content");
        assertThat(result.has("error")).isFalse();
        assertThat(bridge.toolCalls()).isEqualTo(1);
    }

    @Test
    void aRefusedToolIsAnUnsuccessfulResultRatherThanAProtocolError() {
        // The uniform path: the surface's refusal is a tool result, exactly as it is for the model.
        PythonToolBridge bridge = bridge(advertised("cat"),
                returns(new ToolResult(false, -1, "", "Tool 'write-file' is not enabled for this bot.")),
                limits());

        JsonNode response = bridge.handle(call("write-file"));

        assertThat(response.path("type").asString()).isEqualTo("tool_result");
        assertThat(response.path("result").path("success").asBoolean()).isFalse();
        assertThat(response.path("result").path("error").asString())
                .isEqualTo("Tool 'write-file' is not enabled for this bot.");
    }

    @Test
    void argumentsReachTheSurfaceExecutorVerbatim() {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        PythonToolBridge bridge = bridge(advertised("cat"), (tool, arguments) -> {
            received.set(arguments);
            return ok("");
        }, limits());

        bridge.handle("{\"type\":\"tool_call\",\"id\":\"1\",\"name\":\"cat\","
                + "\"arguments\":{\"path\":\"a\",\"depth\":3}}");

        assertThat(received.get().path("path").asString()).isEqualTo("a");
        assertThat(received.get().path("depth").asInt()).isEqualTo(3);
    }

    @Test
    void everyCallIsRelayedWithoutFilteringOnTheToolName() {
        // No allow list lives here: a name the surface does not know is the surface's business.
        AtomicInteger relayed = new AtomicInteger();
        PythonToolBridge bridge = bridge(advertised("cat"), (tool, arguments) -> {
            relayed.incrementAndGet();
            return ok("");
        }, limits());

        bridge.handle(call("cat"));
        bridge.handle(call("write-file"));

        assertThat(relayed).hasValue(2);
    }

    @Test
    void theBudgetStopsTheCallAfterTheConfiguredNumberOfCalls() {
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("ok")), limits(2, 50_000));

        assertThat(bridge.handle(call("cat")).path("type").asString()).isEqualTo("tool_result");
        assertThat(bridge.handle(call("cat")).path("type").asString()).isEqualTo("tool_result");

        JsonNode refused = bridge.handle(call("cat"));
        assertThat(refused.path("type").asString()).isEqualTo("tool_error");
        assertThat(refused.path("error").path("code").asString())
                .isEqualTo(PythonToolBridge.TOOL_CALL_LIMIT);
        assertThat(refused.path("error").path("message").asString()).contains("limit of 2");
        assertThat(bridge.toolCalls()).isEqualTo(2);
        assertThat(bridge.budgetExhausted()).isTrue();
    }

    @Test
    void aLargeNestedResultIsCappedBeforeItCrossesTheBridge() {
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("x".repeat(100))),
                limits(50, 10));

        JsonNode result = bridge.handle(call("cat")).path("result");
        String output = result.path("output").asString();

        assertThat(output).startsWith("x".repeat(10)).contains("truncated at 10 chars");
        assertThat(result.path("outputTruncated").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedResultsPreserveUpstreamTruncationWithoutWarningForCompleteOutput(boolean truncated) {
        PythonToolBridge bridge = bridge(advertised("cat"),
                returns(new ToolResult(true, 0, "captured prefix", "", truncated)), limits());

        JsonNode result = bridge.handle(call("cat")).path("result");

        assertThat(result.has("outputTruncated")).isTrue();
        assertThat(result.path("outputTruncated").asBoolean()).isEqualTo(truncated);
        if (truncated) {
            assertThat(result.path("output").asString()).startsWith("captured prefix")
                    .contains("not complete evidence");
        } else {
            assertThat(result.path("output").asString()).isEqualTo("captured prefix");
        }
    }

    @Test
    void toolsList_returnsNamesAndOneLineDescriptions() {
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("")), limits());

        JsonNode tools = bridge.handle("{\"type\":\"tools_list\",\"id\":\"1\"}")
                .path("result").path("tools");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("name").asString()).isEqualTo("cat");
        assertThat(tools.get(0).path("description").asString()).isEqualTo("Description of cat.");
    }

    @Test
    void describe_returnsTheFullInputSchema() {
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("")), limits());

        JsonNode result = bridge.handle("{\"type\":\"tools_describe\",\"id\":\"1\",\"name\":\"cat\"}")
                .path("result");

        assertThat(result.path("name").asString()).isEqualTo("cat");
        assertThat(result.path("inputSchema").path("type").asString()).isEqualTo("object");
    }

    @Test
    void describeOfAnUnadvertisedNameIsRefused() {
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("")), limits());

        JsonNode response = bridge.handle("{\"type\":\"tools_describe\",\"id\":\"1\",\"name\":\"nope\"}");

        assertThat(response.path("type").asString()).isEqualTo("tool_error");
        assertThat(response.path("error").path("code").asString())
                .isEqualTo(PythonToolBridge.TOOL_NOT_ALLOWED);
    }

    @Test
    void aMalformedFrameIsAnsweredRatherThanThrown() {
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("")), limits());

        for (String frame : List.of("this is not json", "[1,2,3]", "{\"type\":\"nonsense\"}",
                "{\"type\":\"tool_call\"}")) {
            JsonNode response = bridge.handle(frame);
            assertThat(response.path("type").asString())
                    .as("frame %s", frame)
                    .isEqualTo("tool_error");
            assertThat(response.path("error").path("code").asString())
                    .as("frame %s", frame)
                    .isEqualTo(PythonToolBridge.BAD_REQUEST);
        }
    }

    @Test
    void unknownRequestFieldsAreTolerated() {
        // A program that hand-rolls a frame may send more than this version knows about.
        PythonToolBridge bridge = bridge(advertised("cat"), returns(ok("ok")), limits());

        JsonNode response = bridge.handle("{\"type\":\"tool_call\",\"id\":\"1\",\"name\":\"cat\","
                + "\"arguments\":{},\"future\":\"ignored\"}");

        assertThat(response.path("type").asString()).isEqualTo("tool_result");
    }
}
