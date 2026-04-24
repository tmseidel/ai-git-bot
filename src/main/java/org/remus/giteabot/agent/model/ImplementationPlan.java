package org.remus.giteabot.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImplementationPlan {

    /**
     * Short summary of the planned implementation.
     */
    private String summary;

    /**
     * Files the AI requests to see before proceeding.
     * If non-empty, fetch these files and continue the conversation.
     */
    private List<String> requestFiles;

    /**
     * Repository exploration tools the AI wants to run before coding.
     */
    private List<ToolRequest> requestTools;

    /**
     * List of file changes to implement.
     */
    private List<FileChange> fileChanges;

    /**
     * Branch name to be created for the implementation.
     */
    private String branchName;

    /**
     * Tool the AI wants to run for validation.
     */
    private ToolRequest toolRequest;

    /**
     * Returns true if the AI is requesting additional files.
     */
    public boolean hasFileRequests() {
        return requestFiles != null && !requestFiles.isEmpty();
    }

    /**
     * Returns true if the AI is requesting repository exploration tools.
     */
    public boolean hasContextToolRequests() {
        return requestTools != null && !requestTools.isEmpty();
    }

    /**
     * Returns true if the AI is requesting any additional repository context.
     */
    public boolean hasContextRequests() {
        return hasFileRequests() || hasContextToolRequests();
    }

    /**
     * Returns true if there are actual file changes to apply.
     */
    public boolean hasFileChanges() {
        return fileChanges != null && !fileChanges.isEmpty();
    }

    /**
     * Returns true if the AI wants to run a validation tool.
     */
    public boolean hasToolRequest() {
        return toolRequest != null && toolRequest.getTool() != null && !toolRequest.getTool().isBlank();
    }

    @Data
    @Builder
    public static class ToolRequest {
        private String tool;
        private List<String> args;
    }
}
