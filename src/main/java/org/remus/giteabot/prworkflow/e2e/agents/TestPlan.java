package org.remus.giteabot.prworkflow.e2e.agents;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured plan returned by the {@code TestPlannerAgent}. Mirrors the
 * schema described in {@code doc/refactoring/PR_REVIEW_AGENTIC_WORKFLOWS_IMPLEMENTATION.md}
 * §4.4:
 *
 * <pre>
 * {
 *   "framework": "playwright",
 *   "journeys": [
 *     { "id": "login",
 *       "title": "Login happy path",
 *       "steps": ["visit /", "click Login", "fill credentials", "submit"],
 *       "assertions": ["sees dashboard heading"] }
 *   ],
 *   "maxRetries": 1
 * }
 * </pre>
 *
 * <p>Extra fields the model invents are silently ignored
 * ({@link JsonIgnoreProperties#ignoreUnknown()}) so prompt drift cannot crash
 * the workflow. Missing fields keep their record defaults.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestPlan(
        String framework,
        List<Journey> journeys,
        @JsonProperty("maxRetries") Integer maxRetries) {

    public TestPlan {
        journeys = journeys == null ? List.of() : List.copyOf(journeys);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Journey(
            String id,
            String title,
            List<String> steps,
            List<String> assertions,
            String fileName) {

        public Journey {
            steps      = steps == null ? List.of() : List.copyOf(steps);
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
        }

        /**
         * Stable file name for {@code pr-test-write}. Uses the explicit
         * {@link #fileName} when set, otherwise derives a slug from
         * {@link #id} or {@link #title}.
         */
        public String resolveFileName(String defaultExtension) {
            if (fileName != null && !fileName.isBlank()) {
                return fileName.trim();
            }
            String slug = slugify(id != null && !id.isBlank() ? id : title);
            if (slug.isEmpty()) slug = "journey";
            return "tests/" + slug + defaultExtension;
        }

        private static String slugify(String raw) {
            if (raw == null) return "";
            String lower = raw.toLowerCase(java.util.Locale.ROOT).trim();
            StringBuilder sb = new StringBuilder(lower.length());
            boolean lastDash = false;
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    sb.append(c);
                    lastDash = false;
                } else if (!lastDash && sb.length() > 0) {
                    sb.append('-');
                    lastDash = true;
                }
            }
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
                sb.deleteCharAt(sb.length() - 1);
            }
            return sb.toString();
        }
    }

    public boolean isEmpty() {
        return journeys.isEmpty();
    }
}
