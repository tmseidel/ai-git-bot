package org.remus.giteabot.agent.critic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentMetricsHolder;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Step 7.3 — optional Critic / Reflection step.
 *
 * <p>When {@link AgentConfigProperties.CriticConfig#isEnabled()} is
 * {@code false} (default), {@link #review(String, String, String, String, AiClient)}
 * short-circuits with {@link ReflectionResult#skipped()} <strong>without
 * making any LLM call</strong> — verified by
 * {@code CriticAgentTest#disabledNeverCallsAi}.</p>
 *
 * <p>When enabled, a single chat call is sent with the {@code prompts/critic.md}
 * system prompt. The model is expected to return a JSON object of the form
 * {@code {"outcome": "...", "feedback": "..."}}. Parse failures default to
 * {@code APPROVE} (fail-open) so that an unreliable critic cannot block all
 * PRs; the failure is logged and counted as {@code outcome=parse_error} in
 * the {@code agent.critic.outcome_total} metric.</p>
 */
@Slf4j
public class CriticAgent {

    private static final String PROMPT_RESOURCE = "prompts/critic.md";

    private final AgentConfigProperties.CriticConfig config;
    private final AgentConfigProperties.BudgetConfig budget;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public CriticAgent(AgentConfigProperties.CriticConfig config,
                       AgentConfigProperties.BudgetConfig budget,
                       ObjectMapper objectMapper) {
        this.config = config;
        this.budget = budget;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.systemPrompt = loadSystemPrompt();
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }


    /**
     * Performs one critic review. Returns {@link ReflectionResult#skipped()}
     * (and records the {@code skipped} metric) without an AI call when
     * {@link #isEnabled()} is {@code false}.
     *
     * @param issueTitle title of the originating issue (may be {@code null})
     * @param issueBody  body of the originating issue (may be {@code null})
     * @param planSummary the agent's own one-line summary of its change
     * @param diffStat   {@code git diff --stat HEAD} excerpt of the workspace
     * @param aiClient   the bot-scoped AI client to use for the review call
     */
    public ReflectionResult review(String issueTitle, String issueBody,
                                   String planSummary, String diffStat,
                                   AiClient aiClient) {
        if (!isEnabled()) {
            AgentMetricsHolder.recordCriticOutcome("skipped");
            return ReflectionResult.skipped();
        }
        if (aiClient == null) {
            log.warn("CriticAgent invoked without an AiClient; skipping review");
            AgentMetricsHolder.recordCriticOutcome("skipped");
            return ReflectionResult.skipped();
        }

        String userMessage = buildUserMessage(issueTitle, issueBody, planSummary, diffStat);
        int maxTokens = budget != null ? budget.getMaxTokensPerCall() : 4096;

        String response;
        try {
            response = aiClient.chat(List.of(), userMessage, systemPrompt, null, maxTokens);
        } catch (RuntimeException e) {
            log.warn("Critic AI call failed ({}); approving by default", e.getMessage());
            AgentMetricsHolder.recordCriticOutcome("error");
            return ReflectionResult.approve("Critic call failed: " + e.getMessage());
        }

        ReflectionResult result = parseResponse(response);
        AgentMetricsHolder.recordCriticOutcome(result.outcome().name().toLowerCase(Locale.ROOT));
        return result;
    }

    private String buildUserMessage(String issueTitle, String issueBody,
                                    String planSummary, String diffStat) {
        return "## Issue\n\n" +
                "Title: " + (issueTitle == null ? "(none)" : issueTitle) + "\n\n" +
                "Body:\n" + (issueBody == null || issueBody.isBlank()
                ? "(empty)" : issueBody.strip()) +
                "\n\n" +
                "## Plan summary\n\n" +
                (planSummary == null || planSummary.isBlank()
                        ? "(no summary provided)" : planSummary.strip()) +
                "\n\n" +
                "## Diff stats\n\n" +
                (diffStat == null || diffStat.isBlank()
                        ? "(no diff)" : diffStat.strip()) +
                "\n";
    }

    ReflectionResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Critic returned empty response; approving by default");
            return ReflectionResult.approve("Critic returned no response");
        }
        String json = extractJson(response);
        try {
            JsonNode node = objectMapper.readTree(json);
            String outcome = node.path("outcome").asString("APPROVE")
                    .trim().toUpperCase(Locale.ROOT);
            String feedback = node.path("feedback").asString("").trim();
            return switch (outcome) {
                case "ITERATE" -> ReflectionResult.iterate(feedback);
                case "ABORT"   -> ReflectionResult.abort(feedback);
                default        -> ReflectionResult.approve(feedback);
            };
        } catch (Exception e) {
            log.warn("Failed to parse critic JSON response ({}); approving by default", e.getMessage());
            AgentMetricsHolder.recordCriticOutcome("parse_error");
            return ReflectionResult.approve("Critic response was not valid JSON");
        }
    }

    private String extractJson(String response) {
        String trimmed = response.strip();
        // Strip ```json ... ``` fences if present.
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closing = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && closing > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, closing).strip();
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private static String loadSystemPrompt() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(PROMPT_RESOURCE).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load critic prompt from {}: {}", PROMPT_RESOURCE, e.getMessage());
            return "You are a code review critic. Respond with JSON: "
                    + "{\"outcome\":\"APPROVE|ITERATE|ABORT\",\"feedback\":\"...\"}";
        }
    }
}
