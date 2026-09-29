# Turn Tracing

This document describes the M04 diagnostic seam: how a conversation turn is
correlated, timed, buffered, inspected, and redacted. It complements
[architecture](./architecture.md#observability),
[voice quality and latency](./voice-quality-and-latency.md#llm-traceability),
and [privacy and security](./privacy-and-security.md).

M04 adds tracing infrastructure only. The turn orchestrator that calls it
arrives in M21; this document describes the contract those callers use.

## Scope

- A `TraceId` per conversation turn plus structured events for stage start/end,
  selected provider/model, request state, cancellation, errors, and delivered
  playback progress.
- Stage durations, LLM time-to-first-text and inter-delta gaps, TTS
  synthesis/first-audible timing, and barge-in stop/cancel timing, all from a
  monotonic clock.
- A bounded, non-blocking sink with an observable drop count; an in-memory store
  and a deterministic text export/viewer for test runs.
- Opt-in, bounded local capture of transcript/prompt/response content, off by
  default.
- Default redaction of credentials, audio, transcript, prompt, and provider
  response content.

M04 does **not** add a Compose trace screen (developer-trail surface only), LLM
adapters, or persistence.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/
  domain/Identifiers.kt          TraceId (new value class)
  contracts/Diagnostics.kt       evolved M02 seam (stages, attributes, traceId)
  diagnostics/
    MonotonicClock.kt            injectable clock + SystemMonotonicClock
    Redaction.kt                 credential/content redaction helpers
    TraceContent.kt              opt-in bounded content capture
    BoundedDiagnosticsSink.kt    non-blocking bounded sink + drop count
    InMemoryTraceStore.kt        bounded retained window for the viewer
    TraceExport.kt               deterministic text export + TraceViewer
    TurnTraceRecorder.kt         one turn's correlated events, spans, timers
    TurnTraceFactory.kt          per-turn TraceId generation
app/src/test/kotlin/com/voicechat/agent/
  fake/FakeMonotonicClock.kt     deterministic clock for tests
  fake/RecordingDiagnosticsSink.kt
  diagnostics/TurnTraceCorrelationTest.kt
  diagnostics/TraceRedactionTest.kt
  diagnostics/TraceTimingAndBufferTest.kt
  diagnostics/TraceExportAndViewerTest.kt
```

The `diagnostics` package contains **no** `android.*` or `androidx.*` imports, so
it is exercised entirely by JVM unit tests. The existing
`DomainPurityTest` guard covers `domain` and `contracts`; the new package is
written to the same rule.

## Correlation

One `TurnTraceRecorder` is created per conversation turn by `TurnTraceFactory`
with a unique `TraceId`. Every event the recorder emits carries that `traceId`
and (optionally) the `TurnId`. A streamed turn, its cancellation, and its
failure therefore stay correlated even though they are produced by different
stages.

`TraceId` is a separate value class from `TurnId` because multiple traces may
describe one turn (for example a retry) and a turn is a persistence concept
while a trace is a diagnostic one.

```kotlin
val trace = traceFactory.start(turnId, selection, runtime = "REMOTE_API")
val span = trace.start(DiagnosticStage.LLM_REQUEST)
trace.requestSelected(selection.providerId.value, selection.modelId.value)
trace.markStreamStarted()
stream.collect { event ->
    when (event) {
        is Delta -> trace.llmDelta(receivedAtNanos = clock.nanoTime(), characterCount = event.text.length)
        is Completed -> { trace.requestState("completed"); span.succeed(); trace.turnCompleted() }
        is Failed -> { trace.error(DiagnosticStage.LLM_REQUEST, event.error.code.name); span.fail() }
        is Cancelled -> { span.cancel(); trace.turnEnded(DiagnosticOutcome.CANCELLED) }
    }
}
```

`requestSelected` records the resolved provider/model, not just the user's
selection, so the trace shows the true origin even if orchestration passed a
different model than the recorder was constructed with (see
[docs/decisions.md](./decisions.md), OpenRouter silent-fallback hazard).

## Timing

All timing comes from an injected `MonotonicClock` (production:
`SystemMonotonicClock`, backed by `System.nanoTime`). Tests inject
`FakeMonotonicClock` and assert exact durations; no assertion depends on real
elapsed time.

| Measurement | Event / attribute |
| --- | --- |
| Stage duration | span `STARTED` -> terminal `durationNanos` |
| Time to first text | first `llmDelta` `durationNanos`, from `markStreamStarted` |
| Inter-delta gap | later `llmDelta` `durationNanos` |
| First audible TTS | `playbackStarted` (`STREAM_STATE=first-audible`) |
| TTS synthesis / playback | `TTS_SYNTHESIS` / `TTS_PLAYBACK` spans |
| Barge-in stop/cancel | `playbackStopped` / `bargeIn` `durationNanos` = onset -> stop |
| Delivered vs generated text | `playbackDelivered` `DELIVERED_CHARACTER_COUNT` / `TOTAL_CHARACTER_COUNT` |
| Turn totals | `TurnTraceSummary` from `recorder.summary()` |

`markStreamStarted` is what makes time-to-first-text meaningful: the first delta
reports its offset from the request start. If it is never called, the first
delta falls back to measuring from turn start.

## Non-blocking buffering

`BoundedDiagnosticsSink` is the default production sink. `record` only calls
`Channel.trySend` on an in-memory channel, so it never suspends and cannot block
the audio or UI path. A consumer coroutine drains the channel to `downstream`
off the caller's thread.

When the buffer is full, an event is dropped, but the drop is counted in
`droppedEventCount` (a `StateFlow<Long>`) rather than lost silently. A slow
downstream therefore degrades into an observable count. `InMemoryTraceStore`
retains a bounded window and reports `evictedEventCount` the same way.

## Viewer and export

- `TraceViewer.lines(events)` returns `TraceViewLine`s with buffer-relative
  milliseconds, stage, outcome, trace ID, duration, and enum attributes;
  `TraceViewer.render(lines)` prints one stable line per event.
- `TraceJsonExporter.export(events)` renders deterministic, newline-delimited
  JSON (one object per event) suitable for attaching to a test run.

Both read only typed events, so an export cannot carry free-form content: the
attribute model itself is the guarantee.

## Redaction and opt-in content

The default trace is content-free **by construction**: `DiagnosticEvent`
accepts only the closed `DiagnosticAttribute` enum (identifiers and counts), a
trace/turn ID, and monotonic timings. There is no field under which a
credential, transcript, prompt, audio, or provider response can be recorded by
accident. A failed event keeps only the stable `ErrorCode` name and category;
`VoiceAgentError.detail` is never accepted by the recorder.

`Redaction` handles imperative paths that still render values (HTTP headers,
debug logs, crash metadata): `Redaction.redact(value)` returns
`"[redacted]"` for any non-empty value and never echoes it, and
`Redaction.redactHeaders` redacts credential-bearing header names while leaving
safe ones (for example `Content-Type`, a model name) untouched.

Sensitive content capture is **off by default**. `InMemoryTraceContentStore`
retains content only when constructed with `TraceContentPolicy(enabled = true,
...)`, truncates each record to `maxCharactersPerRecord`, evicts the oldest
record past `maxRecords`, and is cleared by `clear()`. `NoOpTraceContentSink`
is the default and retains nothing.

## Testing

- `TurnTraceCorrelationTest` drives a fake streamed turn, a cancellation, and a
  typed failure, and asserts each set of events shares one `TraceId` and one
  `TurnId`.
- `TraceRedactionTest` asserts a realistic turn never contains a fake API key,
  transcript, prompt, or provider body in its events, export, or viewer output,
  and that content capture is off by default and bounded when enabled.
- `TraceTimingAndBufferTest` asserts exact durations from the fake clock, that a
  full buffer never blocks and counts drops, and that the store evicts and
  reports evictions.
- `TraceExportAndViewerTest` asserts the export/viewer are deterministic and
  content-free.

Verified commands (Windows host, wrapper):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat spotlessCheck
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

## Follow-up (owned by later milestones)

- M21 calls the recorder from real turn orchestration and owns the request-state
  vocabulary.
- M07/M11 call `playbackStarted` / `playbackStopped` from the real capture and
  TTS adapters.
- A developer-visible in-app viewer screen is deferred; M04 ships the local
  export/viewer core only.
- Drop/eviction counts should be surfaced in developer diagnostics once a UI
  surface exists.
