package org.remus.giteabot.agent.writerimpl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.ImplementationPlan;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public class WriterResponseParser {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WriterPlan parse(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return WriterPlan.builder().qualityAssessment("").build();
        }
        String json = extractJson(aiResponse);
        if (json == null) {
            return WriterPlan.builder()
                    .qualityAssessment(aiResponse.strip())
                    .clarifyingQuestions(List.of(aiResponse.strip()))
                    .build();
        }
        try {
            AiWriterResponse response = objectMapper.readValue(json, AiWriterResponse.class);
            return WriterPlan.builder()
                    .qualityAssessment(response.getQualityAssessment())
                    .clarifyingQuestions(nullToEmpty(response.getClarifyingQuestions()))
                    .revisedIssueDraft(response.getRevisedIssueDraft())
                    .assumptions(nullToEmpty(response.getAssumptions()))
                    .openQuestions(nullToEmpty(response.getOpenQuestions()))
                    .readyToCreate(response.isReadyToCreate())
                    .requestTools(toToolRequests(response.getRequestTools()))
                    .build();
        } catch (JacksonException e) {
            log.warn("Failed to parse writer response JSON: {}", e.getMessage());
            return WriterPlan.builder()
                    .qualityAssessment(aiResponse.strip())
                    .clarifyingQuestions(List.of(aiResponse.strip()))
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
                        return json;
                    }
                    if (braces == 0) {
                        return json.substring(0, i + 1);
                    }
                }
            }
        }
        return json;
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
