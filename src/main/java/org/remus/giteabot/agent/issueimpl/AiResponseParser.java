package org.remus.giteabot.agent.issueimpl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.agent.shared.AgentSchema;
import org.remus.giteabot.agent.shared.AgentSchemaValidator;
import org.remus.giteabot.agent.shared.AgentSchemaValidatorHolder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AI responses into structured {@link ImplementationPlan} objects.
 * Handles JSON extraction, truncated-response repair, and invalid-escape sanitization.
 */
@Slf4j
public class AiResponseParser {

    public static final Pattern PATTERN = Pattern.compile(
            "(\\\\\\\\)|\\\\([^\"\\\\bfnrtu/])");
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BLOCK_UNCLOSED_PATTERN = Pattern.compile("```json\\s*\\n(\\{.*)", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(\\{\\s*\"summary\"\\s*:.*)", Pattern.DOTALL);

    private final ObjectMapper objectMapper = AgentJackson.mapper();

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
            // Native tool-calling mode legitimately produces many assistant
            // responses without any embedded JSON plan (the structured intent
            // lives in tool_calls instead). Logging at WARN spammed the log;
            // keep it at DEBUG so legacy diagnostics are still available when
            // operators explicitly enable debug for this class.
            log.debug("Could not extract JSON from AI response");
            return null;
        }

        // Try to repair truncated JSON if necessary
        // Truncate to first complete JSON object (handles duplicated responses)
        jsonStr = truncateToFirstJsonObject(jsonStr);

        jsonStr = repairTruncatedJson(jsonStr);

        // Fix invalid JSON escape sequences (e.g. \<space> instead of \n)
        jsonStr = sanitizeInvalidJsonEscapes(jsonStr);

        // Step 5: Validate against JSON-Schema (observe-only by default).
        // In enforce mode the parser bails out so that the loop falls back to
        // the existing "no plan returned" handling.
        if (!validateAgainstSchema(jsonStr)) {
            return null;
        }

        try {
            AiImplementationResponse response = objectMapper.readValue(jsonStr, AiImplementationResponse.class);
            if (response == null) {
                log.warn("Parsed AI response is null");
                return null;
            }

            // Check if AI is requesting more files
            List<String> requestFiles = response.getRequestFiles();
            // Parse context tool requests with auto-generated IDs
            List<ImplementationPlan.ToolRequest> requestTools = List.of();
            if (response.getRequestTools() != null) {
                int idx = 1;
                List<ImplementationPlan.ToolRequest> built = new ArrayList<>();
                for (AiToolRequest tool : response.getRequestTools()) {
                    if (tool.getTool() == null || tool.getTool().isBlank()) continue;
                    String id = (tool.getId() != null && !tool.getId().isBlank())
                            ? tool.getId() : "ctx-" + idx;
                    built.add(ImplementationPlan.ToolRequest.builder()
                            .id(id).tool(tool.getTool()).args(normalizeArgs(tool.getArgs())).build());
                    idx++;
                }
                requestTools = built;
            }

            // Parse tool requests: prefer runTools array, fall back to single runTool
            List<ImplementationPlan.ToolRequest> toolRequests = new ArrayList<>();
            if (response.getRunTools() != null && !response.getRunTools().isEmpty()) {
                int idx = 1;
                for (AiToolRequest tr : response.getRunTools()) {
                    if (tr.getTool() == null || tr.getTool().isBlank()) continue;
                    String id = (tr.getId() != null && !tr.getId().isBlank())
                            ? tr.getId() : "tool-" + idx;
                    toolRequests.add(ImplementationPlan.ToolRequest.builder()
                            .id(id).tool(tr.getTool()).args(normalizeArgs(tr.getArgs())).build());
                    idx++;
                }
            } else if (response.getRunTool() != null && response.getRunTool().getTool() != null) {
                String id = (response.getRunTool().getId() != null && !response.getRunTool().getId().isBlank())
                        ? response.getRunTool().getId() : "tool-1";
                toolRequests.add(ImplementationPlan.ToolRequest.builder()
                        .id(id).tool(response.getRunTool().getTool()).args(normalizeArgs(response.getRunTool().getArgs())).build());
            }

            // Legacy single toolRequest for backward compatibility (first entry)
            ImplementationPlan.ToolRequest legacyToolRequest = toolRequests.isEmpty() ? null : toolRequests.getFirst();

            return ImplementationPlan.builder()
                    .summary(response.getSummary())
                    .requestFiles(requestFiles)
                    .requestTools(requestTools)
                    .toolRequest(legacyToolRequest)
                    .toolRequests(toolRequests.isEmpty() ? null : toolRequests)
                    .build();
        } catch (JacksonException e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            log.debug("JSON content that failed to parse: {}", jsonStr);
            return null;
        }
    }

    /**
     * Extracts the non-JSON (thinking/reasoning) text from an AI response.
     *
     * @param aiResponse The raw AI response
     * @return The thinking text, or {@code null} if the response is pure JSON
     */
    public String extractNonJsonResponse(String aiResponse) {
        // First: if the response is pure JSON, there is no thinking text.
        // This check must come BEFORE searching for ```json blocks, because tool
        // arguments inside the JSON (e.g. patch-file content) may themselves contain
        // the literal string "```json", which would otherwise be mistaken for a
        // markdown code-fence and cause the JSON prefix to be posted as a comment.
        if (aiResponse.strip().startsWith("{")) {
            return null; // Pure JSON, no thinking text
        }

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

        return aiResponse;
    }

    /**
     * Truncates the extracted JSON to the first complete top-level JSON object.
     * Handles the case where the AI accidentally duplicates its response – the second
     * copy would otherwise cause a {@code FAIL_ON_TRAILING_TOKENS} parse error.
     * <p>
     * Correctly handles strings (including escaped characters) so that {@code "}
     * or {@code {}/{@code }} inside a string value do not affect brace counting.
     *
     * @param json The raw extracted JSON (may contain one or more complete objects)
     * @return Substring up to and including the first closing {@code }}, or the
     *         original string if no complete object is found
     */
    String truncateToFirstJsonObject(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        int braces = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') {
                    braces++;
                } else if (c == '}') {
                    braces--;
                    if (braces == 0) {
                        return json.substring(0, i + 1);
                    }
                }
            }
        }
        return json; // no complete object found – return as-is for repairTruncatedJson
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

        // JSON is truncated - try to close the structures
        int lastCompleteObject = findLastCompleteRunTool(json);
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
        // Match either a valid \\ (double backslash, keep as-is) or a single \ followed by
        // an invalid JSON escape character (replace with \\).
        // This prevents re-processing the second \ of an already-valid \\ sequence.
        java.util.regex.Matcher m = PATTERN.matcher(json);
        return m.replaceAll(mr -> {
            if (mr.group(1) != null) {
                // Valid \\ sequence – keep unchanged
                return java.util.regex.Matcher.quoteReplacement("\\\\");
            } else {
                // Invalid \x – escape to \\x
                return java.util.regex.Matcher.quoteReplacement("\\\\" + mr.group(2));
            }
        });
    }

    /**
     * Finds the position after the last complete runTool object in the JSON.
     */
    private int findLastCompleteRunTool(String json) {
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

    /**
     * Validates the extracted JSON against the coding-agent schema. The
     * validator runs observe-only by default (see
     * {@code agent.schema.enforce}); this method only returns {@code false}
     * when the validator is configured to enforce and validation failed.
     */
    private boolean validateAgainstSchema(String jsonStr) {
        AgentSchemaValidator validator = AgentSchemaValidatorHolder.get();
        if (validator == null) {
            return true;
        }
        var violations = validator.validate(jsonStr, AgentSchema.CODING_PLAN);
        if (violations.isEmpty()) {
            return true;
        }
        if (validator.isEnforce()) {
            log.warn("Rejecting coding plan: {} schema violation(s) and enforce mode active",
                    violations.get().size());
            return false;
        }
        return true;
    }

    private List<String> normalizeArgs(Object rawArgs) {
        return switch (rawArgs) {
            case null -> List.of();
            case String s -> List.of(s);
            case List<?> list -> list.stream()
                    .filter(Objects::nonNull)
                    .map(this::normalizeSingleArg)
                    .toList();
            default -> List.of(normalizeSingleArg(rawArgs));
        };
    }

    private String normalizeSingleArg(Object arg) {
        if (arg instanceof String s) {
            return s;
        }
        if (arg instanceof Number || arg instanceof Boolean) {
            return String.valueOf(arg);
        }
        try {
            return objectMapper.writeValueAsString(arg);
        } catch (Exception e) {
            return String.valueOf(arg);
        }
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
        private AiToolRequest runTool;
        private List<AiToolRequest> runTools;
    }


    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiToolRequest {
        private String id;
        private String tool;
        private Object args;
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
