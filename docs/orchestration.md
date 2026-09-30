# Turn Orchestration and Cancellation (M21)

This document records the M21 turn orchestration: the deterministic state
machine that connects a finalized user turn to one provider request and on-device
TTS, and the rules that keep the persisted conversation truthful. It implements
the turn-orchestration boundary in [architecture.md](./architecture.md)
("Streaming and lifecycle") over the M02 domain/contracts, M05 persistence, M09
listening seam, M11 TTS, and M12 LLM contract.

It closes two tracked seam items: the request-state vocabulary and per-turn
recorder call site (R-0028) and the replacement of the M06 state holder's inline,
single-request handling with the real state machine (R-0032).

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/orchestration/
  TurnEvent.kt          typed inputs, each tagged with its turn ID
  TurnRecord.kt         immutable snapshots (generation + listening)
  TurnOutcome.kt        terminal classification + transcript rejection
  TurnStateMachine.kt   the pure, synchronous reducer
  TtsTextChunker.kt     splits streamed text into complete TTS chunks
  LlmRequestState.kt    finalized REQUEST_STATE vocabulary (R-0028)
  TurnRequest.kt        request/result/observer types
  TurnOrchestrator.kt   coroutine runtime over the contracts
```

The `orchestration` package contains no `android.*`/`androidx.*` import and does
not depend on the Compose `ui` package; `OrchestrationPurityTest` enforces both,
so the state machine is JVM-testable and no UI or vendor code controls pipeline
internals.

## Two layers

**`TurnStateMachine`** is a synchronous, side-effect-free reducer. It owns the
turn's phase, generated/queued/delivered text, terminal outcome, and stale-event
count. It has no clock, dispatcher, repository, provider, or TTS, so a test
drives a whole turn by feeding events and asserting snapshots.

**`TurnOrchestrator`** is the coroutine runtime. `run(request, observer)`
persists the finalized user turn, builds the bounded M05 request, consumes the M12
stream, feeds complete chunks to TTS, and persists only truthful assistant state.
Every observable transition goes through the state machine; the runtime only
performs the side effects (persistence, TTS, tracing) the machine determines.

## State model

`TurnPhase` (M02) is finalized with the edges the pipeline actually needs:

```text
IDLE -> LISTENING -> GENERATING -> SPEAKING -> COMPLETED
IDLE -> GENERATING                        (manual text: no listening stage)
GENERATING -> COMPLETED                   (a text-only reply)
GENERATING -> SPEAKING                    (first chunk queued/started)
GENERATING -> INTERRUPTED                 (barge-in before the first chunk)
GENERATING|SPEAKING -> CANCELLED | FAILED
INTERRUPTED -> LISTENING
```

`COMPLETED`, `CANCELLED`, and `FAILED` stay terminal. `TurnPhaseTest` covers the
new edges.

`TurnStateMachine` distinguishes outcomes explicitly, as M21 requires:

| `TurnOutcome` | Meaning |
| --- | --- |
| `Completed` | Generation finished and (with speech) all queued text was delivered. |
| `NoSpeech` | VAD/endpoint found no speech; no turn committed. |
| `EmptyTranscript` | Final transcript empty or below the confidence threshold. |
| `ProviderError` | Typed LLM failure; partial text kept. |
| `TtsFailure` | Synthesis/playback failed; the turn is **not** a success. |
| `Cancelled` | The user cancelled. |
| `Interrupted` | Barge-in cut assistant output short. |
| `PersistenceFailure` | A durable write failed; the caller must surface it. |

## Turn IDs discard stale work

Every `TurnEvent` carries the `TurnId` it belongs to. `reduce` applies an event
only when it names the machine's current turn and the turn has not reached a
terminal phase; anything else is dropped and counted in `staleEventDrops`, never
applied. This is what makes three races safe:

- **Late events after cancellation.** Once a cancel/interrupt settles the turn, a
  late `ProviderDelta` or `ProviderCompleted` cannot flip it back to success.
- **Superseded turns.** Starting a new turn replaces the active ID, so a
  straggler from the previous turn cannot mutate the new one.
- **Interim revisions.** A `Provisional` transcript is applied only when its
  `TranscriptRevision` is strictly newer, so a stale interim guess cannot
  overwrite a newer hypothesis.

## Request lifecycle vocabulary (R-0028)

`LlmRequestState` is the finalized set of `REQUEST_STATE` values:
`selected`, `streaming`, `completed`, `ended`, `cancelled`, `failed`. The
orchestrator drives the per-turn M04 `TurnTraceRecorder` with `requestSelected`,
`markStreamStarted`, `requestState`, `requestEndReason`, `requestUsage`, the
delta counters (`llmDelta`), and — when speech is used — `playbackStarted` and
`playbackDelivered`. Only identities, counts, and stable names reach the trace;
prompt, transcript, and delta text never do (R-0068).

## Truthful persistence

- **Generated vs delivered.** `TtsPlaybackAccounting` (M11) tracks queued,
  started, and audible text. Persisted `AssistantDelivery.deliveredText` is the
  longest common prefix of the generated text, so it is always a valid prefix and
  an interruption cannot claim unheard speech.
- **Text-only turns** mirror delivery to generation: `Completed -> COMPLETED`,
  `Cancelled -> INTERRUPTED`, `Failed -> FAILED`.
- **Nothing to persist.** A turn with no generated and no delivered text (for
  example, cancelled before any output) leaves no phantom assistant turn.
- **No silent completion.** A stream that ends without a terminal event is
  persisted as `FAILED` with `LLM_MALFORMED_RESPONSE`, never as a completion.
- **No silent fallback.** A provider that reports a different model than the
  selection is recorded in the trace (`REPORTED_MODEL_ID`) instead of accepted
  silently; orchestration never reroutes a request (R-0017, R-0023).

## Cancellation vs barge-in

- **Caller cancellation** cancels the running coroutine; the runtime catches
  `CancellationException`, stops TTS, reduces `CancelRequested` in a
  `NonCancellable` block, persists the interrupted turn, and rethrows.
- **Barge-in** calls `interrupt(onsetAtNanos)`, which cancels the in-flight
  provider request and TTS jobs from another coroutine and settles the turn as
  `Interrupted`, preserving the audible prefix.

Both stop playback and cancel generation; neither is stored as a completed reply.

## TTS chunking

`TtsTextChunker` buffers streamed deltas and emits a chunk only at a sentence
boundary or once the buffer reaches a character cap, flushing the trailing
fragment when the stream ends. Each chunk is spoken through a
`TextToSpeech.speak` collection and accounted as queued/started/delivered. This
keeps delivered text ordered and truthful; overlapping synthesis latency is
deferred to M24/M25 (see R-0080).

## M06 UI integration (R-0032)

`ConversationViewModel` keeps conversation-level concerns (history list, open,
delete, composer, provisional display, notices) and delegates the generation
pipeline to `TurnOrchestrator`. It observes a `TurnObserver` (user-turn
committed, live assistant text, finished) and maps the terminal `TurnRecord` to
the existing `ConversationDialogState` (`phase`, `turns`, `notice`). The M06 UI
behavior and tests are unchanged, but the ViewModel no longer folds the stream,
computes partial text, or accounts delivery itself.

## Testing

Deterministic JVM tests under `:app:testDebugUnitTest`:

- `TurnStateMachineTest` — manual and voice turns, revisions, no-speech/empty/
  low-confidence, provider failure, remote cancellation, user cancel, barge-in,
  TTS failure, out-of-order late events, superseded turns, delivery accounting.
- `TurnOrchestratorTest` — end-to-end turns over the deterministic fakes:
  persistence, live text, bounded context, cancellation race, interruption,
  TTS failure, retry-safe failure, content-free trace, and model-mismatch trace.
- `TtsTextChunkerTest`, `OrchestrationPurityTest`, plus the finalized
  `TurnPhaseTest` edges.

A compile-only instrumented test (`orchestration.TurnOrchestrationInstrumentedTest`)
runs the real orchestrator with an inline fake on device; it is compiled by
`:app:assembleDebugAndroidTest` and run only by `:app:connectedDebugAndroidTest`.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebugAndroidTest
```

## Limitations and next steps

- The live capture → VAD → STT → orchestration loop is not assembled; M21 exposes
  the listening/revision seam, and M24 wires it end to end (R-0063, R-0082).
- TTS chunk overlap and first-audible latency are unmeasured on device (R-0080).
- The delivery wait has a bounded timeout that degrades to `Interrupted`; real
  engine hang behavior is device-unverified (R-0081).
- The UI still runs one active turn at a time; orchestrator-level supersede is
  tested but the app does not expose interrupting an active turn with a new
  submission yet (R-0083).
