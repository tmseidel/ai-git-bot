# Review memory contract (schema 1)

`prworkflow.agentreview.memory` is an inactive domain module. No tools, persistence,
provider calls, or publication are activated. It implements RR01 of the approved
agentic-review reliability plan (§§6.1–6.5) for [#393](https://github.com/tmseidel/ai-git-bot/issues/393).

## Ownership

The application supplies purpose, exact `RevisionKey`, checkpointed inspected
paths, clarification question, validated lineage IDs, committed memory version,
completion cause, and database sealing time. Model inputs contain none of these.
Finding inputs may reference an **existingId** from the current draft; new
findings omit it. The module assigns fresh UUIDs. Removed IDs cannot be referenced
again. Every omitted existing finding requires exactly one explicit removal with
a reason, including a complete terminal replacement.

The future store owns CAS, fences, version allocation/increment, revision recapture,
lineage lookup/cycle checks, and transactions. Domain replacement does not write
anything or maintain a second counter. An insufficient-context submit retains the
last valid content exactly (or an empty purpose-specific content without a draft).
Clarification replacements never accept a question or review findings.

## Canonical encoding and readers

- Only schema **1** is readable/writable. Unknown or corrupt versions fail closed.
  Future versions must add an explicit reader/upcaster while retaining every
  previously persisted reader; no best-effort fallback or silent field dropping.
- UTF-8 without BOM, no whitespace or final newline. Root order is `schemaVersion`,
  `memoryVersion`, `purpose`, `status`, `completionCause`, `headSha`, `baseSha`,
  `diffHash`, `sealedAt`, `baseReviewRunId`, `parentMemoryRunId`, `inspectedPaths`,
  `review`, `clarification`, `insufficientReason`. Nested review order is `summary`, `openQuestions`,
  `findings`; finding order is `id`, `severity`, `category`, `title`, `description`,
  `recommendation`, optional `file`, optional `line`, `cwe`, `owasp`;
  clarification order is `question`, `answer`.
- Nullable fields are explicit JSON null, except unknown finding file/line, which
  are omitted. All arrays retain order except inspected paths (deduplicated and
  sorted by Java's locale-independent string ordering).
- Times use `Instant.toString()` (UTC, `Z`, lossless fractional seconds). DRAFT
  has neither sealing time nor completion cause. Both are required on terminal
  snapshots and cannot subsequently be replaced.
- Unicode is preserved, not normalized; unpaired UTF-16 surrogates are rejected.
  Quotes/backslashes use `\"`/`\\`; controls use `\b`, `\t`, `\n`, `\f`, `\r` or
  `\u00XX` with uppercase hex. Slashes, HTML characters and other Unicode remain
  literal UTF-8. Character limits count UTF-16 code units, including
  the **serialized** 24,000-character total. Hashes are `sha256:` plus lowercase
  hexadecimal over exactly the returned UTF-8 bytes, never platform-default bytes.
- Persisted readers require canonical bytes, including canonical numbers and
  times. Model wire readers accept insignificant whitespace/property ordering but
  reject unknown/duplicate fields, trailing values, number coercion and invalid
  result branches. Parsing is bounded before building JSON trees; errors do not
  echo untrusted payloads. An already-parsed provider JSON tree cannot recover
  duplicate-field evidence: call the wire readers on the original JSON text.

## Bounds

Fixed limits: 50 findings, 100 inspected paths, 20 open questions, 24,000 serialized
characters; 4,000 summary, 8,000 clarification question, 16,000 answer/draft,
256 finding title, 2,000 each description/recommendation, 1,024 path, 1,000 each
open question. Additional bounded identifiers, categories and reference tags are
128 characters; removal reasons 2,000, insufficient reasons 4,000. Input payloads
are also limited to 24,000 characters. Values are never silently truncated.

Paths are lexical repository-relative slash paths, not absolute paths, drive
paths, backslash paths, empty components, `.`/`..` components or control characters.
This does not authorize filesystem access or replace exact-root/symlink checks.
Commit IDs are full lowercase 40- or 64-hex IDs; diff hashes are full lowercase
SHA-256. Baseline/parent IDs, when present, are positive application-owned run IDs.

## Public seam

- `ReviewMemory` and its nested immutable content/finding records; `RevisionKey`.
- `ReviewMemoryInputs`: purpose-specific update/submit values, result, finding
  references and explicit removals. Result branches are validated on construction.
- `ReviewMemoryCodec.encode(memory)` returns immutable `Encoded` with `json()`,
  `utf8()` (defensive copy) and `sha256()`; `decode(utf8)` loads canonical stored
  values. `validate(memory)` includes the serialized total.
- `readUpdateReview(json)` / `readSubmitReview(json)` and the corresponding
  `readUpdateClarification` / `readSubmitClarification` read model wire data.
- `ReviewMemoryOperations.updateReview(previous, metadata, version, input)` and
  `submitReview(previous, metadata, version, cause, sealedAt, input)` (or the
  corresponding `updateClarification` / `submitClarification`) return a
  fully validated replacement. `previous=null` is allowed for the first write.

All validation failures are `IllegalArgumentException`; no previous value is
mutated. A supplied version must be positive and advance an existing draft.
The store, not these operations, must enforce exactly `expected + 1` atomically
with its expected-version/fence checks. No operation can modify a sealed value.

The wire shapes are deliberately small:

```text
update review        = {review: {summary, openQuestions, findings}, removals: [{id, reason}]}
update clarification = {answerDraft}
submit review        = {result: COMPLETE, review, removals}
                     | {result: INSUFFICIENT_CONTEXT, insufficientReason}
submit clarification = {result: COMPLETE, answer}
                     | {result: INSUFFICIENT_CONTEXT, insufficientReason}
finding input        = {existingId?, severity, category, title, description, recommendation, file?, line?, cwe?, owasp?}
```

Arrays and required text properties cannot be omitted or coerced. Optional finding
properties accept null; unknown location is omitted on storage. An insufficient
wire submit must not contain a content/removal property, even if null or empty.
