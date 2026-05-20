package org.remus.giteabot.prworkflow.deployment.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpDeploymentTemplatingTest {

    @Test
    void substituteReplacesPlaceholders() {
        String out = McpDeploymentTemplating.substitute(
                "build {branch}@{sha}", Map.of("branch", "feature/x", "sha", "abc123"));

        assertThat(out).isEqualTo("build feature/x@abc123");
    }

    @Test
    void substituteLeavesUnknownPlaceholdersUntouched() {
        String out = McpDeploymentTemplating.substitute("hi {missing}", Map.of("known", "v"));

        assertThat(out).isEqualTo("hi {missing}");
    }

    @Test
    void substituteHandlesNullAndEmpty() {
        assertThat(McpDeploymentTemplating.substitute(null, Map.of())).isNull();
        assertThat(McpDeploymentTemplating.substitute("", Map.of())).isEmpty();
    }

    @Test
    void substituteEscapesDollarAndBackslashSafely() {
        // Matcher.appendReplacement would normally treat $ and \ as backreferences
        // — quoteReplacement must protect us.
        String out = McpDeploymentTemplating.substitute(
                "{key}", Map.of("key", "$1 \\n literal"));

        assertThat(out).isEqualTo("$1 \\n literal");
    }

    @Test
    void substituteDeepRecursesIntoMapsAndLists() {
        Map<String, Object> template = Map.of(
                "branch", "{branch}",
                "meta", Map.of("sha", "{sha}", "prNumber", 0),
                "tags", List.of("pr-{prNumber}", "{branch}"),
                "flag", Boolean.TRUE,
                "count", 42);

        Object out = McpDeploymentTemplating.substituteDeep(
                template, Map.of("branch", "main", "sha", "deadbeef", "prNumber", 7));

        assertThat(out).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> outMap = (Map<String, Object>) out;
        assertThat(outMap.get("branch")).isEqualTo("main");
        assertThat(outMap.get("flag")).isEqualTo(Boolean.TRUE);
        assertThat(outMap.get("count")).isEqualTo(42);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) outMap.get("meta");
        assertThat(meta.get("sha")).isEqualTo("deadbeef");
        // numbers pass through untouched
        assertThat(meta.get("prNumber")).isEqualTo(0);
        assertThat(outMap.get("tags")).isEqualTo(List.of("pr-7", "main"));
    }

    @Test
    void substituteDeepReturnsNullForNull() {
        assertThat(McpDeploymentTemplating.substituteDeep(null, Map.of())).isNull();
    }
}

