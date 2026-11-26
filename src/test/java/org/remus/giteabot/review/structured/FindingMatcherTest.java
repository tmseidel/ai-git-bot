package org.remus.giteabot.review.structured;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.review.structured.FindingMatcher.Candidate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which finding of a review continues which finding of the pull request's previous review. */
class FindingMatcherTest {

    @Test
    void aFindingWithASimilarTitleInTheSameFileContinuesTheEarlierOne() {
        Candidate before = new Candidate(1, null, "security", "Missing permission check in OrderController.cancel",
                "src/main/java/shop/OrderController.java", 88, 97);
        Candidate now = new Candidate(7, null, "auth", "OrderController.cancel still lacks a permission check",
                "./src/main/java/shop/OrderController.java", 120, 130);
        assertThat(FindingMatcher.match(List.of(now), List.of(before))).containsExactly(Map.entry(7L, 1L));
    }

    @Test
    void anotherFileOrAnUnrelatedTitleIsANewFinding() {
        Candidate before = new Candidate(1, null, "security", "Missing permission check", "src/A.java", 10, 12);
        Candidate otherFile = new Candidate(7, null, "security", "Missing permission check", "src/B.java", 10, 12);
        Candidate otherTitle = new Candidate(8, null, "performance", "Query inside a loop", "src/A.java", 300, 310);
        assertThat(FindingMatcher.match(List.of(otherFile, otherTitle), List.of(before))).isEmpty();
    }

    @Test
    void theSameCategoryOnNearbyLinesNeedsOnlyAFewSharedWords() {
        Candidate before = new Candidate(1, null, "error handling", "Refund exception is swallowed",
                "src/Refund.java", 40, 44);
        Candidate near = new Candidate(7, null, "Error-Handling", "Exception during refund is only logged",
                "src/Refund.java", 50, 52);
        Candidate far = new Candidate(8, null, "error-handling", "Exception during refund is only logged",
                "src/Refund.java", 400, 402);
        assertThat(FindingMatcher.match(List.of(near), List.of(before))).containsExactly(Map.entry(7L, 1L));
        assertThat(FindingMatcher.match(List.of(far), List.of(before))).isEmpty();
    }

    @Test
    void eachEarlierFindingIsContinuedAtMostOnceByTheClosestMatch() {
        Candidate before = new Candidate(1, null, "correctness", "Null check missing for customer address",
                "src/Customer.java", 10, 10);
        Candidate exact = new Candidate(7, null, "correctness", "Null check missing for customer address",
                "src/Customer.java", 10, 10);
        Candidate looser = new Candidate(8, null, "correctness", "Customer address null check missing again",
                "src/Customer.java", 30, 30);
        assertThat(FindingMatcher.match(List.of(looser, exact), List.of(before))).containsExactly(Map.entry(7L, 1L));
    }

    @Test
    void theChainOfAContinuedFindingIsItsFirstFinding() {
        assertThat(new Candidate(9, 3L, "x", "y", null, null, null).origin()).isEqualTo(3L);
        assertThat(new Candidate(9, null, "x", "y", null, null, null).origin()).isEqualTo(9L);
    }

    @Test
    void theFingerprintIgnoresCaseWordOrderAndPathPrefixes() {
        assertThat(FindingMatcher.fingerprint("./src/A.java", "Auth", "Missing permission CHECK"))
                .isEqualTo(FindingMatcher.fingerprint("src/A.java", "security", "check permission missing"))
                .hasSize(64);
        assertThat(FindingMatcher.fingerprint("src/A.java", "security", "Missing permission check"))
                .isNotEqualTo(FindingMatcher.fingerprint("src/B.java", "security", "Missing permission check"));
    }
}
