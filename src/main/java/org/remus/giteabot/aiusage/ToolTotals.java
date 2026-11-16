package org.remus.giteabot.aiusage;

/**
 * The tool calls of one interaction, summed up.
 *
 * @param interactionId the interaction
 * @param calls         number of tool calls
 * @param failed        number of failed tool calls
 * @param durationMs    sum of the tool call durations
 */
public record ToolTotals(Long interactionId, long calls, long failed, long durationMs) {

    /** The totals of an interaction without tool calls. */
    public static ToolTotals none(Long interactionId) {
        return new ToolTotals(interactionId, 0, 0, 0);
    }
}
