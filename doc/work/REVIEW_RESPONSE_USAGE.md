# Review response usage evidence (RR-I10A)

OpenRouter and OpenAI accept only non-negative JSON integers in optional usage
counters, using the shared `UsageCounterDeserializer` without mapper-wide changes.
Missing, null, malformed and overflowing values are unavailable, not reported zero.
An explicit integer zero remains reported independently for input and output.

- OpenRouter retains native `Long` prompt/completion/cache ranges and opaque
  in-memory reasoning. It uses `ChatTurn.withReportedUsage`, not the legacy
  positive-only constructor.
- OpenAI retains `Integer` prompt/completion/total and `Long` cache ranges.
  **Public DTO compatibility:** `Usage.totalTokens` and its Lombok accessors change
  from primitive `int` to nullable `Integer`; no repository callers use them.
- Non-object usage/detail containers become unavailable locally. Valid text, tools
  and companion counters survive. Invalid JSON or malformed tool structures still
  fail; these container guards do not catch response or tool-parser exceptions.
- Existing audit zero placeholders and raw-payload opt-in remain. The opt-in payload
  serializes the validated DTO: invalid counters now appear as null, not their
  original malformed wire values. No additional raw logging is introduced.

## Verification

Direct `AgentJackson.mapper()` tests cover missing, null, zero, positive, negative,
fractional, boolean, string, object, array and overflow values independently for
each counter, plus malformed containers. OpenRouter's `toTurn()` is tested directly.
Small mock-HTTP samples cover `chatWithTools` (native and text-only) and `chat`,
preserving text, tools, companion usage and null-versus-zero opt-in audit evidence.
OpenRouter error redaction and transient reasoning remain covered; malformed JSON
and tools still fail. The mock server verifies one request without a second counter.

Run the focused suite with `mvn -o '-Dtest=OpenAi*Test,OpenRouter*Test' test`.
Full-suite, PostgreSQL and live-provider runs belong to the integrating session.

## Not accounting qualification

All real provider accounting contracts remain unsupported. Presence does not
prove billing completeness, refund eligibility or attempt settlement; no budget or
HTTP admission gate is activated here. RR09 owns settlement integration. RR10B
still owns native finish/provenance/reasoning/model/cost qualification and the
response guard, including OpenAI's pre-existing malformed JSON tool-argument
fallback and incomplete response-error handling.

**Existing overflow risk:** individually valid native OpenRouter `Long` counters
can sum beyond `Long.MAX_VALUE` in `ChatTurn.totalTokens()` (for example,
`9223372036854775807 + 3`). Their range is preserved here; no second budget
calculator or settlement claim is added.
