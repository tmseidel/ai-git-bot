package org.remus.giteabot.agent.loop;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tool-pair-aware sliding window for in-memory agent history.
 *
 * <p>A "compaction unit" is either:</p>
 * <ul>
 *     <li>A single user or assistant message (no tool calls), or</li>
 *     <li>An assistant message with {@code tool_calls} + all subsequent tool-role
 *     messages that reference those calls.</li>
 * </ul>
 *
 * <p>Units are kept or dropped atomically. When the total character count exceeds
 * the budget, older units are replaced with a single summary message.</p>
 *
 * <p>This prevents the in-memory history from growing unbounded during a single
 * {@link AgentLoop#run AgentLoop.run()} execution — the most impactful defense
 * against "prompt too long" crashes mid-run.</p>
 */
@Slf4j
public final class HistoryCompactor {

    private static final String COMPACTED_SUMMARY =
            "[Earlier conversation compacted to fit context window. "
            + "Previous tool results are no longer available in history but their effects persist. "
            + "You can re-read files or re-run tools if needed.]";

    private static final String AGGRESSIVE_SUMMARY =
            "[Context was aggressively compacted due to provider prompt-length limits. "
            + "Only the most recent exchanges remain.]";

    private final int maxChars;
    private final int keepLastN;

    /**
     * @param maxChars  character budget for the entire history list
     * @param keepLastN minimum number of compaction units to always keep,
     *                  even if they exceed the character budget
     */
    public HistoryCompactor(int maxChars, int keepLastN) {
        this.maxChars = maxChars;
        this.keepLastN = Math.max(2, keepLastN);
    }

    /**
     * Compacts history in-place using the standard budget.
     *
     * @param history the mutable history list (modified in-place)
     * @return the number of characters freed, or 0 if no compaction occurred
     */
    public int compact(List<AiMessage> history) {
        return doCompact(history, COMPACTED_SUMMARY, keepLastN);
    }

    /**
     * Aggressively compacts history, keeping only the last 2 units.
     * Used as a last resort when the provider rejects a request due to
     * prompt length.
     *
     * @param history the mutable history list (modified in-place)
     * @return the number of characters freed, or 0 if no compaction occurred
     */
    public int compactAggressively(List<AiMessage> history) {
        return doCompact(history, AGGRESSIVE_SUMMARY, 2);
    }

    private int doCompact(List<AiMessage> history, String summaryText, int minKeepUnits) {
        if (history == null || history.isEmpty()) {
            return 0;
        }

        int totalChars = history.stream()
                .mapToInt(HistoryCompactor::messageChars)
                .sum();

        if (totalChars <= maxChars && history.size() <= keepLastN * 3) {
            return 0;
        }

        // Group messages into compaction units
        List<CompactionUnit> units = groupIntoUnits(history);

        if (units.size() <= minKeepUnits) {
            return 0;
        }

        // Walk from the end, keeping units until we hit the budget or minKeepUnits
        int keptChars = 0;
        int keptUnits = 0;
        int cutIndex = units.size(); // index of first unit to drop

        for (int i = units.size() - 1; i >= 0; i--) {
            CompactionUnit unit = units.get(i);
            int unitChars = unit.totalChars();

            if (keptUnits >= minKeepUnits && keptChars + unitChars > maxChars) {
                break;
            }
            keptChars += unitChars;
            keptUnits++;
            cutIndex = i;
        }

        if (cutIndex == 0) {
            // Nothing to drop
            return 0;
        }

        // Calculate how many chars we're freeing
        int droppedChars = 0;
        for (int i = 0; i < cutIndex; i++) {
            droppedChars += units.get(i).totalChars();
        }

        // Build the new history: summary + kept units
        List<AiMessage> newHistory = new ArrayList<>();
        newHistory.add(AiMessage.builder()
                .role("user")
                .content(summaryText)
                .build());

        for (int i = cutIndex; i < units.size(); i++) {
            newHistory.addAll(units.get(i).messages());
        }

        log.debug("HistoryCompactor: dropped {} units ({} chars), kept {} units ({} chars)",
                cutIndex, droppedChars, units.size() - cutIndex, keptChars);

        // Replace history contents in-place
        history.clear();
        history.addAll(newHistory);

        return droppedChars;
    }

    /**
     * Groups messages into compaction units. An assistant message with tool_calls
     * and all subsequent tool messages that reference those calls form one unit.
     */
    static List<CompactionUnit> groupIntoUnits(List<AiMessage> messages) {
        List<CompactionUnit> units = new ArrayList<>();
        int i = 0;

        while (i < messages.size()) {
            AiMessage msg = messages.get(i);

            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null
                    && !msg.getToolCalls().isEmpty()) {
                // Start of an assistant+tool group
                List<AiMessage> group = new ArrayList<>();
                group.add(msg);

                // Collect the tool call IDs this assistant message emitted
                Set<String> expectedIds = msg.getToolCalls().stream()
                        .map(ToolCall::id)
                        .collect(Collectors.toSet());

                // Consume subsequent tool messages that match
                int j = i + 1;
                while (j < messages.size()) {
                    AiMessage next = messages.get(j);
                    if ("tool".equals(next.getRole()) && next.getToolCallId() != null
                            && expectedIds.contains(next.getToolCallId())) {
                        group.add(next);
                        j++;
                    } else {
                        break;
                    }
                }

                units.add(new CompactionUnit(group));
                i = j;
            } else {
                // Single message unit (user, assistant without tools, or orphan tool)
                units.add(new CompactionUnit(List.of(msg)));
                i++;
            }
        }

        return units;
    }

    static int messageChars(AiMessage msg) {
        int total = 0;
        if (msg.getContent() != null) total += msg.getContent().length();
        if (msg.getToolResult() != null) total += msg.getToolResult().length();
        return total;
    }

    /**
     * A compaction unit: either a single message or an assistant+tool group
     * that must be kept or dropped atomically.
     */
    record CompactionUnit(List<AiMessage> messages) {
        CompactionUnit {
            messages = List.copyOf(messages);
        }

        int totalChars() {
            return messages.stream().mapToInt(HistoryCompactor::messageChars).sum();
        }
    }
}
