# LLM Streaming Contract (M12)

This document defines the provider-neutral language-model request, stream, and
error contract introduced by M02 and refined by M12. It is the seam every
external provider adapter (M14–M19) and on-device LLM runtime adapter (M20)
implements, and the only surface orchestration (M21) and the conversation UI
(M06) consume. Provider endpoints, authentication, and the per-provider
capability matrix live in
[decisions.md §4](./decisions.md#4-provider-capability-matrix) and
[llm-providers.md](./llm-providers.md); this document is about the app-facing
types.

## Why a contract at all

The planned providers are not interchangeable. They differ in streaming event
shapes, usage reporting, reasoning controls, and error semantics, and one of
them (Hermes) is an agent runtime rather than a pure proxy. Two rules follow and
are enforced by the types below, not by convention:

1. **No vendor type in the public contract.** The contract is pure Kotlin
   (no `android.*`, no HTTP/JSON client, no provider SDK). `DomainPurityTest` and
   `LlmContractPurityTest` fail the build if one leaks in. A provider SDK stays
   inside its adapter; the adapter translates to these types at the boundary.
2. **No assumed capability parity.** An adapter *declares* what it supports
   (`LlmCapabilities`) and a request is *checked* against that declaration
   (`LlmRequestValidator`). A capability is never inferred from another
   provider's behavior or from the request format looking similar.

## Files

| File | Contents |
| --- | --- |
| `contracts/LanguageModel.kt` | `LanguageModel`, `LlmRequest`, `LlmMessage`, `LlmRole`, `LlmUsage`, `LlmCapabilities`, `LlmRequestValidator`, `LlmFailureReason`, `LlmStreamEvent`, ordering rules, `LlmStreamEventEnvelope`, `LlmStreamValidator` |
| `contracts/LlmStreamConsumer.kt` | `LanguageModel.consume` (the reference consumer), `LlmStreamResult`, `TurnStreamTrace` |
| `domain/Errors.kt` | `ErrorCode`/`VoiceAgentError` (M02), extended with `LLM_CANCELLED` and `LLM_INVALID_REQUEST` in M12 |
| `test/.../fake/DeterministicLanguageModel.kt` | the deterministic fake (test source set only) |
| `test/.../fake/FakeLanguageModel.kt` | the minimal verbatim-replay fake |

## Request model

```kotlin
data class LlmRequest(
    val model: ProviderModelSelection,   // provider identity + model identity, together
    val messages: List<LlmMessage>,      // the already-bounded context
    val reasoning: ReasoningLevel? = null,
)
```

- `model` keeps provider and model identity together so a request always names
  its true origin and a silent provider/model switch is visible.
- `messages` is the bounded context the app chose to send, produced by the M05
  `ModelContextBuilder`. **Bounding is not duplicated here:** this contract
  carries the window; it does not decide it. `LlmMessage` is intentionally not
  the M05 `ContextMessage`; orchestration maps one to the other at the call
  site.
- `reasoning` is a *request*, never a promise. `ReasoningLevel` is the union of
  levels verified across the planned providers. A provider that does not support
  reasoning must not be handed a level.

`LlmRequest.characterCount` exists for privacy-safe size diagnostics (a count,
never the text).

### Reasoning and capability

```kotlin
data class LlmCapabilities(
    val streaming: Boolean = true,
    val usageReporting: Boolean = false,
    val reasoningLevels: Set<ReasoningLevel> = emptySet(),
)
```

`LanguageModel.capabilities` defaults to `LlmCapabilities.UNVERIFIED` (streams,
claims nothing else), so an adapter must opt in to each claim rather than
inherit one it never verified. `LlmRequestValidator.validate(request,
capabilities)` returns `Supported` or a typed `Unsupported(error)`; a caller
uses it to refuse or hide an option instead of sending a parameter the provider
will reject or ignore. `ReasoningLevel.NONE` is always accepted, because "do not
think" is expressible by every provider.

`streaming = false` is a legal adapter: it emits one `Delta` followed by
`Completed`, never a fabricated multi-delta stream. This keeps a non-streaming
provider truthful instead of pretending to stream.

## Stream events

```kotlin
sealed interface LlmStreamEvent {
    data class Delta(val text: String) : LlmStreamEvent
    data class Completed(
        val usage: LlmUsage? = null,
        val model: ModelId? = null,               // what actually served the request
        val reasoning: ReasoningLevel? = null,
    ) : LlmStreamEvent
    data class Cancelled(
        val partialText: String,
        val reason: LlmFailureReason = LlmFailureReason.CANCELLED,
    ) : LlmStreamEvent
    data class Failed(
        val error: VoiceAgentError,
        val partialText: String,
        val reason: LlmFailureReason = LlmFailureReason.fromErrorCode(error.code),
    ) : LlmStreamEvent
}
```

### Ordering

A stream is **zero or more `Delta`s followed by exactly one terminal event**
(`Completed`, `Cancelled`, or `Failed`). Nothing may follow a terminal event.
`isLegalStreamTransition` / `LlmStreamValidator` are the mechanical form of the
rule; the deterministic fake validates its own script against them, and
`consume` drops and logs a late event rather than appending to a finished turn.

A **flow that simply ends without a terminal event is not a completion.** There
is no event that says "the text was whole" other than `Completed`, so a silent
end is surfaced as `LLM_MALFORMED_RESPONSE` and persisted as a failure. This is
deliberate: it would otherwise be impossible to distinguish a truncated response
from a complete one.

`Completed` is the only event that means the response is whole. It echoes the
provider-reported `model` and `reasoning` when the provider sends them, so a
request that reached a different model than the selection is visible
(risk R-0017) rather than silently accepted. `usage` is `null` when the provider
reported none — distinct from an empty `LlmUsage`, and never rendered as `0`.

### Partial-response state

A stream can end before the assistant text is complete, and a consumer cannot
reconstruct the interrupted text on its own. So the terminal event carries it:
`Cancelled.partialText` and `Failed.partialText` are the deltas received before
the stop, concatenated in arrival order. `LlmStreamResult.isPartial` is true when
text arrived but the response did not complete. Orchestration persists a partial
turn with generation `CANCELLED`/`FAILED` and delivery `INTERRUPTED`, and only
the delivered prefix as delivered — a cancelled response is never stored as
complete (see [conversation-ui.md](./conversation-ui.md)).

### Cancellation

Cancelling collection is the primary cancellation mechanism and is delivered as
`CancellationException`; the flow emits nothing further. `LlmStreamEvent.Cancelled`
exists for an adapter that must report a *remote* cancellation it learned from
the provider while local collection is still active, and for scripts in tests. A
well-behaved adapter does not need to emit it.

## Errors

Errors are typed, not stringly-typed, and are `LANGUAGE_MODEL`-categorised
`VoiceAgentError`s with a stable `ErrorCode`. `LlmFailureReason` makes the *kind*
of stop branchable without inspecting a provider message:

| `LlmFailureReason` | `ErrorCode` | Retryable |
| --- | --- | --- |
| `TIMEOUT` | `LLM_TIMEOUT` | yes |
| `RATE_LIMITED` | `LLM_RATE_LIMITED` | yes |
| `AUTHENTICATION` | `LLM_AUTHENTICATION_FAILED` | no |
| `NETWORK` | `LLM_NETWORK_FAILED` | yes |
| `MALFORMED_RESPONSE` | `LLM_MALFORMED_RESPONSE` | yes |
| `UNAVAILABLE` | `LLM_UNAVAILABLE` | yes |
| `NOT_CONFIGURED` | `LLM_NOT_CONFIGURED` | no |
| `INVALID_REQUEST` | `LLM_INVALID_REQUEST` | no |
| `CANCELLED` | `LLM_CANCELLED` | no |
| `OTHER` | `LLM_REQUEST_FAILED` | yes |

`fromErrorCode` maps a code back to a reason and falls back to `OTHER` — never a
fabricated specific reason. An adapter may also throw
`VoiceAgentException(VoiceAgentError(...))`; `consume` maps it to the same
`Failed` result. `ErrorCode` names are the persisted/traced contract and must not
be renamed once shipped.

`M12` added two codes to the M02 enum: `LLM_CANCELLED` (the typed code for a
cancelled stream event; cancellation is normally `CancellationException`, and
this code is not shown as an error) and `LLM_INVALID_REQUEST` (the request asked
for something the provider does not support; not retryable).

## Success, failure, and partial state

| Stream ends with | Generation | Delivery | Persisted text |
| --- | --- | --- | --- |
| `Completed` | `COMPLETED` | `COMPLETED` | all deltas |
| `Failed(error)` | `FAILED` | `FAILED` | deltas / `partialText` |
| `Cancelled` | `CANCELLED` | `INTERRUPTED` | deltas / `partialText` |
| no terminal event | `FAILED` (`LLM_MALFORMED_RESPONSE`) | `FAILED` | deltas received |
| `CancellationException` | `CANCELLED` | `INTERRUPTED` | deltas received |

No path reports success unless generation actually completed.

## The reference consumer

```kotlin
val result: LlmStreamResult = languageModel.consume(request, trace)
```

`LanguageModel.consume` folds one stream into a single `LlmStreamResult`
(`text`, `terminal`, `deltaCount`, `usage`, `model`, `reportedReasoning`). It is
the shape orchestration reuses: a consumer branches on `result.completed` and
`result.failureReason`, not on event subtypes, and never re-implements partial
text or late-event handling. `TurnStreamTrace` is an optional privacy-safe hook
(the default `NONE` records nothing); it receives the delta text so a live UI can
render it, but a tracing implementation must use only the index and length.

The M06 `ConversationViewModel` uses this consumer through a private
`UiStreamTrace` that forwards delta text to the dialog, records only counts and
stable names to the M04 `TurnTraceRecorder`, and maps the terminal event to the
persisted turn state.

## Diagnostics and logging

- Deltas are recorded on the M04 trace as index + character count only
  (`TurnTraceRecorder.llmDelta`), with time-to-first-text and inter-delta gaps.
- Completion records `REQUEST_END_REASON=completed` and, when reported,
  `USAGE=prompt=…,completion=…,total=…` (only the reported parts; a null count is
  omitted, never written as `0`).
- A non-completing end records the `LlmFailureReason` name — never the adapter's
  detail string.
- The developer log (`AppLog`) records identities (`provider`, `model`), counts
  (`messages`, `chars`, `deltas`), and state names. Prompt text, delta text,
  credentials, and provider response bodies are never logged;
  `LlmStreamLoggingTest` proves it.

## How adapters map into the contract

An adapter (M14–M20) does four things:

1. **Declare capabilities.** Return an `LlmCapabilities` matching the verified
   provider/model behavior. Use `UNVERIFIED` until the provider's current docs
   are checked; never claim reasoning or usage support you have not verified.
2. **Map the request.** Translate `LlmRequest` into the provider payload inside
   the adapter. Provider-specific fields (tool definitions, system-prompt
   placement, effort encodings) stay there. Keep provider and model identity
   explicit; never route to a different model silently.
3. **Map the stream.** Emit `Delta` per provider delta; a reasoning/thinking
   channel that is not assistant text must be excluded or surfaced separately,
   not concatenated into `text`. End with exactly one terminal event.
4. **Map errors.** Translate HTTP/vendor errors to the matching
   `LlmFailureReason`/`ErrorCode` (`401/403 → AUTHENTICATION`, `429 →
   RATE_LIMITED`, `408/504 → TIMEOUT`, `5xx → UNAVAILABLE`, unparsable body →
   `MALFORMED_RESPONSE`). Never carry a provider body, prompt, or credential into
   `VoiceAgentError.detail`. Add a fixture test per mapped status and an opt-in
   smoke test that never runs in routine CI.

`LlmAdapterMappingTest` contains a reference adapter written this way, so the
mapping is exercised (not assumed) before the real adapters exist.

## The deterministic fake

`DeterministicLanguageModel` (test source set) builds a stream from
`ScriptedLlmStep`s:

- `Emit(event, afterMillis)` — wait, then emit a checked event;
- `Stall(millis)` — wait and emit nothing, holding the stream open;
- `ThrowFailure(error, afterMillis)` — end collection by throwing.

Timing uses `kotlinx.coroutines.delay`, so it is controlled by the test
scheduler: repeated runs produce identical event sequences and virtual
timestamps, and no wall-clock time passes. There is no clock, random source,
socket, or credential anywhere in it, and it is in the test source set, so it can
never ship in the app. Emissions go through a bounded channel with
`BufferOverflow.SUSPEND`, so a slow consumer suspends the producer instead of
losing or reordering events.

`FakeLanguageModel` remains the minimal replay fake (one script, optional delay)
for tests that need none of the above.

### What the fake proves

| Concern | Test |
| --- | --- |
| event ordering | `DeterministicLanguageModelTest`, `LlmContractTest` |
| backpressure | `aSlowConsumerSuspendsTheProducerInsteadOfLosingEvents` |
| cancellation | `cancellingAMidStreamCollectionStopsEventsAndCountsTheCancellation`, `cancellingExactlyWhenTheStreamIsEmittingStopsItWithoutRacing` |
| timeout | `aTimeoutIsExpressedAsATypedFailureNotAStallForever` |
| malformed / empty stream | `anEmptyScriptEndsWithoutATerminalEventSoItIsNotACompletion`, `anEventAfterTheTerminalEventIsDroppedByTheConsumer` |
| partial-response state | `aCancelledStreamReportsPartialTextAndNeverClaimsCompletion`, `aFailedPartialResponseKeepsTheDeliveredPrefixAndTheTypedReason` |
| determinism | `theSameScriptProducesTheSameEventSequenceAndVirtualTimingOnEveryRun` |
| no assumed capability parity | `anUnsupportedRequestIsRefusedBeforeAnyStreamStarts`, `LlmAdapterMappingTest` |
| no vendor type / no content leak | `LlmContractPurityTest`, `LlmStreamLoggingTest` |

## Limitations

- A real adapter is wired into the turn path since M23 (`RegisteredProviderLanguageModelFactory`
  + `ProviderTurnResolver`); `NotConfiguredLanguageModel` remains only when
  nothing is selected and declares no capabilities. See
  [text-first-slice.md](./text-first-slice.md).
- `LlmCapabilities` is per-adapter. The per-model catalog (which reasoning levels
  a specific model exposes) arrives with M13/M22; `LlmRequestValidator` only
  enforces what an adapter has already declared.
- The contract is provider-neutral but not provider-complete: tool/function
  calling, multimodal input, and provider session reuse are out of scope until a
  milestone needs them.
- Device-level behavior (real provider latency, reasoning-channel parsing,
  cancellation acknowledgement timing) is not verified here; M14–M20 and M25
  own it.
