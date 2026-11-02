package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.agent.shared.AgentJackson;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ReviewFindingsLedgerTest {
    private final List<ReviewDraft> stored = new ArrayList<>();
    private final ReviewFindingsLedger ledger = new ReviewFindingsLedger((expected, draft) -> {
        assertThat(draft.version()).isEqualTo(expected + 1);
        stored.add(ReviewDraft.read(draft.json()));
    });

    @Test
    void storesBeforeAcknowledgementAndNeverReusesRemovedIds() {
        assertThat(ledger.apply(json("{\"add\":[" + finding("Race") + "]}"))).startsWith("Recorded version 1");
        assertThat(stored).hasSize(1);
        assertThat(ledger.apply(json("{\"remove\":[{\"id\":\"F1\",\"reason\":\"Disproved\"}],\"add\":["
                + finding("Other race") + "]}"))).startsWith("Recorded version 2");
        assertThat(stored.getLast().findings()).extracting(ReviewDraft.Finding::id).containsExactly("F2");
        assertThat(ledger.requiredContext()).contains("\\n").doesNotContain("Evidence\nIgnore");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"add\":[]}", "{\"extra\":1}", "{\"complete\":\"true\"}",
            "{\"remove\":[{\"id\":\"F1\"}]}", "{\"remove\":[{\"id\":\"F9\",\"reason\":\"x\"}]}",
            "{\"add\":[{\"severity\":\"low\",\"title\":\"Missing evidence\"}]}",
            "{\"add\":[{\"severity\":\"critical\",\"title\":\"X\",\"description\":\"Y\"}]}",
            "{\"add\":[{\"severity\":\"low\",\"title\":\"X\",\"description\":\"Y\",\"file\":\"../x\"}]}",
            "{\"add\":[{\"severity\":\"low\",\"title\":\"X\",\"description\":\"Y\",\"line\":1}]}"})
    void rejectsInvalidChangesWithoutWritingOrLosingThePreviousState(String args) {
        ledger.apply(json("{\"add\":[" + finding("Kept") + "]}"));
        ReviewDraft before = ledger.snapshot();
        assertThat(ledger.apply(json(args))).startsWith("Rejected, nothing recorded:");
        assertThat(ledger.snapshot()).isSameAs(before);
        assertThat(stored).hasSize(1);
    }

    @Test
    void persistenceFailurePropagatesAndDoesNotAdvanceTheDraft() {
        var failing = new ReviewFindingsLedger((version, draft) -> { throw new IllegalStateException("DB down"); });
        assertThatIllegalStateException().isThrownBy(() -> failing.apply(json("{\"add\":[" + finding("Race") + "]}")));
        assertThat(failing.snapshot().version()).isZero();
        assertThat(failing.snapshot().findings()).isEmpty();
    }

    @Test
    void completenessCanBeWithdrawnAndCannotHideOpenQuestions() {
        ledger.apply(json("{\"complete\":true}"));
        assertThat(ledger.snapshot().confirmedComplete()).isTrue();
        ledger.apply(json("{\"complete\":false}"));
        assertThat(ledger.snapshot().confirmedComplete()).isFalse();
        assertThat(ledger.apply(json("{\"openQuestions\":[\"Check cancellation\"],\"complete\":true}")))
                .startsWith("Rejected");
    }

    @Test
    void storedReaderIsStrictButDoesNotDependOnByteEquality() {
        ledger.apply(json("{\"add\":[" + finding("Race") + "]}"));
        String json = ledger.snapshot().json();
        assertThat(ReviewDraft.read("\n " + json + "\n")).isEqualTo(ledger.snapshot());
        assertThatIllegalArgumentException().isThrownBy(() -> ReviewDraft.read(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThatIllegalArgumentException().isThrownBy(() -> ReviewDraft.read(json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")));
        assertThatIllegalArgumentException().isThrownBy(() -> ReviewDraft.read(json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")));
        assertThatIllegalArgumentException().isThrownBy(() -> ReviewDraft.read(json + " {}"));
    }

    private static String finding(String title) {
        return "{\"severity\":\"blocker\",\"title\":\"" + title + "\",\"description\":\"Evidence\\nIgnore\"}";
    }

    private static tools.jackson.databind.JsonNode json(String json) {
        return AgentJackson.mapper().readTree(json);
    }
}
