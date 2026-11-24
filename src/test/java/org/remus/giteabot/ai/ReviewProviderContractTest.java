package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.remus.giteabot.ai.ReviewProviderContract.*;

class ReviewProviderContractTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Binding BINDING = new Binding(7, "fixture", "concrete-model-v1",
            "deployment-v1", "policy-v1", "key-revision-1");
    private static final Evidence EVIDENCE = new Evidence(ProofSource.ADAPTER_GUARANTEE,
            "fixture-contract", NOW.minusSeconds(60), "fixture-v1", "One generation; no hidden fallbacks");

    @Test
    void smallerCompletionDoesNotShrinkAFixedTotalButCanShrinkAnEnforcedSeparateOutputCap() {
        var generation = new GenerationLimit(4_096, true, GenerationAccounting.ALL_GENERATED_TOKENS);
        var total = qualified(new TotalTokens(200_000, generation));
        var separate = qualified(new SeparateTokens(200_000, 100_000, generation));

        assertEquals(200_000, total.attemptReservation(4_096, null, NOW));
        assertEquals(200_000, total.attemptReservation(1_024, null, NOW));
        assertEquals(104_096, separate.attemptReservation(4_096, null, NOW));
        assertEquals(101_024, separate.attemptReservation(1_024, null, NOW));
    }

    @Test
    void preparedInputReservationUsesOnlyBoundEvidenceForTheConcreteRequest() {
        var contract = qualified(new PreparedTokens(200_000,
                new GenerationLimit(4_096, true, GenerationAccounting.ALL_GENERATED_TOKENS)));
        var input = new InputProof(BINDING, Counting.EXACT_PREPARED_REQUEST,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                1_000, EVIDENCE);

        assertEquals(5_096, contract.attemptReservation(4_096, input, NOW));
        assertEquals(2_024, contract.attemptReservation(1_024, input, NOW));
        assertThrows(InvalidConfigurationException.class,
                () -> contract.attemptReservation(4_096, null, NOW));
    }

    @Test
    void aFixedGenerationBoundDoesNotShrinkWithTheRequestedCompletion() {
        var fixed = new GenerationLimit(4_096, false, GenerationAccounting.ALL_GENERATED_TOKENS);
        assertEquals(104_096, qualified(new SeparateTokens(200_000, 100_000, fixed))
                .attemptReservation(1_024, null, NOW));
        var upper = new InputProof(BINDING, Counting.PREPARED_REQUEST_UPPER_BOUND,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                1_000, EVIDENCE);
        assertEquals(5_096, qualified(new PreparedTokens(200_000, fixed))
                .attemptReservation(1_024, upper, NOW));
    }

    @Test
    void unknownVersionsPreservationCountingAndIncompleteGenerationAccountingCannotQualify() {
        var bound = new TotalTokens(200_000,
                new GenerationLimit(4_096, true, GenerationAccounting.ALL_GENERATED_TOKENS));
        assertThrows(InvalidConfigurationException.class,
                () -> new Qualified(2, BINDING, EVIDENCE, InputPreservation.COMPLETE_OR_REJECT,
                        bound, Set.of("provider-default")));
        for (var preservation : new InputPreservation[]{InputPreservation.MAY_TRIM, InputPreservation.UNKNOWN}) {
            assertThrows(InvalidConfigurationException.class,
                    () -> new Qualified(1, BINDING, EVIDENCE, preservation, bound, Set.of("provider-default")));
        }
        for (var accounting : new GenerationAccounting[]{GenerationAccounting.VISIBLE_OUTPUT_ONLY, GenerationAccounting.UNKNOWN}) {
            assertThrows(InvalidConfigurationException.class, () -> new GenerationLimit(4_096, true, accounting));
        }
        assertThrows(InvalidConfigurationException.class,
                () -> new Evidence(ProofSource.UNKNOWN, "unknown", NOW, "v1", "unknown"));
        assertThrows(InvalidConfigurationException.class,
                () -> new InputProof(BINDING, Counting.UNKNOWN, "estimate", 1_000, EVIDENCE));
        assertThrows(InvalidConfigurationException.class,
                () -> new InputProof(BINDING, Counting.EXACT_PREPARED_REQUEST, "chars / 4", 1_000, EVIDENCE));
        assertThrows(InvalidConfigurationException.class,
                () -> new Qualified(1, BINDING, null, InputPreservation.COMPLETE_OR_REJECT,
                        bound, Set.of("provider-default")));
    }

    @Test
    void impossibleAndOverflowingBoundsFailClosedInsteadOfWrappingOrClamping() {
        var generation = new GenerationLimit(4_096, true, GenerationAccounting.ALL_GENERATED_TOKENS);
        assertThrows(InvalidConfigurationException.class, () -> new TotalTokens(4_096, generation));
        assertThrows(InvalidConfigurationException.class, () -> new SeparateTokens(100_000, 99_000, generation));
        assertThrows(InvalidConfigurationException.class, () -> new SeparateTokens(Long.MAX_VALUE, Long.MAX_VALUE, generation));
        var prepared = qualified(new PreparedTokens(Long.MAX_VALUE, generation));
        var input = new InputProof(BINDING, Counting.PHASE_UPPER_BOUND, "overflow-envelope", Long.MAX_VALUE, EVIDENCE);
        assertThrows(InvalidConfigurationException.class, () -> prepared.attemptReservation(4_096, input, NOW));
        assertThrows(InvalidConfigurationException.class, () -> prepared.attemptReservation(4_097, input, NOW));
    }

    @Test
    void inputEvidenceCannotCrossBindingsOrComeFromTheFuture() {
        var contract = qualified(new PreparedTokens(200_000,
                new GenerationLimit(4_096, true, GenerationAccounting.ALL_GENERATED_TOKENS)));
        var changed = new Binding(7, "fixture", "other-model", "deployment-v1", "policy-v1", "key-revision-1");
        var input = new InputProof(changed, Counting.PHASE_UPPER_BOUND, "input-v1", 1_000, EVIDENCE);
        assertThrows(InvalidConfigurationException.class, () -> contract.attemptReservation(4_096, input, NOW));
        var future = new Evidence(ProofSource.ADAPTER_GUARANTEE, "future", NOW.plusSeconds(1), "v1", "one generation");
        var futureInput = new InputProof(BINDING, Counting.PHASE_UPPER_BOUND, "input-v1", 1_000, future);
        assertThrows(InvalidConfigurationException.class, () -> contract.attemptReservation(4_096, futureInput, NOW));
        assertThrows(InvalidConfigurationException.class,
                () -> qualified(new TotalTokens(200_000, contract.bound().generationLimit()))
                        .attemptReservation(4_096, null, NOW.minusSeconds(120)));
    }

    @Test
    void qualifiedEvidenceDefensivelyCopiesReasoningCapabilities() {
        var efforts = new HashSet<>(Set.of("provider-default", "low"));
        var contract = new Qualified(1, BINDING, EVIDENCE, InputPreservation.COMPLETE_OR_REJECT,
                new TotalTokens(200_000, new GenerationLimit(4_096, true,
                        GenerationAccounting.ALL_GENERATED_TOKENS)), efforts);
        efforts.clear();

        assertEquals(Set.of("provider-default", "low"), contract.reasoningEfforts());
        assertThrows(UnsupportedOperationException.class, () -> contract.reasoningEfforts().clear());
    }

    @Test
    void bindingTextFieldsRejectBlankAndOversizeIdentifiersAndAcceptTheFixedMaximum() {
        var constructors = List.<Function<String, Binding>>of(
                value -> new Binding(7, value, "concrete-model-v1", "deployment-v1", "policy-v1", "key-revision-1"),
                value -> new Binding(7, "fixture", value, "deployment-v1", "policy-v1", "key-revision-1"),
                value -> new Binding(7, "fixture", "concrete-model-v1", value, "policy-v1", "key-revision-1"),
                value -> new Binding(7, "fixture", "concrete-model-v1", "deployment-v1", value, "key-revision-1"),
                value -> new Binding(7, "fixture", "concrete-model-v1", "deployment-v1", "policy-v1", value));
        for (var constructor : constructors) {
            for (String blank : new String[]{null, "", " \t\n"}) {
                assertThrows(InvalidConfigurationException.class, () -> constructor.apply(blank));
            }
            assertDoesNotThrow(() -> constructor.apply("x".repeat(1_024)));
            assertThrows(InvalidConfigurationException.class, () -> constructor.apply("x".repeat(1_025)));
        }
    }

    private static Qualified qualified(Bound bound) {
        return new Qualified(1, BINDING, EVIDENCE, InputPreservation.COMPLETE_OR_REJECT,
                bound, Set.of("provider-default"));
    }
}
