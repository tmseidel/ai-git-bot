package org.remus.giteabot.agent.issueimpl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.agent.model.ImplementationPlan;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AI responses into structured {@link ImplementationPlan} objects.
 * Handles JSON extraction, truncated-response repair, and invalid-escape sanitization.
 */
@Slf4j
public class AiResponseParser {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BLOCK_UNCLOSED_PATTERN = Pattern.compile("```json\\s*\\n(\\{.*)", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(\\{\\s*\"summary\"\\s*:.*)", Pattern.DOTALL);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parses a full AI response string into an {@link ImplementationPlan}.
     *
     * @param aiResponse The raw AI response (may contain markdown, JSON blocks, etc.)
     * @return The parsed plan, or {@code null} if parsing failed
     */
    public ImplementationPlan parseAiResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            log.warn("Empty AI response");
            return null;
        }

        String jsonStr = extractJsonFromResponse(aiResponse);
        if (jsonStr == null) {
            log.warn("Could not extract JSON from AI response");
            return null;
        }

        // Try to repair truncated JSON if necessary
        jsonStr = repairTruncatedJson(jsonStr);

        // Fix invalid JSON escape sequences (e.g. \<space> instead of \n)
        jsonStr = sanitizeInvalidJsonEscapes(jsonStr);

        try {
            AiImplementationResponse response = objectMapper.readValue(jsonStr, AiImplementationResponse.class);
            if (response == null) {
                log.warn("Parsed AI response is null");
                return null;
            }

            // Check if AI is requesting more files
            List<String> requestFiles = response.getRequestFiles();
            List<ImplementationPlan.ToolRequest> requestTools = response.getRequestTools() != null
                    ? response.getRequestTools().stream()
                    .filter(tool -> tool.getTool() != null && !tool.getTool().isBlank())
                    .map(tool -> ImplementationPlan.ToolRequest.builder()
                            .tool(tool.getTool())
                            .args(tool.getArgs())
                            .build())
                    .toList()
                    : List.of();

            // Parse file changes if present
            List<FileChange> fileChanges = new ArrayList<>();
            if (response.getFileChanges() != null) {
                fileChanges = response.getFileChanges().stream()
                        .map(fc -> FileChange.builder()
                                .path(fc.getPath())
                                .content(fc.getContent() != null ? fc.getContent() : "")
                                .diff(fc.getDiff())
                                .operation(parseOperation(fc.getOperation()))
                                .build())
                        .toList();
            }

            // Parse tool request if present
            ImplementationPlan.ToolRequest toolRequest = null;
            if (response.getRunTool() != null && response.getRunTool().getTool() != null) {
                toolRequest = ImplementationPlan.ToolRequest.builder()
                        .tool(response.getRunTool().getTool())
                        .args(response.getRunTool().getArgs())
                        .build();
            }

            return ImplementationPlan.builder()
                    .summary(response.getSummary())
                    .requestFiles(requestFiles)
                    .requestTools(requestTools)
                    .fileChanges(fileChanges)
                    .toolRequest(toolRequest)
                    .build();
        } catch (JacksonException e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            log.debug("JSON content that failed to parse: {}", jsonStr);
            return null;
        }
    }

    /**
     * Parses the AI's response for requested files, validating them against the repository tree.
     *
     * @param aiResponse The raw AI response
     * @param tree       The repository file tree
     * @return List of valid requested file paths
     */
    public List<String> parseRequestedFiles(String aiResponse, List<Map<String, Object>> tree) {
        List<String> requestedFiles = new ArrayList<>();

        // Build set of valid paths
        Set<String> validPaths = new HashSet<>();
        for (Map<String, Object> entry : tree) {
            String path = (String) entry.getOrDefault("path", "");
            String type = (String) entry.getOrDefault("type", "blob");
            if ("blob".equals(type)) {
                validPaths.add(path);
            }
        }

        // Try to extract JSON from response
        String jsonStr = extractJsonFromResponse(aiResponse);
        if (jsonStr != null) {
            try {
                FileRequestResponse response = objectMapper.readValue(jsonStr, FileRequestResponse.class);
                if (response != null && response.getRequestedFiles() != null) {
                    for (String file : response.getRequestedFiles()) {
                        if (validPaths.contains(file)) {
                            requestedFiles.add(file);
                        } else {
                            log.debug("Requested file not found in tree: {}", file);
                        }
                    }
                }
            } catch (JacksonException e) {
                log.warn("Failed to parse file request response: {}", e.getMessage());
            }
        }

        // If parsing failed, fall back to pattern matching
        if (requestedFiles.isEmpty()) {
            for (String path : validPaths) {
                if (aiResponse.contains(path)) {
                    requestedFiles.add(path);
                }
            }
        }

        // Limit to 30 files
        if (requestedFiles.size() > 30) {
            requestedFiles = requestedFiles.subList(0, 30);
        }

        return requestedFiles;
    }

    /**
     * Extracts the non-JSON (thinking/reasoning) text from an AI response.
     *
     * @param aiResponse The raw AI response
     * @return The thinking text, or {@code null} if the response is pure JSON
     */
    public String extractNonJsonResponse(String aiResponse) {
        // Try to extract the text before any JSON block
        int jsonStart = aiResponse.indexOf("```json");
        if (jsonStart >= 0) {
            if (jsonStart == 0) {
                return null; // Response starts with JSON block, no thinking text
            }
            String thinking = aiResponse.substring(0, jsonStart).strip();
            return thinking.isEmpty() ? null : thinking;
        }

        // Also check for ``` without language hint (some models do this)
        int codeBlockStart = aiResponse.indexOf("```\n{");
        if (codeBlockStart >= 0) {
            if (codeBlockStart == 0) {
                return null;
            }
            String thinking = aiResponse.substring(0, codeBlockStart).strip();
            return thinking.isEmpty() ? null : thinking;
        }

        // If no JSON block, check if it looks like JSON
        if (aiResponse.strip().startsWith("{")) {
            return null; // Pure JSON, no thinking text
        }

        return aiResponse;
    }

    /**
     * Extracts JSON from the AI response using multiple strategies.
     */
    String extractJsonFromResponse(String aiResponse) {
        // Strategy 1: Look for properly closed ```json ... ``` block
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(aiResponse);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }

        // Strategy 2: Look for unclosed ```json block (truncated response)
        matcher = JSON_BLOCK_UNCLOSED_PATTERN.matcher(aiResponse);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }

        // Strategy 3: Look for JSON object starting with {"summary":
        matcher = JSON_OBJECT_PATTERN.matcher(aiResponse);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }

        // Strategy 4: Try to find any JSON object in the response
        int jsonStart = aiResponse.indexOf('{');
        if (jsonStart >= 0) {
            return aiResponse.substring(jsonStart).strip();
        }

        return null;
    }

    /**
     * Attempts to repair truncated JSON by closing open structures.
     * This is a best-effort approach for handling incomplete AI responses.
     * IMPORTANT: Only truncates if the JSON is actually incomplete (unbalanced brackets).
     */
    String repairTruncatedJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        // First, check if the JSON is already complete (balanced brackets)
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        char prevChar = 0;

        for (char c : json.toCharArray()) {
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') braces++;
                else if (c == '}') braces--;
                else if (c == '[') brackets++;
                else if (c == ']') brackets--;
            }
            prevChar = c;
        }

        // If JSON is already balanced and complete, return as-is (do NOT truncate!)
        if (braces == 0 && brackets == 0 && !inString) {
            return json;
        }

        // JSON is truncated - try to repair it by finding last complete fileChange
        int lastCompleteObject = findLastCompleteFileChange(json);
        if (lastCompleteObject > 0 && lastCompleteObject < json.length() - 10) {
            json = json.substring(0, lastCompleteObject);

            // Recount brackets after truncation
            braces = 0;
            brackets = 0;
            inString = false;
            prevChar = 0;

            for (char c : json.toCharArray()) {
                if (c == '"' && prevChar != '\\') {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '{') braces++;
                    else if (c == '}') braces--;
                    else if (c == '[') brackets++;
                    else if (c == ']') brackets--;
                }
                prevChar = c;
            }
        }

        // If still unbalanced, try to close the structures
        if (braces > 0 || brackets > 0 || inString) {
            StringBuilder repaired = new StringBuilder(json);

            // Close unclosed string
            if (inString) {
                repaired.append("\"");
            }

            // Close brackets and braces
            while (brackets > 0) {
                repaired.append("]");
                brackets--;
            }
            while (braces > 0) {
                repaired.append("}");
                braces--;
            }

            return repaired.toString();
        }

        return json;
    }

    /**
     * Sanitizes invalid JSON escape sequences in the raw JSON string.
     * AI models sometimes produce invalid escapes like {@code \<space>} instead of {@code \n}.
     * This replaces any {@code \X} where X is not a valid JSON escape character
     * ({@code " \ / b f n r t u}) with {@code \\X} (escaped backslash + character).
     */
    String sanitizeInvalidJsonEscapes(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        return json.replaceAll("\\\\([^\"\\\\bfnrtu/])", "\\\\\\\\$1");
    }

    /**
     * Finds the position after the last complete fileChange object in the JSON.
     */
    private int findLastCompleteFileChange(String json) {
        int lastComplete = -1;
        int searchFrom = 0;

        while (true) {
            int closeBrace = json.indexOf('}', searchFrom);
            if (closeBrace < 0) break;

            int nextNonWhitespace = closeBrace + 1;
            while (nextNonWhitespace < json.length() &&
                   Character.isWhitespace(json.charAt(nextNonWhitespace))) {
                nextNonWhitespace++;
            }

            if (nextNonWhitespace < json.length()) {
                char nextChar = json.charAt(nextNonWhitespace);
                if (nextChar == ']' || nextChar == ',') {
                    lastComplete = nextNonWhitespace + 1;
                }
            }

            searchFrom = closeBrace + 1;
        }

        return lastComplete;
    }

    private FileChange.Operation parseOperation(String operation) {
        if (operation == null) return FileChange.Operation.CREATE;
        return switch (operation.toUpperCase()) {
            case "UPDATE" -> FileChange.Operation.UPDATE;
            case "DELETE" -> FileChange.Operation.DELETE;
            default -> FileChange.Operation.CREATE;
        };
    }

    // ---- Inner DTOs for AI response deserialization ----

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiImplementationResponse {
        private String summary;
        @JsonAlias("requestedFiles")
        private List<String> requestFiles;
        @JsonAlias("requestedTools")
        private List<AiToolRequest> requestTools;
        private List<AiFileChange> fileChanges;
        private AiToolRequest runTool;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiFileChange {
        private String path;
        private String operation;
        private String content;
        private String diff;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiToolRequest {
        private String tool;
        private List<String> args;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FileRequestResponse {
        private String reasoning;
        @JsonAlias("requestFiles")
        private List<String> requestedFiles;
    }
}
