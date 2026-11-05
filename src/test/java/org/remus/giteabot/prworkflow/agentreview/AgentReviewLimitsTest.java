package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.ReviewProviderContract;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.remus.giteabot.ai.ReviewProviderContract.*;

class AgentReviewLimitsTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Binding BINDING = new Binding(7, "fixture", "concrete-model-v1",
            "deployment-v1", "policy-v1", "key-revision-1");
    private static final Evidence EVIDENCE = new Evidence(ProofSource.ADAPTER_GUARANTEE,
            "fixture-contract", NOW.minusSeconds(60), "fixture-v1", "One generation; no hidden fallbacks");

    @Test
    void defaultsInheritTheIntegrationAndReserveBothTerminalAttemptsAndTheirRetries() {
        var limits = resolve(AgentReviewLimits.Configuration.defaults(), total(200_000), null, null);

        assertEquals(12, limits.getConfigured().maxToolRounds());
        assertEquals(18, limits.getConfigured().maxProviderCalls());
        assertEquals(2_000_000, limits.getConfigured().maxRunTokens());
        assertEquals(24_000, limits.getConfigured().maxRenderedReviewChars());
        assertNull(limits.getConfigured().explorationMaxCompletionTokens());
        assertNull(limits.getConfigured().finalizationMaxCompletionTokens());
        assertEquals(4_096, limits.getExplorationMaxCompletionTokens());
        assertEquals(4_096, limits.getFinalizationMaxCompletionTokens());
        assertEquals(4, limits.getReservedTerminalCalls());
        assertEquals(800_000, limits.getReservedTerminalTokens());
        assertEquals(1_000_000, limits.getMinimumRunTokens());
        assertEquals(Instant.parse("2026-10-18T13:00:00Z"), limits.getRunDeadlineAt());
        assertEquals(Duration.ofSeconds(120), limits.getTimeouts().providerAttemptTimeout());
        assertEquals(Duration.ofSeconds(300), limits.getTimeouts().toolAttemptTimeout());
        assertEquals(Duration.ofSeconds(600), limits.getReservedTerminalDuration());
        assertEquals(2, limits.getMaxFinalizationAttempts());
        assertEquals(1, limits.getMaxTransportRetriesPerTerminalAttempt());
        assertEquals(24_000, limits.getMaxReviewMemoryChars());
        assertEquals(Duration.ofSeconds(30), limits.getMaxRetryDelay());
    }

    @Test
    void theMinimumBudgetIncludesOneExplorationAndAllFourTerminalCalls() {
        var exact = new AgentReviewLimits.Configuration(1, 5, 1_000_000, null, null, 24_000);
        assertEquals(1_000_000, resolve(exact, total(200_000), null, null).getMinimumRunTokens());
        assertThrows(InvalidConfigurationException.class, () -> resolve(
                new AgentReviewLimits.Configuration(1, 4, 1_000_000, null, null, 24_000), total(200_000), null, null));
        assertThrows(InvalidConfigurationException.class, () -> resolve(
                new AgentReviewLimits.Configuration(1, 5, 999_999, 1L, 1L, 24_000), total(200_000), null, null));
    }

    @Test
    void explicitPhasesAreNeverClampedAndMemoryDoesNotFollowTheRenderedLimit() {
        var limits = resolve(new AgentReviewLimits.Configuration(30, 18, 2_000_000, 1_024L, 2_048L, 12_000),
                total(200_000), null, null);
        assertEquals(1_024, limits.getExplorationMaxCompletionTokens());
        assertEquals(2_048, limits.getFinalizationMaxCompletionTokens());
        assertEquals(12_000, limits.getConfigured().maxRenderedReviewChars());
        assertEquals(24_000, limits.getMaxReviewMemoryChars());
        assertThrows(InvalidConfigurationException.class, () -> resolve(
                new AgentReviewLimits.Configuration(12, 18, 2_000_000, 4_097L, null, 24_000), total(200_000), null, null));
        assertThrows(InvalidConfigurationException.class, () -> resolve(
                new AgentReviewLimits.Configuration(12, 18, 2_000_000, null, 4_097L, 24_000), total(200_000), null, null));
    }

    @Test
    void fixedGenerationProofCannotPretendToEnforceASmallerPhaseOrIntegrationCap() {
        var contract = new Qualified(1, BINDING, EVIDENCE, InputPreservation.COMPLETE_OR_REJECT,
                new SeparateTokens(200_000, 100_000, new GenerationLimit(4_096, false,
                        GenerationAccounting.ALL_GENERATED_TOKENS)), Set.of("provider-default"));
        assertEquals(104_096, resolve(AgentReviewLimits.Configuration.defaults(), contract, null, null)
                .getTerminalAttemptReservation());
        assertThrows(InvalidConfigurationException.class, () -> resolve(
                new AgentReviewLimits.Configuration(12, 18, 2_000_000, 1_024L, null, 24_000), contract, null, null));
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(),
                new AgentReviewLimits.Integration(BINDING, 2_048, 200_000, "provider-default"), contract,
                new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
    }

    @Test
    void individualRangesFailRatherThanFallingBackToDefaults() {
        for (int rounds : new int[]{0, 31}) {
            assertThrows(InvalidConfigurationException.class,
                    () -> new AgentReviewLimits.Configuration(rounds, 18, 2_000_000, null, null, 24_000));
        }
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Configuration(12, 3, 2_000_000, null, null, 24_000));
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Configuration(12, 18, 0, null, null, 24_000));
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Configuration(12, 18, 2_000_000, 0L, null, 24_000));
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Configuration(12, 18, 2_000_000, null, -1L, 24_000));
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Configuration(12, 18, 2_000_000, null, null, 0));
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Integration(BINDING, 4_096, 4_096, "provider-default"));
    }

    @Test
    void unsupportedNullAndUnprovenReasoningDenyAdmission() {
        assertThrows(InvalidConfigurationException.class,
                () -> resolve(AgentReviewLimits.Configuration.defaults(), ReviewProviderContract.unsupported(), null, null));
        assertThrows(InvalidConfigurationException.class,
                () -> resolve(AgentReviewLimits.Configuration.defaults(), null, null, null));
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(),
                new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "high"), total(200_000),
                new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(),
                new AgentReviewLimits.Integration(BINDING, 4_097, 200_000, "provider-default"), total(200_000),
                new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(),
                new AgentReviewLimits.Integration(BINDING, 4_096, 200_001, "provider-default"), total(200_000),
                new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
    }

    @Test
    void everyConcreteBindingDimensionIsCheckedBeforeAdmission() {
        var changedBindings = new Binding[]{
                new Binding(8, "fixture", "concrete-model-v1", "deployment-v1", "policy-v1", "key-revision-1"),
                new Binding(7, "other", "concrete-model-v1", "deployment-v1", "policy-v1", "key-revision-1"),
                new Binding(7, "fixture", "other-model", "deployment-v1", "policy-v1", "key-revision-1"),
                new Binding(7, "fixture", "concrete-model-v1", "other-deployment", "policy-v1", "key-revision-1"),
                new Binding(7, "fixture", "concrete-model-v1", "deployment-v1", "policy-v2", "key-revision-1"),
                new Binding(7, "fixture", "concrete-model-v1", "deployment-v1", "policy-v1", "key-revision-2")
        };
        for (Binding binding : changedBindings) {
            assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                    AgentReviewLimits.Configuration.defaults(),
                    new AgentReviewLimits.Integration(binding, 4_096, 200_000, "provider-default"), total(200_000),
                    new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
        }
    }

    @Test
    void aSmallerOperatorContextRequiresInputProofAndNeverLowersTheEnforcedTotalBound() {
        var integration = new AgentReviewLimits.Integration(BINDING, 4_096, 150_000, "provider-default");
        var config = AgentReviewLimits.Configuration.defaults();
        var repository = new AgentReviewLimits.RepositoryLimits(65_536, 1_000);
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(config, integration,
                total(200_000), repository, null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
        var limits = AgentReviewLimits.resolve(config, integration, total(200_000), repository,
                envelope(1_000), envelope(100_000), NOW, NOW, AgentReviewLimits.Timeouts.defaults());
        assertEquals(200_000, limits.getExplorationAttemptReservation());
        assertEquals(800_000, limits.getReservedTerminalTokens());
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(config, integration,
                total(200_000), repository, envelope(1_000), envelope(149_000), NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
    }

    @Test
    void preparedAccountingReservesAProvenEnvelopeForRepairAndRetryNotOneTerminalPrompt() {
        var generation = new GenerationLimit(4_096, true, GenerationAccounting.ALL_GENERATED_TOKENS);
        var contract = new Qualified(1, BINDING, EVIDENCE, InputPreservation.COMPLETE_OR_REJECT,
                new PreparedTokens(200_000, generation), Set.of("provider-default"));
        var prepared = new InputProof(BINDING, Counting.EXACT_PREPARED_REQUEST,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 1_000, EVIDENCE);
        var limits = resolve(AgentReviewLimits.Configuration.defaults(), contract, prepared, envelope(2_000));
        assertEquals(5_096, limits.getExplorationAttemptReservation());
        assertEquals(6_096, limits.getTerminalAttemptReservation());
        assertEquals(24_384, limits.getReservedTerminalTokens());
        assertEquals(29_480, limits.getMinimumRunTokens());
        assertThrows(InvalidConfigurationException.class,
                () -> resolve(AgentReviewLimits.Configuration.defaults(), contract, prepared, prepared));
        assertThrows(InvalidConfigurationException.class,
                () -> resolve(AgentReviewLimits.Configuration.defaults(), contract, prepared, null));
        assertThrows(InvalidConfigurationException.class,
                () -> resolve(AgentReviewLimits.Configuration.defaults(), contract, prepared, envelope(200_000)));
    }

    @Test
    void preflightUsesSeparateInputAndGenerationBoundsForBothPhaseReserves() {
        var contract = new Qualified(1, BINDING, EVIDENCE, InputPreservation.COMPLETE_OR_REJECT,
                new SeparateTokens(200_000, 100_000, new GenerationLimit(4_096, true,
                        GenerationAccounting.ALL_GENERATED_TOKENS)), Set.of("provider-default"));
        var limits = resolve(new AgentReviewLimits.Configuration(12, 18, 2_000_000, 1_024L, 2_048L, 24_000),
                contract, null, null);
        assertEquals(101_024, limits.getExplorationAttemptReservation());
        assertEquals(102_048, limits.getTerminalAttemptReservation());
        assertEquals(408_192, limits.getReservedTerminalTokens());
        assertEquals(509_216, limits.getMinimumRunTokens());
    }

    @Test
    void reserveProductsSumsAndAbsoluteDeadlinesUseCheckedArithmetic() {
        var config = new AgentReviewLimits.Configuration(12, 18, Long.MAX_VALUE, null, null, 24_000);
        for (long window : new long[]{Long.MAX_VALUE, Long.MAX_VALUE / 4}) {
            assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(config,
                    new AgentReviewLimits.Integration(BINDING, 4_096, window, "provider-default"), total(window),
                    new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
        }
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(config,
                new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "provider-default"), total(200_000),
                new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, Instant.MAX, Instant.MAX,
                AgentReviewLimits.Timeouts.defaults()));
    }

    @Test
    void theVisibleReviewAndCompleteMarkerMustFitTheRemoteLimitWithoutTruncation() {
        var config = AgentReviewLimits.Configuration.defaults();
        var integration = new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "provider-default");
        var limits = AgentReviewLimits.resolve(config, integration, total(200_000),
                new AgentReviewLimits.RepositoryLimits(25_000, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults());
        assertEquals(24_000, limits.getConfigured().maxRenderedReviewChars());
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(config, integration, total(200_000),
                new AgentReviewLimits.RepositoryLimits(24_999, 1_000), null, null, NOW, NOW, AgentReviewLimits.Timeouts.defaults()));
        assertThrows(InvalidConfigurationException.class, () -> new AgentReviewLimits.RepositoryLimits(1_000, -1));
        assertThrows(InvalidConfigurationException.class, () -> new AgentReviewLimits.RepositoryLimits(1_000, 1_000));
    }

    @Test
    void snapshotsHaveValueEqualityAndRemainUnchangedWhenTheNextConfigurationDiffers() {
        var before = resolve(AgentReviewLimits.Configuration.defaults(), total(200_000), null, null);
        var next = resolve(new AgentReviewLimits.Configuration(1, 5, 1_000_000, 1_024L, 2_048L, 12_000),
                total(200_000), null, null);
        assertEquals(before, resolve(AgentReviewLimits.Configuration.defaults(), total(200_000), null, null));
        assertEquals(4_096, before.getFinalizationMaxCompletionTokens());
        assertEquals(2_048, next.getFinalizationMaxCompletionTokens());
        assertEquals(24_000, before.getConfigured().maxRenderedReviewChars());
    }

    @Test
    void smallerDeploymentTimeoutsAreFrozenWithoutExtendingTheFixedRunDeadline() {
        var limits = AgentReviewLimits.resolve(AgentReviewLimits.Configuration.defaults(),
                new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "provider-default"), total(200_000),
                new AgentReviewLimits.RepositoryLimits(65_536, 1_000), null, null, NOW, NOW,
                new AgentReviewLimits.Timeouts(Duration.ofSeconds(30), Duration.ofSeconds(60)));
        assertEquals(Duration.ofSeconds(240), limits.getReservedTerminalDuration());
        assertEquals(Instant.parse("2026-10-18T13:00:00Z"), limits.getRunDeadlineAt());
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Timeouts(Duration.ofSeconds(121), Duration.ofSeconds(300)));
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Timeouts(Duration.ZERO, Duration.ofSeconds(300)));
        assertThrows(InvalidConfigurationException.class,
                () -> new AgentReviewLimits.Timeouts(Duration.ofSeconds(120), Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void evidenceVerifiedAfterRunStartIsValidAtAdmissionWithoutExtendingTheDeadline() {
        var admissionAt = Instant.parse("2026-10-18T12:00:02Z");
        var evidence = new Evidence(ProofSource.ADAPTER_GUARANTEE, "after-start-contract",
                Instant.parse("2026-10-18T12:00:01Z"), "v1", "One generation; no hidden fallbacks");
        var contract = new Qualified(1, BINDING, evidence, InputPreservation.COMPLETE_OR_REJECT,
                new PreparedTokens(200_000, new GenerationLimit(4_096, true,
                        GenerationAccounting.ALL_GENERATED_TOKENS)), Set.of("provider-default"));
        var input = new InputProof(BINDING, Counting.PHASE_UPPER_BOUND, "after-start-envelope", 1_000, evidence);

        var limits = AgentReviewLimits.resolve(AgentReviewLimits.Configuration.defaults(),
                new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "provider-default"), contract,
                new AgentReviewLimits.RepositoryLimits(65_536, 1_000), input, input, NOW, admissionAt,
                AgentReviewLimits.Timeouts.defaults());

        assertEquals(Instant.parse("2026-10-18T12:00:00Z"), limits.getRunStartedAt());
        assertEquals(Instant.parse("2026-10-18T12:00:02Z"), limits.getAdmissionAt());
        assertEquals(Instant.parse("2026-10-18T13:00:00Z"), limits.getRunDeadlineAt());
    }

    @Test
    void evidenceFromAfterAdmissionIsRejectedForTheContractAndEitherInputPhase() {
        var admissionAt = Instant.parse("2026-10-18T12:00:02Z");
        var future = new Evidence(ProofSource.ADAPTER_GUARANTEE, "future-contract",
                Instant.parse("2026-10-18T12:00:03Z"), "v1", "One generation; no hidden fallbacks");
        var futureContract = new Qualified(1, BINDING, future, InputPreservation.COMPLETE_OR_REJECT,
                total(200_000).bound(), Set.of("provider-default"));
        var integration = new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "provider-default");
        var repository = new AgentReviewLimits.RepositoryLimits(65_536, 1_000);
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(), integration, futureContract, repository,
                null, null, NOW, admissionAt, AgentReviewLimits.Timeouts.defaults()));
        var futureInput = new InputProof(BINDING, Counting.PHASE_UPPER_BOUND, "future-envelope", 1_000, future);
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(), integration, total(200_000), repository,
                futureInput, envelope(1_000), NOW, admissionAt, AgentReviewLimits.Timeouts.defaults()));
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(), integration, total(200_000), repository,
                envelope(1_000), futureInput, NOW, admissionAt, AgentReviewLimits.Timeouts.defaults()));
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(), integration, total(200_000), repository,
                null, null, NOW, null, AgentReviewLimits.Timeouts.defaults()));
    }

    @Test
    void admissionCannotPrecedeRunStartButMayEqualIt() {
        var runStartedAt = Instant.parse("2026-10-18T12:00:02Z");
        var integration = new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "provider-default");
        var repository = new AgentReviewLimits.RepositoryLimits(65_536, 1_000);
        assertThrows(InvalidConfigurationException.class, () -> AgentReviewLimits.resolve(
                AgentReviewLimits.Configuration.defaults(), integration, total(200_000), repository,
                null, null, runStartedAt, Instant.parse("2026-10-18T12:00:01Z"), AgentReviewLimits.Timeouts.defaults()));

        var limits = AgentReviewLimits.resolve(AgentReviewLimits.Configuration.defaults(), integration,
                total(200_000), repository, null, null, runStartedAt, runStartedAt, AgentReviewLimits.Timeouts.defaults());
        assertEquals(Instant.parse("2026-10-18T13:00:02Z"), limits.getRunDeadlineAt());
    }

    private static InputProof envelope(long tokens) {
        return new InputProof(BINDING, Counting.PHASE_UPPER_BOUND, "all-terminal-inputs-v1", tokens, EVIDENCE);
    }

    private static AgentReviewLimits resolve(AgentReviewLimits.Configuration config,
                                             ReviewProviderContract contract,
                                             InputProof exploration, InputProof terminal) {
        return AgentReviewLimits.resolve(config,
                new AgentReviewLimits.Integration(BINDING, 4_096, 200_000, "provider-default"),
                contract, new AgentReviewLimits.RepositoryLimits(65_536, 1_000),
                exploration, terminal, NOW, NOW, AgentReviewLimits.Timeouts.defaults());
    }

    private static Qualified total(long window) {
        return new Qualified(1, BINDING, EVIDENCE, InputPreservation.COMPLETE_OR_REJECT,
                new TotalTokens(window, new GenerationLimit(4_096, true,
                        GenerationAccounting.ALL_GENERATED_TOKENS)), Set.of("provider-default"));
    }
}
