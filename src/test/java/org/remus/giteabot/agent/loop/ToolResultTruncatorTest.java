package org.remus.giteabot.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolResultTruncator}, covering the head+tail truncation
 * arithmetic and edge cases.
 */
class ToolResultTruncatorTest {

    @Test
    void truncate_nullInput_returnsNull() {
        ToolResultTruncator truncator = new ToolResultTruncator(100);

        assertNull(truncator.truncate(null));
    }

    @Test
    void truncate_belowLimit_returnsUnchanged() {
        ToolResultTruncator truncator = new ToolResultTruncator(100);

        String result = truncator.truncate("short text");

        assertEquals("short text", result);
    }

    @Test
    void truncate_exactlyAtLimit_returnsUnchanged() {
        ToolResultTruncator truncator = new ToolResultTruncator(10);

        String result = truncator.truncate("1234567890");

        assertEquals("1234567890", result);
    }

    @Test
    void truncate_exceedsLimit_appliesHeadTailStrategy() {
        ToolResultTruncator truncator = new ToolResultTruncator(1000);

        // Create text with identifiable head and tail
        String head = "A".repeat(100);
        String middle = "B".repeat(2000);
        String tail = "C".repeat(100);
        String text = head + middle + tail;

        String result = truncator.truncate(text);

        // Result should be within budget (head=500, tail=436, marker~64 = ~1000)
        assertTrue(result.length() <= 1000, "Result should be within maxChars");
        // Head should be preserved (first 500 chars = maxChars/2)
        assertTrue(result.startsWith("A".repeat(100)));
        // Tail should be preserved (last 436 chars = maxChars - 500 - 64)
        assertTrue(result.endsWith("C".repeat(100)));
        // Marker should be present
        assertTrue(result.contains("characters truncated"));
    }

    @Test
    void truncate_verySmallBudget_headOnly() {
        // When maxChars is so small that tail would be negative, only head is kept
        ToolResultTruncator truncator = new ToolResultTruncator(100);

        String text = "A".repeat(100) + "B".repeat(200);

        String result = truncator.truncate(text);

        // Should not throw, and should contain the marker
        assertTrue(result.contains("characters truncated"));
        assertTrue(result.startsWith("A".repeat(36))); // headSize = maxChars - markerOverhead = 36
    }

    @Test
    void truncate_preservesHeadStructure() {
        ToolResultTruncator truncator = new ToolResultTruncator(200);

        String text = "=== HEADER ===\n" + "x".repeat(500) + "\n=== FOOTER ===";

        String result = truncator.truncate(text);

        assertTrue(result.startsWith("=== HEADER ==="));
        assertTrue(result.contains("characters truncated"));
    }

    @Test
    void truncate_preservesTailErrors() {
        ToolResultTruncator truncator = new ToolResultTruncator(200);

        String text = "x".repeat(500) + "\nERROR: build failed\nexit code 1";

        String result = truncator.truncate(text);

        assertTrue(result.endsWith("exit code 1"));
        assertTrue(result.contains("ERROR: build failed"));
    }

    @Test
    void truncate_markerShowsCorrectRemovedCount() {
        ToolResultTruncator truncator = new ToolResultTruncator(1000);
        String text = "x".repeat(5000);

        String result = truncator.truncate(text);

        // head = 500, tail = 1000 - 500 - 64 = 436, removed = 5000 - 500 - 436 = 4064
        assertTrue(result.contains("4064 characters truncated"));
    }

    @Test
    void truncate_veryLargeInput_staysWithinBudget() {
        ToolResultTruncator truncator = new ToolResultTruncator(1000);

        String text = "x".repeat(100_000);

        String result = truncator.truncate(text);

        assertTrue(result.length() <= 1000);
    }

    @Test
    void truncate_emptyString_returnsEmpty() {
        ToolResultTruncator truncator = new ToolResultTruncator(100);

        String result = truncator.truncate("");

        assertEquals("", result);
    }

    @Test
    void getMaxChars_returnsConfiguredValue() {
        ToolResultTruncator truncator = new ToolResultTruncator(5000);

        assertEquals(5000, truncator.getMaxChars());
    }

    @Test
    void truncate_headAndTailDoNotOverlap() {
        ToolResultTruncator truncator = new ToolResultTruncator(1000);

        // Create text where head and tail have distinct markers
        String text = "HEAD_START" + "x".repeat(2000) + "TAIL_END";

        String result = truncator.truncate(text);

        assertTrue(result.startsWith("HEAD_START"));
        assertTrue(result.endsWith("TAIL_END"));
        // Head and tail should not overlap
        int headEnd = result.indexOf("characters truncated");
        int tailStart = result.lastIndexOf("]");
        assertTrue(headEnd > 0 && tailStart > headEnd);
    }
}
