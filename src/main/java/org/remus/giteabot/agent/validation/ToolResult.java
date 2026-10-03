package org.remus.giteabot.agent.validation;

/**
 * Result of a tool execution containing exit code, stdout, and any error message.
 */
public record ToolResult(
        boolean success,
        int exitCode,
        String output,
        String error,
        boolean outputTruncated
) {
    /** Compatibility constructor for results without known output truncation. */
    public ToolResult(boolean success, int exitCode, String output, String error) {
        this(success, exitCode, output, error, false);
    }
    /**
     * Formats the result for sending to the AI.
     */
    public String formatForAi() {
        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(exitCode).append("\n");
        if (outputTruncated) sb.append("Output is truncated; this is not complete evidence.\n");
        if (!error.isEmpty()) {
            sb.append("Error: ").append(error).append("\n");
        }
        if (!output.isEmpty()) {
            sb.append("Output:\n```\n").append(output).append("```\n");
        }
        return sb.toString();
    }
}
