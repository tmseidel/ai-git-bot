package org.remus.giteabot.prworkflow.agentreview;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.remus.giteabot.ai.ReviewProviderContract;
import org.remus.giteabot.ai.ReviewProviderContract.Binding;
import org.remus.giteabot.ai.ReviewProviderContract.Counting;
import org.remus.giteabot.ai.ReviewProviderContract.InputProof;
import org.remus.giteabot.ai.ReviewProviderContract.InvalidConfigurationException;
import org.remus.giteabot.ai.ReviewProviderContract.PreparedTokens;
import org.remus.giteabot.ai.ReviewProviderContract.Qualified;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/**
 * Frozen, side-effect-free review preflight. No entities or live workflow parameters are retained.
 * Resolve once before provider, tool, workspace or publication effects; this does not activate a run.
 */
@Getter
@EqualsAndHashCode
public final class AgentReviewLimits {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 12;
    public static final long DEFAULT_MAX_PROVIDER_CALLS = 18;
    public static final long DEFAULT_MAX_RUN_TOKENS = 2_000_000;
    public static final long DEFAULT_MAX_RENDERED_REVIEW_CHARS = 24_000;

    private static final int MAX_FINALIZATION_ATTEMPTS = 2;
    private static final int MAX_TRANSPORT_RETRIES_PER_TERMINAL_ATTEMPT = 1;
    private static final int MAX_REVIEW_MEMORY_CHARS = 24_000;
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(60);
    private static final Duration MAX_PROVIDER_ATTEMPT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    /** Six review-owned values; null phase limits explicitly inherit the integration cap. */
    public record Configuration(int maxToolRounds, long maxProviderCalls, long maxRunTokens,
                                Long explorationMaxCompletionTokens, Long finalizationMaxCompletionTokens,
                                long maxRenderedReviewChars) {
        public Configuration {
            require(maxToolRounds >= 1 && maxToolRounds <= 30, "maxToolRounds must be between 1 and 30");
            require(maxProviderCalls >= terminalCalls(), "maxProviderCalls cannot reserve terminal calls");
            require(maxRunTokens > 0, "maxRunTokens must be positive");
            require(explorationMaxCompletionTokens == null || explorationMaxCompletionTokens > 0,
                    "exploration completion must be positive or inherited");
            require(finalizationMaxCompletionTokens == null || finalizationMaxCompletionTokens > 0,
                    "finalization completion must be positive or inherited");
            require(maxRenderedReviewChars > 0, "maxRenderedReviewChars must be positive");
        }

        /** Same defaults for missing legacy selections and newly saved configurations. */
        public static Configuration defaults() {
            return new Configuration(DEFAULT_MAX_TOOL_ROUNDS, DEFAULT_MAX_PROVIDER_CALLS, DEFAULT_MAX_RUN_TOKENS,
                    null, null, DEFAULT_MAX_RENDERED_REVIEW_CHARS);
        }
    }

    /** Concrete integration/policy snapshot; never store plaintext credentials in its binding. */
    public record Integration(Binding binding, long maxTokens, long contextWindowTokens, String reasoningEffort) {
        public Integration {
            require(binding != null, "integration binding is required");
            require(maxTokens > 0 && maxTokens < contextWindowTokens, "integration completion must fit context");
            require(reasoningEffort != null && !reasoningEffort.isBlank(), "reasoning effort is required");
        }
    }

    /** Adapter-owned remote character limit and the complete publication-marker/separator reserve. */
    public record RepositoryLimits(long maxReviewBodyChars, long markerReserve) {
        public RepositoryLimits {
            require(maxReviewBodyChars > 0 && markerReserve >= 0 && markerReserve < maxReviewBodyChars,
                    "repository body/marker limits are invalid");
        }
    }

    /** Absolute per-attempt durations, including all transport/parse/cleanup work, not read timeouts. */
    public record Timeouts(Duration providerAttemptTimeout, Duration toolAttemptTimeout) {
        public Timeouts {
            require(providerAttemptTimeout != null && providerAttemptTimeout.isPositive()
                            && providerAttemptTimeout.compareTo(MAX_PROVIDER_ATTEMPT_TIMEOUT) <= 0,
                    "provider timeout must be positive and at most 120 seconds");
            require(toolAttemptTimeout != null && toolAttemptTimeout.isPositive()
                            && toolAttemptTimeout.compareTo(MAX_RUN_DURATION) <= 0,
                    "tool timeout must be positive and fit the run duration");
        }

        /** The initial protocol defaults; pass a smaller existing deployment timeout explicitly. */
        public static Timeouts defaults() {
            return new Timeouts(MAX_PROVIDER_ATTEMPT_TIMEOUT, Duration.ofSeconds(300));
        }
    }

    private final Configuration configured;
    private final Integration integration;
    private final Qualified providerContract;
    private final RepositoryLimits repositoryLimits;
    private final InputProof explorationInput;
    private final InputProof terminalInput;
    private final Instant runStartedAt;
    private final Instant admissionAt;
    private final Timeouts timeouts;
    private final long explorationMaxCompletionTokens;
    private final long finalizationMaxCompletionTokens;
    private final long explorationAttemptReservation;
    private final long terminalAttemptReservation;
    private final long reservedTerminalCalls;
    private final long reservedTerminalTokens;
    private final long minimumRunTokens;
    private final Instant runDeadlineAt;
    private final Duration reservedTerminalDuration;

    /**
     * The single resolution seam. The supplied start instant must be the run's database start time;
     * admissionAt must come from a trusted server clock, never client/provider proof data.
     * U-based terminal evidence must cover every finalization/repair/retry request, not one prompt.
     * Throws invalid_configuration before effects; values are rejected, never silently clamped.
     */
    public static AgentReviewLimits resolve(Configuration configured, Integration integration,
                                             ReviewProviderContract providerContract, RepositoryLimits repositoryLimits,
                                             InputProof explorationInput, InputProof terminalInput,
                                             Instant runStartedAt, Instant admissionAt, Timeouts timeouts) {
        require(configured != null && integration != null && repositoryLimits != null
                && runStartedAt != null && admissionAt != null && timeouts != null, "incomplete review configuration");
        require(!admissionAt.isBefore(runStartedAt), "admission cannot precede run start");
        require(providerContract instanceof Qualified, "provider admission contract is unsupported");
        try {
            return new AgentReviewLimits(configured, integration, (Qualified) providerContract, repositoryLimits,
                    explorationInput, terminalInput, runStartedAt, admissionAt, timeouts);
        } catch (ArithmeticException | DateTimeException e) {
            throw new InvalidConfigurationException("review reservation or deadline overflow");
        }
    }

    private AgentReviewLimits(Configuration configured, Integration integration, Qualified providerContract,
                              RepositoryLimits repositoryLimits, InputProof explorationInput, InputProof terminalInput,
                              Instant runStartedAt, Instant admissionAt, Timeouts timeouts) {
        this.configured = configured;
        this.integration = integration;
        this.providerContract = providerContract;
        this.repositoryLimits = repositoryLimits;
        this.explorationInput = explorationInput;
        this.terminalInput = terminalInput;
        this.runStartedAt = runStartedAt;
        this.admissionAt = admissionAt;
        this.timeouts = timeouts;

        require(integration.binding().equals(providerContract.binding()), "provider proof binding changed");
        require(providerContract.reasoningEfforts().contains(integration.reasoningEffort()), "unproven reasoning effort");
        require(integration.maxTokens() <= providerContract.bound().generationLimit().maxTokens(),
                "integration completion exceeds the proven cap");
        require(integration.contextWindowTokens() <= providerContract.bound().contextWindowTokens(),
                "integration context exceeds the proven window");
        explorationMaxCompletionTokens = completion(configured.explorationMaxCompletionTokens(), integration.maxTokens());
        finalizationMaxCompletionTokens = completion(configured.finalizationMaxCompletionTokens(), integration.maxTokens());
        var generation = providerContract.bound().generationLimit();
        require(generation.requestLimitEnforced() || (explorationMaxCompletionTokens == generation.maxTokens()
                        && finalizationMaxCompletionTokens == generation.maxTokens()),
                "phase completion limits are not enforced by the provider proof");

        if (providerContract.bound() instanceof PreparedTokens
                || integration.contextWindowTokens() < providerContract.bound().contextWindowTokens()) {
            require(explorationInput != null && terminalInput != null,
                    "request proof is required for prepared-input accounting or a smaller local context");
        }
        if (terminalInput != null) {
            require(terminalInput.counting() == Counting.PHASE_UPPER_BOUND,
                    "terminal input proof must cover the whole terminal phase");
        }
        validateLocalFit(explorationMaxCompletionTokens, explorationInput);
        validateLocalFit(finalizationMaxCompletionTokens, terminalInput);
        explorationAttemptReservation = providerContract.attemptReservation(
                explorationMaxCompletionTokens, explorationInput, admissionAt);
        terminalAttemptReservation = providerContract.attemptReservation(
                finalizationMaxCompletionTokens, terminalInput, admissionAt);
        reservedTerminalCalls = terminalCalls();
        require(configured.maxProviderCalls() >= Math.addExact(reservedTerminalCalls, 1),
                "maxProviderCalls must also allow one exploration attempt");
        reservedTerminalTokens = Math.multiplyExact(reservedTerminalCalls, terminalAttemptReservation);
        minimumRunTokens = Math.addExact(reservedTerminalTokens, explorationAttemptReservation);
        require(configured.maxRunTokens() >= minimumRunTokens, "maxRunTokens cannot preserve the terminal reserve");
        require(configured.maxRenderedReviewChars() <= Math.subtractExact(
                repositoryLimits.maxReviewBodyChars(), repositoryLimits.markerReserve()),
                "rendered review plus marker exceeds the remote body limit");
        runDeadlineAt = runStartedAt.plus(MAX_RUN_DURATION);
        reservedTerminalDuration = timeouts.providerAttemptTimeout().plus(MAX_RETRY_DELAY)
                .multipliedBy(reservedTerminalCalls);
    }

    /** First terminal submit plus one schema-repair attempt. */
    public int getMaxFinalizationAttempts() { return MAX_FINALIZATION_ATTEMPTS; }

    /** A transport retry is a separate provider call, not a new terminal attempt. */
    public int getMaxTransportRetriesPerTerminalAttempt() { return MAX_TRANSPORT_RETRIES_PER_TERMINAL_ATTEMPT; }

    /** Memory has an independent invariant, never the rendered-review operator value. */
    public int getMaxReviewMemoryChars() { return MAX_REVIEW_MEMORY_CHARS; }

    /** Longer Retry-After values must fail rather than cause an early retry. */
    public Duration getMaxRetryDelay() { return MAX_RETRY_DELAY; }

    private void validateLocalFit(long completion, InputProof input) {
        if (input != null) {
            var generation = providerContract.bound().generationLimit();
            long g = generation.requestLimitEnforced() ? completion : generation.maxTokens();
            require(Math.addExact(input.upperBoundTokens(), g) <= integration.contextWindowTokens(),
                    "proven request exceeds the selected context window");
        }
    }

    private static long completion(Long configured, long integrationCap) {
        long effective = configured == null ? integrationCap : configured;
        require(effective > 0 && effective <= integrationCap, "phase completion exceeds the integration cap");
        return effective;
    }

    private static long terminalCalls() {
        return Math.multiplyExact((long) MAX_FINALIZATION_ATTEMPTS,
                Math.addExact(1L, MAX_TRANSPORT_RETRIES_PER_TERMINAL_ATTEMPT));
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new InvalidConfigurationException(detail);
        }
    }
}
