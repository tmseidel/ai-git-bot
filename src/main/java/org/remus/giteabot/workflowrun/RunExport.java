package org.remus.giteabot.workflowrun;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.remus.giteabot.aiusage.AiToolCall;
import org.remus.giteabot.aiusage.InteractionRow;

import java.util.List;

/**
 * One run in the JSON export: the run's columns, then its interactions (without raw payloads)
 * with their tool calls. Serialized as is, so a new column appears in the export by itself.
 */
public record RunExport(@JsonUnwrapped WorkflowRun run, List<Interaction> interactions) {

    public record Interaction(@JsonUnwrapped InteractionRow row, List<AiToolCall> toolCalls) {
    }
}
