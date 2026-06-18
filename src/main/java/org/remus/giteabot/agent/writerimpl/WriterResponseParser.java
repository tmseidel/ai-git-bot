package org.remus.giteabot.agent.writerimpl;

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
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public class WriterResponseParser {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BLOCK_UNCLOSED_PATTERN = Pattern.compile("```json\\s*\\n(\\{.*)", Pattern.DOTALL);
    private static final Pattern GENERIC_JSON_BLOCK_PATTERN = Pattern.compile("```\\s*\\n(\\{.*?})\\n\\s*```", Pattern.DOTALL);
    private final ObjectMapper objectMapper = AgentJackson.mapper();

    /**
     * Returns {@code true} when {@code aiResponse} contains an extractable JSON
     * payload (a fenced ```json block, a generic fenced block, or a bare
     * object). Used by the strategy to tell a structured final answer apart from
     * plain-language narration in NATIVE tool-calling mode.
     */
    public boolean hasJsonPayload(String aiResponse) {
        return aiResponse != null && !aiResponse.isBlank() && extractJson(aiResponse) != null;
    }

    public WriterPlan parse(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            // Always populate clarifyingQuestions with at least an empty list so
            // downstream consumers (WriterPromptBuilder) never NPE.
            return WriterPlan.builder()
                    .qualityAssessment("")
                    .clarifyingQuestions(List.of())
                    .build();
        }
        String json = extractJson(aiResponse);
        if (json == null) {
            // Plain-text reply (no JSON). Surface it verbatim as the quality
            // assessment and leave clarifyingQuestions empty — the strategy
            // still takes the clarifying-comment branch because readyToCreate
            // defaults to false. Storing the same text in both fields caused
            // the prose to be rendered twice in the posted comment.
            return WriterPlan.builder()
                    .qualityAssessment(aiResponse.strip())
                    .clarifyingQuestions(List.of())
                    .build();
        }
        try {
            // Step 5: schema validation – observe-only by default. In enforce
            // mode we treat the response like an unparseable payload and fall
            // back to the assessment-only WriterPlan.
            if (!validateAgainstSchema(json)) {
                return WriterPlan.builder()
                        .qualityAssessment(aiResponse.strip())
                        .clarifyingQuestions(List.of())
                        .build();
            }
            AiWriterResponse response = objectMapper.readValue(json, AiWriterResponse.class);
            return WriterPlan.builder()
                    .qualityAssessment(response.getQualityAssessment())
                    .clarifyingQuestions(nullToEmpty(response.getClarifyingQuestions()))
                    .revisedIssueDraft(response.getRevisedIssueDraft())
                    .assumptions(nullToEmpty(response.getAssumptions()))
                    .openQuestions(nullToEmpty(response.getOpenQuestions()))
                    .readyToCreate(response.isReadyToCreate())
                    .requestFiles(nullToEmpty(response.getRequestFiles()))
                    .requestTools(toToolRequests(response.getRequestTools()))
                    .build();
        } catch (JacksonException e) {
            log.warn("Failed to parse writer response JSON: {}", e.getMessage());
            return WriterPlan.builder()
                    .qualityAssessment(aiResponse.strip())
                    .clarifyingQuestions(List.of())
                    .build();
        }
    }

    private String extractJson(String response) {
        String stripped = response.strip();
        if (stripped.startsWith("{")) {
            return truncateToFirstJsonObject(stripped);
        }
        var matcher = JSON_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return truncateToFirstJsonObject(matcher.group(1).strip());
        }
        matcher = JSON_BLOCK_UNCLOSED_PATTERN.matcher(response);
        if (matcher.find()) {
            return truncateToFirstJsonObject(matcher.group(1).strip());
        }
        matcher = GENERIC_JSON_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return truncateToFirstJsonObject(matcher.group(1).strip());
        }
        int jsonStart = response.indexOf('{');
        if (jsonStart >= 0) {
            return truncateToFirstJsonObject(response.substring(jsonStart).strip());
        }
        return null;
    }

    private String truncateToFirstJsonObject(String json) {
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
                if (c == '{') braces++;
                if (c == '}') {
                    braces--;
                    if (braces < 0) {
                        return null;
                    }
                    if (braces == 0) {
                        return json.substring(0, i + 1);
                    }
                }
            }
        }
        return json;
    }

    private boolean validateAgainstSchema(String json) {
        AgentSchemaValidator validator = AgentSchemaValidatorHolder.get();
        if (validator == null) {
            return true;
        }
        var violations = validator.validate(json, AgentSchema.WRITER_PLAN);
        if (violations.isEmpty()) {
            return true;
        }
        if (validator.isEnforce()) {
            log.warn("Rejecting writer plan: {} schema violation(s) and enforce mode active",
                    violations.get().size());
            return false;
        }
        return true;
    }

    private List<String> nullToEmpty(List<String> values) {
        return values != null ? values : List.of();
    }

    private List<ImplementationPlan.ToolRequest> toToolRequests(List<AiToolRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ImplementationPlan.ToolRequest> result = new ArrayList<>();
        int idx = 1;
        for (AiToolRequest request : requests) {
            if (request.getTool() == null || request.getTool().isBlank()) {
                continue;
            }
            result.add(ImplementationPlan.ToolRequest.builder()
                    .id(request.getId() != null && !request.getId().isBlank() ? request.getId() : "writer-tool-" + idx)
                    .tool(request.getTool())
                    .args(request.getArgs())
                    .build());
            idx++;
        }
        return result;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiWriterResponse {
        private String qualityAssessment;
        private List<String> clarifyingQuestions;
        private String revisedIssueDraft;
        private List<String> assumptions;
        private List<String> openQuestions;
        private boolean readyToCreate;
        private List<String> requestFiles;
        private List<AiToolRequest> requestTools;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiToolRequest {
        private String id;
        private String tool;
        private List<String> args;
    }
}
