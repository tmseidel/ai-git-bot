package org.remus.giteabot.prworkflow.e2e.agents;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Parses the {@code TestPlannerAgent}'s assistant text into a {@link TestPlan}.
 * The model is asked (via the planner prompt) to return a single JSON object;
 * to stay tolerant we accept:
 *
 * <ul>
 *     <li>raw JSON,</li>
 *     <li>JSON wrapped in a fenced code block (with or without a language hint),</li>
 *     <li>JSON preceded or followed by prose.</li>
 * </ul>
 *
 * <p>If no balanced {@code { … }} block can be extracted, or if Jackson rejects
 * the payload, an empty {@link Optional} is returned and the caller is
 * expected to surface a graceful "planner could not produce a plan" outcome.</p>
 */
@Slf4j
public final class TestPlanParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TestPlanParser() {
        // utility
    }

    public static Optional<TestPlan> parse(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return Optional.empty();
        }
        String json = extractFirstJsonObject(stripFences(assistantText));
        if (json == null) {
            log.debug("TestPlanParser: no balanced {{…}} block found in assistant text ({} chars)",
                    assistantText.length());
            return Optional.empty();
        }
        try {
            TestPlan plan = JSON.readValue(json, TestPlan.class);
            return Optional.of(plan);
        } catch (DatabindException | StreamReadException e) {
            log.debug("TestPlanParser: Jackson rejected planner JSON: {}", e.getOriginalMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.debug("TestPlanParser: failed to parse planner JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Caps {@code plan.journeys()} at {@code maxJourneys} and returns a new
     * {@link TestPlan} with the truncated list. Returns the input unchanged
     * when the cap is already satisfied. The dropped journeys are logged so
     * operators can audit cost-guard activations.
     */
    public static TestPlan capJourneys(TestPlan plan, int maxJourneys) {
        if (plan == null || plan.journeys().size() <= maxJourneys) {
            return plan;
        }
        ArrayList<TestPlan.Journey> truncated = new ArrayList<>(plan.journeys().subList(0, maxJourneys));
        log.info("TestPlanParser: capped planner output from {} → {} journeys (maxTestCases cost guard)",
                plan.journeys().size(), maxJourneys);
        return new TestPlan(plan.framework(), truncated, plan.maxRetries());
    }

    private static String stripFences(String text) {
        // Drop common ``` / ```json fences without committing to a regex engine.
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int closingFence = trimmed.lastIndexOf("```");
            if (closingFence >= 0) {
                trimmed = trimmed.substring(0, closingFence);
            }
        }
        return trimmed;
    }

    static String extractFirstJsonObject(String haystack) {
        if (haystack == null) return null;
        int start = haystack.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < haystack.length(); i++) {
            char c = haystack.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return haystack.substring(start, i + 1);
            }
        }
        return null;
    }
}
