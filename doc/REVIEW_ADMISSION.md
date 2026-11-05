# Review admission core (RR08)

This is a migrations-free prerequisite, **not runtime activation**. All six shipped
provider adapters and their native clients still return `unsupported()`. Legacy
chat, retries, request DTOs, factories, workflows and forms are unchanged.

## Public seams

- `AiProviderMetadata.reviewProviderContract(AiIntegration)` and
  `AiClient.reviewProviderContract()` default to unsupported, without I/O.
  `RetryAiClient` and `AuditingAiClient` return their delegate's same immutable
  contract. A future qualified native adapter must expose the same evidence as
  its metadata; the existing factory decorator chain preserves it.
- `ReviewProviderContract.Qualified.attemptReservation(long requestedCompletionTokens,
  InputProof input, Instant at)` calculates one conservative reservation.
- `AgentReviewLimits.resolve(Configuration, Integration, ReviewProviderContract,
  RepositoryLimits, InputProof explorationInput, InputProof terminalInput,
  Instant runStartedAt, Instant admissionAt, Timeouts)` is the single preflight/resolution entrypoint.
  Its immutable result exposes the original inputs and effective values through
  getters. It retains no entities, mutable parameter maps, credentials or clients.

Invalid or unsupported configuration throws
`ReviewProviderContract.InvalidConfigurationException` (`invalid_configuration`).
The future run owner must call preflight before workspace/tool/provider/publish
effects and map this failure to the run outcome. No existing path calls it yet.

## Evidence and token bounds

Version 1 requires a complete `Binding`: integration identity, concrete canonical
model, provider, deployment, effective request-policy revision and non-secret key
revision. The deployment must identify the actual endpoint/region/API/transport
version; policy revisions must cover routing, fallback, transforms, generation
count, tools and effective reasoning settings. Changed binding invalidates proof.
Never use a credential itself as a revision or infer a concrete model from an
unresolved router/alias name.

Each textual binding field (provider, model, deployment, policy revision and key
revision) must be nonblank and at most `MAX_BINDING_FIELD_CHARS=1_024` Java
characters (UTF-16 code units). This fixed metadata cap is checked at construction;
oversized identities are rejected, never truncated. Key revisions are opaque
non-secret identifiers, not key material.

`Evidence` names its reviewed source, time, source version and explicit provider
assumptions. Future-dated evidence, unknown sources/versions, incomplete input
preservation and visible-only/unknown generation accounting cannot enable
admission. Proof constructors validate their enabling invariants. These are
adapter-owned values, **not client-submitted proof DTOs**: a catalog entry alone
does not prove transport behavior. Adapters remain unsupported until evidence
actually establishes the complete contract, including one generation and disabled
or accounted extra generations/fallbacks. No inference sample, UI limit,
`chars / 4` estimate or unverified native counter is proof. No counting HTTP calls
are introduced.

Complete input preservation (`COMPLETE_OR_REJECT`) is separate from accounting:
all system/history/tool/schema/provider-framing input must arrive unchanged or be
rejected, never silently trimmed. Reservations use checked `long` arithmetic:

- `TotalTokens`: enforced total **C**. A smaller completion never reduces C.
- `SeparateTokens`: enforced input **I** plus combined generation **G**.
- `PreparedTokens`: proven input **U** plus combined generation **G**.

G includes reasoning, tool-call output and visible output together. A proven
enforced request cap can lower G; otherwise the fixed proven generation bound is
reserved even for a smaller requested completion. U is an exact count or proven
upper bound for the complete prepared request, or a reviewed phase-wide envelope.
Preflight rejects phase/integration caps smaller than a fixed G when no enforced
request cap proves them; conservative accounting alone cannot enforce that setting.
Prepared evidence identifies the serialized request by SHA-256. An envelope
identifies its reviewed scope/version; terminal envelopes must cover initial
finalization, repair and all their transport attempts. A count for a single
terminal prompt cannot reserve the whole terminal phase.

A smaller operator-selected context needs actual input evidence for both phases;
it never lowers an enforced C or I bound. Proven input plus G must fit both the
selected and proven context. Otherwise the complete input is accepted/rejected
by the provider, not locally estimated or shortened.

This is an **application admission budget under documented assumptions**, not an
all-outcomes external billing guarantee. Missing all-outcomes billing guarantees
alone do not disqualify an otherwise proven adapter.

## Frozen limits

`Configuration` has the six review-owned fields. Public shared defaults are
`DEFAULT_MAX_TOOL_ROUNDS=12`, `DEFAULT_MAX_PROVIDER_CALLS=18`,
`DEFAULT_MAX_RUN_TOKENS=2_000_000`, and
`DEFAULT_MAX_RENDERED_REVIEW_CHARS=24_000`. Both nullable phase limits inherit
`Integration.maxTokens`; future form parsing must normalize absent/blank values
to null, not a numeric default. Invalid values are rejected, never clamped.

Preflight validates 1–30 exploration rounds, positive integration completion below
context, phase limits within the integration cap, proof binding/reasoning, remote
body-minus-marker limits, and enough calls/tokens for at least one exploration.
The two terminal attempts each retain one transport retry: **four calls**, with
four complete terminal token reservations. Memory remains independently fixed at
24,000 characters. The renderer/adapter must still reject an oversized actual
body, including the complete marker; this module does not truncate or publish.

The caller supplies the database run-start instant and a separate `admissionAt`
from a trusted server clock, never from client/provider proof data. Contract and
input evidence are checked against admission time: evidence verified after run
start but no later than admission is valid; genuinely future evidence is rejected.
The deadline remains fixed at run start + 60 minutes, never admission + 60 minutes.
`Timeouts.defaults()` freezes 120-second provider attempts
and 300-second tool attempts; pass existing smaller deployment timeouts explicitly.
Retry waits are capped at 30 seconds; initial terminal time reserve is four times
(provider timeout + 30 seconds). These values describe absolute total attempt
durations, not socket timeouts. Live cancellation/lease/control checks remain live.

## Deliberately not implemented here

RR09 owns run-local attempt admission, proof-to-prepared-bytes matching immediately
before HTTP, actual call/retry/redirect counts, terminal envelope rechecks as
context changes, settlement/refunds, contract-violation handling and deadline
enforcement. Reservations cannot be refunded from estimates or missing usage.
The six dynamic workflow fields/range plumbing, provider qualification, reasoning
migration, integration-cap guards, protected context, rendering and runtime loop
integration are separate work. No shared client receives mutable run budget state.

Focused public tests cover worked C/I+G/U+G examples, overflow, reserves,
inheritance/no clamping, proof rejection/binding, immutable snapshots, remote
marker limits, decorator propagation and every shipped adapter staying unsupported.
