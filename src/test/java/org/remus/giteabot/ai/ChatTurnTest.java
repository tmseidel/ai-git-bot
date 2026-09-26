package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.shared.AgentJackson;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTurnTest {

    @Test
    void opaqueReasoningIsExcludedFromDiagnosticsAndGenericSerialization() {
        var details = List.of(AgentJackson.mapper().readTree("{\"data\":\"private-reasoning\"}"));
        var turn = new ChatTurn("Answer", List.of(), StopReason.END_TURN, 100, 32, details);
        var message = turn.toAssistantMessage();

        assertEquals(details, turn.reasoningDetails());
        for (Object value : List.of(turn, message)) {
            assertFalse(value.toString().contains("private-reasoning"));
            assertFalse(AgentJackson.mapper().writeValueAsString(value).contains("private-reasoning"));
        }
    }

    @Test
    void textFactory_producesEndTurnAndNoToolCalls() {
        ChatTurn turn = ChatTurn.text("hello");
        assertEquals("hello", turn.assistantText());
        assertEquals(StopReason.END_TURN, turn.stopReason());
        assertFalse(turn.hasToolCalls());
        assertNotNull(turn.toolCalls());
        assertTrue(turn.toolCalls().isEmpty());
    }

    @Test
    void canonicalConstructor_normalisesNullFields() {
        ChatTurn turn = new ChatTurn(null, null, null, 0L, 0L);
        assertEquals("", turn.assistantText());
        assertNotNull(turn.toolCalls());
        assertTrue(turn.toolCalls().isEmpty());
        assertEquals(StopReason.OTHER, turn.stopReason());
        assertEquals(0L, turn.inputTokens());
        assertEquals(0L, turn.outputTokens());
    }

    @Test
    void hasToolCalls_trueWhenToolCallPresent() {
        ChatTurn turn = new ChatTurn("",
                List.of(new ToolCall("call_1", "do_thing", null)),
                StopReason.TOOL_USE, 100L, 50L);
        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("do_thing", turn.toolCalls().getFirst().name());
        assertEquals(turn.toolCalls(), turn.toAssistantMessage().getToolCalls());
        assertEquals(100L, turn.inputTokens());
        assertEquals(50L, turn.outputTokens());
        assertEquals(150L, turn.totalTokens());
    }
}
