package org.remus.giteabot.ai;

import java.time.Instant;
import java.util.Set;

/** Immutable, versioned evidence for review admission, not an external billing guarantee. */
public sealed interface ReviewProviderContract
        permits ReviewProviderContract.Unsupported, ReviewProviderContract.Qualified {

    int VERSION = 1;

    /** Maximum Java characters (UTF-16 code units) in each textual binding identity or revision. */
    int MAX_BINDING_FIELD_CHARS = 1_024;

    /** Format version of the immutable contract. Unknown versions cannot qualify. */
    int version();

    /** Default for transports without a reviewed admission contract. */
    static ReviewProviderContract unsupported() {
        return Unsupported.INSTANCE;
    }

    /** No request, accounting or context-preservation capability is inferred. */
    enum Unsupported implements ReviewProviderContract {
        INSTANCE;

        @Override public int version() { return VERSION; }
    }

    /** Non-secret identity; revisions must change when credentials or effective request policy change. */
    record Binding(long integrationId, String provider, String model, String deployment,
                   String policyRevision, String keyRevision) {
        public Binding {
            require(integrationId > 0, "integration identity is required");
            requireBindingField(provider, "provider");
            requireBindingField(model, "concrete model");
            requireBindingField(deployment, "deployment");
            requireBindingField(policyRevision, "policy revision");
            requireBindingField(keyRevision, "key revision");
        }
    }

    /** Reviewed sources, never operator-entered token limits or a finite inference sample. */
    enum ProofSource { ADAPTER_GUARANTEE, PROVIDER_DOCUMENTATION, MODEL_CATALOG, UNKNOWN }

    /** Provenance of the bound and its explicit deployment/accounting assumptions. */
    record Evidence(ProofSource source, String reference, Instant verifiedAt, String version,
                    String assumptions) {
        public Evidence {
            require(source != null && source != ProofSource.UNKNOWN, "unknown proof source");
            requireText(reference, "proof reference");
            require(verifiedAt != null, "proof time is required");
            requireText(version, "proof version");
            requireText(assumptions, "provider assumptions");
        }
    }

    /** Complete input preservation is independent of how tokens are accounted for. */
    enum InputPreservation { COMPLETE_OR_REJECT, MAY_TRIM, UNKNOWN }

    /** Generation means reasoning, tool-call output and visible output together, without double counting. */
    enum GenerationAccounting { ALL_GENERATED_TOKENS, VISIBLE_OUTPUT_ONLY, UNKNOWN }

    /** A proven combined G; only an enforced request limit can reduce its reservation. */
    record GenerationLimit(long maxTokens, boolean requestLimitEnforced, GenerationAccounting accounting) {
        public GenerationLimit {
            require(maxTokens > 0, "generation limit must be positive");
            require(accounting == GenerationAccounting.ALL_GENERATED_TOKENS,
                    "generation limit must cover reasoning, tool calls and visible output");
        }
    }

    /** Enforced, normalized input/generation bounds; all tokens include cached input where applicable. */
    sealed interface Bound permits TotalTokens, SeparateTokens, PreparedTokens {
        long contextWindowTokens();
        GenerationLimit generationLimit();
    }

    /** C is fixed even when the requested completion is smaller. */
    record TotalTokens(long contextWindowTokens, GenerationLimit generationLimit) implements Bound {
        public TotalTokens {
            validateWindow(contextWindowTokens, generationLimit);
        }
    }

    /** I and G are separately enforced; their checked sum must fit the proven context window. */
    record SeparateTokens(long contextWindowTokens, long inputTokens, GenerationLimit generationLimit)
            implements Bound {
        public SeparateTokens {
            validateWindow(contextWindowTokens, generationLimit);
            require(inputTokens > 0, "input limit must be positive");
            require(add(inputTokens, generationLimit.maxTokens()) <= contextWindowTokens,
                    "separate limits exceed the context window");
        }
    }

    /** U is supplied separately, from the exact prepared request or a proven input envelope. */
    record PreparedTokens(long contextWindowTokens, GenerationLimit generationLimit) implements Bound {
        public PreparedTokens {
            validateWindow(contextWindowTokens, generationLimit);
        }
    }

    /** No heuristic counting mode exists. Phase envelopes must also cover repair and retry inputs. */
    enum Counting { EXACT_PREPARED_REQUEST, PREPARED_REQUEST_UPPER_BOUND, PHASE_UPPER_BOUND, UNKNOWN }

    /**
     * Adapter-owned U evidence. Prepared scopes are the SHA-256 of the complete serialized request,
     * including tools, system/history and provider framing. Phase scopes identify a reviewed envelope.
     * The transport must match the proof to the unchanged prepared bytes before sending (RR09).
     */
    record InputProof(Binding binding, Counting counting, String scope, long upperBoundTokens, Evidence evidence) {
        public InputProof {
            require(binding != null && evidence != null, "incomplete input proof");
            require(counting != null && counting != Counting.UNKNOWN, "unknown input counting capability");
            require(upperBoundTokens >= 0, "input bound must not be negative");
            requireText(scope, "input proof scope");
            if (counting != Counting.PHASE_UPPER_BOUND) {
                require(scope.matches("[0-9a-f]{64}"), "prepared input proof requires a request SHA-256");
            }
        }
    }

    /**
     * Adapter-owned evidence, not a UI payload. Construction validates every enabling invariant.
     * A future adapter must prove one generation and disabled/accounted fallbacks in the cited evidence.
     */
    record Qualified(int version, Binding binding, Evidence evidence, InputPreservation inputPreservation,
                     Bound bound, Set<String> reasoningEfforts) implements ReviewProviderContract {
        public Qualified {
            require(version == VERSION, "unknown provider contract version");
            require(binding != null && evidence != null && bound != null, "incomplete provider proof");
            require(inputPreservation == InputPreservation.COMPLETE_OR_REJECT, "input preservation is not proven");
            require(reasoningEfforts != null && !reasoningEfforts.isEmpty(), "reasoning capabilities are required");
            for (String effort : reasoningEfforts) {
                requireText(effort, "reasoning effort");
            }
            reasoningEfforts = Set.copyOf(reasoningEfforts);
        }

        /** Conservative local reservation; never an all-outcomes external billing cap. */
        public long attemptReservation(long requestedCompletionTokens, InputProof input, Instant at) {
            require(at != null && !evidence.verifiedAt().isAfter(at), "proof is not valid at admission time");
            GenerationLimit generation = bound.generationLimit();
            require(requestedCompletionTokens > 0 && requestedCompletionTokens <= generation.maxTokens(),
                    "requested completion exceeds the proven limit");
            long g = generation.requestLimitEnforced() ? requestedCompletionTokens : generation.maxTokens();
            if (input != null) {
                require(binding.equals(input.binding()), "input proof binding changed");
                require(!input.evidence().verifiedAt().isAfter(at), "input proof is not valid at admission time");
                require(add(input.upperBoundTokens(), g) <= bound.contextWindowTokens(), "proven input does not fit");
                if (bound instanceof SeparateTokens separate) {
                    require(input.upperBoundTokens() <= separate.inputTokens(), "input exceeds the enforced input cap");
                }
            }
            return switch (bound) {
                case TotalTokens total -> total.contextWindowTokens();
                case SeparateTokens separate -> add(separate.inputTokens(), g);
                case PreparedTokens ignored -> {
                    require(input != null, "prepared input proof is required");
                    yield add(input.upperBoundTokens(), g);
                }
            };
        }
    }

    /** Fail-closed preflight failure; callers map it to FAILED(invalid_configuration) before effects. */
    final class InvalidConfigurationException extends IllegalArgumentException {
        public InvalidConfigurationException(String detail) {
            super("invalid_configuration: " + detail);
        }
    }

    private static void validateWindow(long window, GenerationLimit generation) {
        require(generation != null && window > generation.maxTokens(), "generation must fit a positive context window");
    }

    private static long add(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException e) {
            throw new InvalidConfigurationException("token sum overflow");
        }
    }

    private static void requireBindingField(String value, String name) {
        requireText(value, name);
        require(value.length() <= MAX_BINDING_FIELD_CHARS, name + " exceeds the binding field limit");
    }

    private static void requireText(String value, String name) {
        require(value != null && !value.isBlank(), name + " is required");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new InvalidConfigurationException(detail);
        }
    }
}
