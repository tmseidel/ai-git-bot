package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.agent.shared.AgentJackson;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewFindingsLedgerTest {

    private final ReviewFindingsLedger ledger = new ReviewFindingsLedger();

    @Test
    void assignsStableIdsAndRemovesOnlyWithAReason() {
        assertThat(ledger.requiredContext()).isNull();
        assertThat(ledger.apply(json("""
                {"add":[{"severity":"BLOCKER","title":"Race","file":"A.java","line":3},
                        {"severity":"low","title":"Typo"}]}"""))).isEqualTo("Recorded. Current findings: F1, F2");

        assertThat(ledger.apply(json("""
                {"remove":[{"id":"F1","reason":"Lock is held by caller"}],"add":[{"severity":"medium","title":"Race v2"}]}
                """))).isEqualTo("Recorded. Current findings: F2, F3");
        assertThat(ledger.requiredContext())
                .contains("- F2 [LOW] Typo", "- F3 [MEDIUM] Race v2")
                .doesNotContain("F1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"add\":[]}",
            "{\"remove\":[{\"id\":\"F9\",\"reason\":\"gone\"}]}",
            "{\"remove\":[{\"id\":\"F1\"}]}",
            "{\"remove\":[{\"id\":\"F1\",\"reason\":\"a\"},{\"id\":\"F1\",\"reason\":\"b\"}]}",
            "{\"add\":[{\"severity\":\"critical\",\"title\":\"X\"}]}",
            "{\"add\":[{\"severity\":\"low\"}]}",
            "{\"add\":[{\"severity\":\"low\",\"title\":\"X\",\"line\":4}]}",
            "{\"add\":[{\"severity\":\"low\",\"title\":\"X\",\"file\":\"A\",\"line\":0}]}",
            "{\"add\":[{\"severity\":\"low\",\"title\":\"X\",\"file\":\"A\",\"line\":\"4\"}]}",
            "{\"add\":{\"severity\":\"low\",\"title\":\"X\"}}",
            "{\"add\":[{\"severity\":\"low\",\"title\":\"ok\"}],\"remove\":[{\"id\":\"F7\",\"reason\":\"x\"}]}"})
    void invalidCallsChangeNothing(String args) {
        ledger.apply(json("{\"add\":[{\"severity\":\"low\",\"title\":\"Kept\"}]}"));

        assertThat(ledger.apply(json(args))).startsWith("Rejected, nothing recorded: ");

        assertThat(ledger.requiredContext()).contains("- F1 [LOW] Kept").doesNotContain("F2");
    }

    @Test
    void completenessHoldsOnlyUntilTheNextChange() {
        assertThat(ledger.apply(json("{\"complete\":true}"))).isEqualTo("Recorded. Current findings: none (confirmed complete)");
        assertThat(ledger.isComplete()).isTrue();

        ledger.apply(json("{\"add\":[{\"severity\":\"medium\",\"title\":\"Late find\"}]}"));
        assertThat(ledger.isComplete()).isFalse();
        assertThat(ledger.findings()).extracting(ReviewFindingsLedger.Finding::id).containsExactly("F1");

        assertThat(ledger.apply(json("{\"complete\":\"yes\"}"))).startsWith("Rejected");
        assertThat(ledger.apply(json("{\"complete\":false}"))).startsWith("Rejected");
        assertThat(ledger.isComplete()).isFalse();
    }

    @Test
    void boundsAreReportedSoTheModelCanShortenOrMerge() {
        String finding = "{\"severity\":\"low\",\"title\":\"T\",\"description\":\"" + "d".repeat(600) + "\"}";
        String batch = "{\"add\":[" + String.join(",", java.util.Collections.nCopies(15, finding)) + "]}";
        assertThat(ledger.apply(json(batch))).startsWith("Recorded.");

        assertThat(ledger.apply(json(batch)))
                .startsWith("Rejected, nothing recorded: record would be ")
                .endsWith("> 16000 chars; shorten descriptions or merge related findings");
        assertThat(ledger.apply(json("{\"add\":[{\"severity\":\"low\",\"title\":\"" + "t".repeat(201) + "\"}]}")))
                .isEqualTo("Rejected, nothing recorded: \"title\" exceeds 200 chars");
    }

    private static JsonNode json(String text) {
        return AgentJackson.mapper().readTree(text);
    }
}
