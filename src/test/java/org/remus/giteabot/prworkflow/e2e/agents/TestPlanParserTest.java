package org.remus.giteabot.prworkflow.e2e.agents;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestPlanParserTest {

    @Test
    void parsesRawJson() {
        String raw = """
                { "framework": "playwright",
                  "journeys": [
                    {"id":"login","title":"Login","steps":["a"],"assertions":["b"],"fileName":"tests/login.spec.ts"}
                  ],
                  "maxRetries": 2 }
                """;

        Optional<TestPlan> parsed = TestPlanParser.parse(raw);

        assertThat(parsed).isPresent();
        TestPlan plan = parsed.get();
        assertThat(plan.framework()).isEqualTo("playwright");
        assertThat(plan.maxRetries()).isEqualTo(2);
        assertThat(plan.journeys()).hasSize(1);
        TestPlan.Journey j = plan.journeys().get(0);
        assertThat(j.id()).isEqualTo("login");
        assertThat(j.title()).isEqualTo("Login");
        assertThat(j.steps()).containsExactly("a");
        assertThat(j.assertions()).containsExactly("b");
        assertThat(j.resolveFileName(".spec.ts")).isEqualTo("tests/login.spec.ts");
    }

    @Test
    void tolerantOfMarkdownFences() {
        String raw = """
                Here is the plan:
                ```json
                {
                  "framework": "playwright",
                  "journeys": [{"id":"x","title":"X","steps":["s"],"assertions":["a"]}]
                }
                ```
                Done.
                """;

        Optional<TestPlan> parsed = TestPlanParser.parse(raw);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().journeys()).hasSize(1);
    }

    @Test
    void returnsEmptyForNonJson() {
        assertThat(TestPlanParser.parse("I cannot produce a plan."))
                .isEmpty();
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertThat(TestPlanParser.parse(null)).isEmpty();
        assertThat(TestPlanParser.parse("   ")).isEmpty();
    }

    @Test
    void unknownFieldsAreIgnored() {
        String raw = """
                {"framework":"playwright","extraField":42,
                 "journeys":[{"id":"a","title":"A","steps":["s"],"assertions":["x"],"trail":"ignored"}]}
                """;

        Optional<TestPlan> parsed = TestPlanParser.parse(raw);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().journeys()).hasSize(1);
    }

    @Test
    void resolveFileNameFallsBackToSlug() {
        TestPlan.Journey j = new TestPlan.Journey("Cool ID!", "ignored", null, null, null);
        assertThat(j.resolveFileName(".spec.ts")).isEqualTo("tests/cool-id.spec.ts");

        TestPlan.Journey blank = new TestPlan.Journey(null, "  Some Title  ", null, null, null);
        assertThat(blank.resolveFileName(".spec.ts")).isEqualTo("tests/some-title.spec.ts");

        TestPlan.Journey nameless = new TestPlan.Journey(null, null, null, null, null);
        assertThat(nameless.resolveFileName(".spec.ts")).isEqualTo("tests/journey.spec.ts");
    }

    @Test
    void capJourneysTruncates() {
        TestPlan plan = new TestPlan("playwright",
                java.util.List.of(
                        new TestPlan.Journey("a", "A", null, null, null),
                        new TestPlan.Journey("b", "B", null, null, null),
                        new TestPlan.Journey("c", "C", null, null, null)),
                1);

        TestPlan capped = TestPlanParser.capJourneys(plan, 2);
        assertThat(capped.journeys()).hasSize(2);
        assertThat(capped.journeys().get(0).id()).isEqualTo("a");
        assertThat(capped.journeys().get(1).id()).isEqualTo("b");
    }

    @Test
    void capJourneysIsNoOpWhenWithinLimit() {
        TestPlan plan = new TestPlan("playwright",
                java.util.List.of(new TestPlan.Journey("only", "Only", null, null, null)),
                1);

        TestPlan capped = TestPlanParser.capJourneys(plan, 5);
        assertThat(capped).isSameAs(plan);
    }
}
