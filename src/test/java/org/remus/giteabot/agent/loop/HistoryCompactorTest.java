package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HistoryCompactor}, focusing on the ADR-1 tool-pair grouping
 * invariant: assistant messages with tool_calls and their corresponding tool
 * responses must be kept or dropped atomically.
 */
class HistoryCompactorTest {

    @Test
    void groupIntoUnits_singleMessages_noToolCalls() {
        List<AiMessage> messages = List.of(
                AiMessage.builder().role("user").content("hello").build(),
                AiMessage.builder().role("assistant").content("hi there").build(),
                AiMessage.builder().role("user").content("thanks").build()
        );

        List<HistoryCompactor.CompactionUnit> units = HistoryCompactor.groupIntoUnits(messages);

        assertEquals(3, units.size());
        assertEquals(1, units.get(0).messages().size());
        assertEquals("hello", units.get(0).messages().get(0).getContent());
        assertEquals(1, units.get(1).messages().size());
        assertEquals("hi there", units.get(1).messages().get(0).getContent());
        assertEquals(1, units.get(2).messages().size());
        assertEquals("thanks", units.get(2).messages().get(0).getContent());
    }

    @Test
    void groupIntoUnits_assistantWithSingleToolCall_groupsAsOneUnit() {
        List<AiMessage> messages = List.of(
                AiMessage.builder().role("user").content("read file").build(),
                AiMessage.builder()
                        .role("assistant")
                        .content("")
                        .toolCalls(List.of(new ToolCall("call_1", "read_file", null)))
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call_1")
                        .toolResult("file contents")
                        .build(),
                AiMessage.builder().role("user").content("done").build()
        );

        List<HistoryCompactor.CompactionUnit> units = HistoryCompactor.groupIntoUnits(messages);

        assertEquals(3, units.size());
        // First: user message
        assertEquals(1, units.get(0).messages().size());
        assertEquals("user", units.get(0).messages().get(0).getRole());
        // Second: assistant+tool group
        assertEquals(2, units.get(1).messages().size());
        assertEquals("assistant", units.get(1).messages().get(0).getRole());
        assertEquals("tool", units.get(1).messages().get(1).getRole());
        assertEquals("call_1", units.get(1).messages().get(1).getToolCallId());
        // Third: user message
        assertEquals(1, units.get(2).messages().size());
    }

    @Test
    void groupIntoUnits_assistantWithMultipleToolCalls_groupsAllResponses() {
        List<AiMessage> messages = List.of(
                AiMessage.builder()
                        .role("assistant")
                        .content("")
                        .toolCalls(List.of(
                                new ToolCall("call_1", "read_file", null),
                                new ToolCall("call_2", "read_file", null)
                        ))
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call_1")
                        .toolResult("file 1")
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call_2")
                        .toolResult("file 2")
                        .build()
        );

        List<HistoryCompactor.CompactionUnit> units = HistoryCompactor.groupIntoUnits(messages);

        assertEquals(1, units.size());
        assertEquals(3, units.get(0).messages().size());
        assertEquals("assistant", units.get(0).messages().get(0).getRole());
        assertEquals("tool", units.get(0).messages().get(1).getRole());
        assertEquals("call_1", units.get(0).messages().get(1).getToolCallId());
        assertEquals("tool", units.get(0).messages().get(2).getRole());
        assertEquals("call_2", units.get(0).messages().get(2).getToolCallId());
    }

    @Test
    void groupIntoUnits_multipleSequentialToolPairs_groupsSeparately() {
        List<AiMessage> messages = List.of(
                // First tool pair
                AiMessage.builder()
                        .role("assistant")
                        .toolCalls(List.of(new ToolCall("call_1", "read_file", null)))
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call_1")
                        .toolResult("result 1")
                        .build(),
                // Second tool pair
                AiMessage.builder()
                        .role("assistant")
                        .toolCalls(List.of(new ToolCall("call_2", "run_command", null)))
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call_2")
                        .toolResult("result 2")
                        .build()
        );

        List<HistoryCompactor.CompactionUnit> units = HistoryCompactor.groupIntoUnits(messages);

        assertEquals(2, units.size());
        assertEquals(2, units.get(0).messages().size());
        assertEquals("call_1", units.get(0).messages().get(1).getToolCallId());
        assertEquals(2, units.get(1).messages().size());
        assertEquals("call_2", units.get(1).messages().get(1).getToolCallId());
    }

    @Test
    void groupIntoUnits_orphanToolMessage_treatedAsSingleUnit() {
        List<AiMessage> messages = List.of(
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("orphan_call")
                        .toolResult("orphan result")
                        .build(),
                AiMessage.builder().role("user").content("next").build()
        );

        List<HistoryCompactor.CompactionUnit> units = HistoryCompactor.groupIntoUnits(messages);

        assertEquals(2, units.size());
        assertEquals(1, units.get(0).messages().size());
        assertEquals("tool", units.get(0).messages().get(0).getRole());
    }

    @Test
    void groupIntoUnits_assistantWithoutToolCalls_treatedAsSingleUnit() {
        List<AiMessage> messages = List.of(
                AiMessage.builder()
                        .role("assistant")
                        .content("just text")
                        .toolCalls(List.of())
                        .build()
        );

        List<HistoryCompactor.CompactionUnit> units = HistoryCompactor.groupIntoUnits(messages);

        assertEquals(1, units.size());
        assertEquals(1, units.get(0).messages().size());
    }

    @Test
    void compact_belowBudget_noCompaction() {
        HistoryCompactor compactor = new HistoryCompactor(10_000, 3);

        List<AiMessage> history = new ArrayList<>(List.of(
                AiMessage.builder().role("user").content("small").build(),
                AiMessage.builder().role("assistant").content("reply").build()
        ));

        int freed = compactor.compact(history);

        assertEquals(0, freed);
        assertEquals(2, history.size());
    }

    @Test
    void compact_exceedsBudget_dropsOlderUnits() {
        HistoryCompactor compactor = new HistoryCompactor(50, 2);

        String longContent = "x".repeat(30);
        List<AiMessage> history = new ArrayList<>(List.of(
                AiMessage.builder().role("user").content(longContent).build(),
                AiMessage.builder().role("assistant").content(longContent).build(),
                AiMessage.builder().role("user").content(longContent).build(),
                AiMessage.builder().role("assistant").content("recent").build()
        ));

        int freed = compactor.compact(history);

        assertTrue(freed > 0);
        // Should have summary + kept units
        assertTrue(history.size() <= 4);
        assertEquals("user", history.get(0).getRole());
        assertTrue(history.get(0).getContent().contains("compacted"));
    }

    @Test
    void compact_preservesToolPairAtomically() {
        HistoryCompactor compactor = new HistoryCompactor(100, 2);

        String longResult = "x".repeat(80);
        List<AiMessage> history = new ArrayList<>(List.of(
                // Old tool pair (should be dropped atomically)
                AiMessage.builder()
                        .role("assistant")
                        .content("I will read the file")
                        .toolCalls(List.of(new ToolCall("call_1", "read_file", null)))
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call_1")
                        .toolResult(longResult)
                        .build(),
                // Recent messages
                AiMessage.builder().role("user").content("recent question").build(),
                AiMessage.builder().role("assistant").content("recent answer").build()
        ));

        int freed = compactor.compact(history);

        assertTrue(freed > 0);
        // Verify the old tool pair is gone (both messages dropped together)
        boolean hasOldToolCall = history.stream()
                .anyMatch(m -> m.getToolCalls() != null
                        && m.getToolCalls().stream().anyMatch(tc -> "call_1".equals(tc.id())));
        boolean hasOldToolResponse = history.stream()
                .anyMatch(m -> "tool".equals(m.getRole()) && "call_1".equals(m.getToolCallId()));
        assertFalse(hasOldToolCall, "Old tool call should be dropped");
        assertFalse(hasOldToolResponse, "Old tool response should be dropped");
    }

    @Test
    void compact_keepsRecentToolPairIntact() {
        HistoryCompactor compactor = new HistoryCompactor(100, 2);

        List<AiMessage> history = new ArrayList<>(List.of(
                AiMessage.builder().role("user").content("old question").build(),
                AiMessage.builder().role("assistant").content("old answer").build(),
                // Recent tool pair (should be kept)
                AiMessage.builder()
                        .role("assistant")
                        .toolCalls(List.of(new ToolCall("call_2", "run_command", null)))
                        .build(),
                AiMessage.builder()
                        .role("tool")
                        .toolCallId("call_2")
                        .toolResult("recent result")
                        .build()
        ));

        compactor.compact(history);

        // Verify the recent tool pair is intact
        boolean hasRecentToolCall = history.stream()
                .anyMatch(m -> m.getToolCalls() != null
                        && m.getToolCalls().stream().anyMatch(tc -> "call_2".equals(tc.id())));
        boolean hasRecentToolResponse = history.stream()
                .anyMatch(m -> "tool".equals(m.getRole()) && "call_2".equals(m.getToolCallId()));
        assertTrue(hasRecentToolCall, "Recent tool call should be kept");
        assertTrue(hasRecentToolResponse, "Recent tool response should be kept");
    }

    @Test
    void compact_neverDropsBelowKeepLastN() {
        HistoryCompactor compactor = new HistoryCompactor(10, 3); // Very tight budget

        String longContent = "x".repeat(20);
        List<AiMessage> history = new ArrayList<>(List.of(
                AiMessage.builder().role("user").content(longContent).build(),
                AiMessage.builder().role("assistant").content(longContent).build(),
                AiMessage.builder().role("user").content(longContent).build(),
                AiMessage.builder().role("assistant").content(longContent).build()
        ));

        compactor.compact(history);

        // Should keep at least 3 units (plus summary)
        assertTrue(history.size() >= 3);
    }

    @Test
    void compactAggressively_keepsOnlyLastTwoUnits() {
        HistoryCompactor compactor = new HistoryCompactor(20, 5);

        List<AiMessage> history = new ArrayList<>(List.of(
                AiMessage.builder().role("user").content("message one").build(),
                AiMessage.builder().role("assistant").content("message two").build(),
                AiMessage.builder().role("user").content("message three").build(),
                AiMessage.builder().role("assistant").content("message four").build(),
                AiMessage.builder().role("user").content("message five").build()
        ));

        compactor.compactAggressively(history);

        // Should have summary + 2 units (each is a single message here)
        assertEquals(3, history.size());
        assertTrue(history.get(0).getContent().contains("aggressively compacted"));
    }

    @Test
    void compact_emptyHistory_noOp() {
        HistoryCompactor compactor = new HistoryCompactor(100, 2);
        List<AiMessage> history = new ArrayList<>();

        int freed = compactor.compact(history);

        assertEquals(0, freed);
        assertTrue(history.isEmpty());
    }

    @Test
    void compact_nullHistory_noOp() {
        HistoryCompactor compactor = new HistoryCompactor(100, 2);

        int freed = compactor.compact(null);

        assertEquals(0, freed);
    }

    @Test
    void messageChars_countsContentAndToolResult() {
        AiMessage msg = AiMessage.builder()
                .role("tool")
                .content("content")
                .toolResult("result")
                .build();

        int chars = HistoryCompactor.messageChars(msg);

        assertEquals(13, chars); // "content" (7) + "result" (6)
    }

    @Test
    void messageChars_handlesNullFields() {
        AiMessage msg = AiMessage.builder()
                .role("assistant")
                .content(null)
                .build();

        int chars = HistoryCompactor.messageChars(msg);

        assertEquals(0, chars);
    }
}
